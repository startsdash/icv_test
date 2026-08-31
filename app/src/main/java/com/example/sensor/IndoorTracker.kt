package com.example.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.model.PdrConfig
import com.example.model.Position
import com.example.model.TrackerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Модуль автономного позиционирования внутри помещений (PDR - Pedestrian Dead Reckoning).
 * Работает без GPS, лидаров и ARCore, опираясь исключительно на инерциальные датчики (IMU):
 * - Акселерометр: детекция шагов по пикам динамического ускорения.
 * - Гироскоп: высокочастотная интеграция угловой скорости для отслеживания курса/азимута.
 * - Магнитометр: опциональная долгосрочная коррекция накопленного дрейфа гироскопа.
 *
 * Все вычисления производятся в фоновом пуле корутин (Dispatchers.Default).
 */
class IndoorTracker(
    context: Context,
    private var config: PdrConfig = PdrConfig()
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magnetometer = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    // Фоновый скоуп для математических расчётов вне UI-потока
    private val trackerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Буферизованный канал событий датчиков для быстрой передачи из callback в фоновый поток
    private val sensorEventFlow = MutableSharedFlow<RawSensorData>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val _trackerState = MutableStateFlow(
        TrackerState(
            currentPosition = Position(x = 0.0, y = 0.0, floor = 1),
            currentStepLength = config.stepLength
        )
    )
    val trackerState: StateFlow<TrackerState> = _trackerState.asStateFlow()

    // --- Внутреннее состояние PDR ---
    private var isTracking = false
    private var currentFloor = 1
    private var currentX = 0.0
    private var currentY = 0.0
    private var stepCount = 0
    private var totalDistance = 0.0
    private var headingRadians = 0.0 // Курс в радианах (0 = Север / +Y, PI/2 = Восток / +X)

    // Гравитационный фильтр (Low-Pass Filter) для выделения вектора тяжести
    private val gravity = FloatArray(3) { 0f }
    private var isGravityInitialized = false
    private val alphaGravity = 0.85f

    // Детектор шагов: переменные состояния
    private var lastStepTimestampNs: Long = 0L
    private var lastAccelMagnitude: Float = 9.81f
    private var isPeakCandidate = false
    private var peakCandidateVal = 0f

    // Гироскоп: интеграция угловой скорости
    private var lastGyroTimestampNs: Long = 0L

    // Магнитометр + Акселерометр: вычисление абсолютного азимута для комплементарного фильтра
    private val lastAccelRaw = FloatArray(3)
    private val lastMagRaw = FloatArray(3)
    private var hasAccel = false
    private var hasMag = false
    private var magHeadingRadians = 0.0

    init {
        // Запуск фоновой обработки данных датчиков
        trackerScope.launch {
            sensorEventFlow.collect { event ->
                processSensorEvent(event)
            }
        }
    }

    /**
     * Запуск отслеживания перемещений.
     */
    fun startTracking() {
        if (isTracking) return
        isTracking = true
        lastStepTimestampNs = 0L
        lastGyroTimestampNs = 0L

        sensorManager?.let { sm ->
            // Частота опроса: SENSOR_DELAY_GAME (~20-50 Гц) оптимальна для PDR без перегрузки батареи
            accelerometer?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            gyroscope?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            magnetometer?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }

        _trackerState.update {
            it.copy(
                isTracking = true,
                currentPosition = Position(
                    x = currentX,
                    y = currentY,
                    floor = currentFloor,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Остановка отслеживания.
     */
    fun stopTracking() {
        if (!isTracking) return
        isTracking = false
        sensorManager?.unregisterListener(this)
        _trackerState.update { it.copy(isTracking = false) }
    }

    /**
     * Сброс координат в (0,0) или заданную начальную точку.
     */
    fun resetPosition(x: Double = 0.0, y: Double = 0.0, headingDeg: Float = 0f) {
        currentX = x
        currentY = y
        stepCount = 0
        totalDistance = 0.0
        headingRadians = Math.toRadians(headingDeg.toDouble())

        val startPos = Position(
            x = currentX,
            y = currentY,
            floor = currentFloor,
            timestamp = System.currentTimeMillis()
        )

        _trackerState.update {
            it.copy(
                currentPosition = startPos,
                stepCount = 0,
                totalDistance = 0.0,
                headingDegrees = headingDeg,
                trajectory = listOf(startPos)
            )
        }
    }

    /**
     * Установка текущего этажа клинера.
     */
    fun setFloor(floor: Int) {
        currentFloor = floor
        _trackerState.update {
            val updatedPos = it.currentPosition.copy(floor = floor, timestamp = System.currentTimeMillis())
            it.copy(currentPosition = updatedPos)
        }
    }

    /**
     * Обновление конфигурации PDR (калибровка длины шага и порогов).
     */
    fun updateConfig(newConfig: PdrConfig) {
        this.config = newConfig
        _trackerState.update { it.copy(currentStepLength = newConfig.stepLength) }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isTracking || event == null) return

        // Быстрая упаковка данных и отправка в Dispatchers.Default без блокировки потока датчиков
        val raw = RawSensorData(
            sensorType = event.sensor.type,
            values = event.values.clone(),
            timestampNs = event.timestamp
        )
        sensorEventFlow.tryEmit(raw)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Обработка изменения точности при необходимости
    }

    /**
     * Основной алгоритм PDR, выполняющийся в Dispatchers.Default:
     * 1. Интеграция гироскопа (курс).
     * 2. Выделение гравитации и детекция шага (акселерометр).
     * 3. Смещение координат (Dead Reckoning).
     */
    private fun processSensorEvent(data: RawSensorData) {
        when (data.sensorType) {
            Sensor.TYPE_ACCELEROMETER -> {
                handleAccelerometer(data.values, data.timestampNs)
            }
            Sensor.TYPE_GYROSCOPE -> {
                handleGyroscope(data.values, data.timestampNs)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                handleMagnetometer(data.values)
            }
        }
    }

    /**
     * Обработка данных акселерометра:
     * - Вычисление полного модуля ускорения |a| = sqrt(x^2 + y^2 + z^2)
     * - Низкочастотный фильтр гравитации
     * - Детекция пиков динамического ускорения (удара стопы о пол) с окном нечувствительности.
     */
    private fun handleAccelerometer(values: FloatArray, timestampNs: Long) {
        val ax = values[0]
        val ay = values[1]
        val az = values[2]

        lastAccelRaw[0] = ax
        lastAccelRaw[1] = ay
        lastAccelRaw[2] = az
        hasAccel = true

        // Выделение гравитации LPF
        if (!isGravityInitialized) {
            gravity[0] = ax
            gravity[1] = ay
            gravity[2] = az
            isGravityInitialized = true
        } else {
            gravity[0] = alphaGravity * gravity[0] + (1 - alphaGravity) * ax
            gravity[1] = alphaGravity * gravity[1] + (1 - alphaGravity) * ay
            gravity[2] = alphaGravity * gravity[2] + (1 - alphaGravity) * az
        }

        // Динамическое линейное ускорение (без гравитации)
        val linearX = ax - gravity[0]
        val linearY = ay - gravity[1]
        val linearZ = az - gravity[2]

        // Модуль динамического ускорения
        val dynamicAccelMagnitude = sqrt(linearX * linearX + linearY * linearY + linearZ * linearZ)
        val totalMagnitude = sqrt(ax * ax + ay * ay + az * az)

        // Алгоритм поиска пика шага с окном нечувствительности (Dead Time)
        val minIntervalNs = config.stepDeadTimeMs * 1_000_000L
        val timeSinceLastStepNs = timestampNs - lastStepTimestampNs

        if (dynamicAccelMagnitude > config.stepThreshold) {
            if (!isPeakCandidate || dynamicAccelMagnitude > peakCandidateVal) {
                isPeakCandidate = true
                peakCandidateVal = dynamicAccelMagnitude
            }
        } else if (isPeakCandidate) {
            // Спал ниже порога после превышения — фиксируем завершённый пик шага
            if (timeSinceLastStepNs >= minIntervalNs) {
                onStepDetected(timestampNs)
                lastStepTimestampNs = timestampNs
            }
            isPeakCandidate = false
            peakCandidateVal = 0f
        }

        lastAccelMagnitude = totalMagnitude

        // Обновляем текущий модуль ускорения для телеметрии
        _trackerState.update {
            it.copy(currentAccelMagnitude = totalMagnitude)
        }
    }

    /**
     * Обработка гироскопа:
     * Интеграция угловой скорости вокруг вертикальной оси Z (yaw) с учётом дельты времени dt.
     */
    private fun handleGyroscope(values: FloatArray, timestampNs: Long) {
        if (lastGyroTimestampNs != 0L) {
            val dt = (timestampNs - lastGyroTimestampNs) * 1.0e-9 // секунды

            if (dt in 0.0001..0.5) {
                // В стандартном положении смартфона в руке/кармане вертикальная ось вращения — Z
                // Для произвольного наклона учитываем ориентацию устройства относительно гравитации
                val gz = values[2].toDouble()

                // Интегрируем угловую скорость
                headingRadians += gz * dt

                // Нормализация угла в диапазон [0, 2*PI)
                headingRadians = (headingRadians % (2 * PI) + 2 * PI) % (2 * PI)

                // Опциональная мягкая коррекция курса по магнитометру (комплементарный фильтр)
                if (config.useMagnetometerCorrection && hasMag && hasAccel) {
                    val angleDiff = normalizeAngleDifference(magHeadingRadians - headingRadians)
                    // Весовой коэффициент коррекции (0.02 = 2% магнитного компаса, 98% гироскопа)
                    headingRadians += 0.02 * angleDiff
                    headingRadians = (headingRadians % (2 * PI) + 2 * PI) % (2 * PI)
                }

                val headingDegrees = Math.toDegrees(headingRadians).toFloat()

                _trackerState.update {
                    it.copy(headingDegrees = headingDegrees)
                }
            }
        }
        lastGyroTimestampNs = timestampNs
    }

    /**
     * Обработка магнитометра для расчёта абсолютного магнитного азимута.
     */
    private fun handleMagnetometer(values: FloatArray) {
        lastMagRaw[0] = values[0]
        lastMagRaw[1] = values[1]
        lastMagRaw[2] = values[2]
        hasMag = true

        if (hasAccel) {
            val rotationMatrix = FloatArray(9)
            val inclinationMatrix = FloatArray(9)
            val success = SensorManager.getRotationMatrix(
                rotationMatrix,
                inclinationMatrix,
                lastAccelRaw,
                lastMagRaw
            )

            if (success) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientation)
                // orientation[0] — азимут (угол относительно магнитного севера в радианах)
                val rawAzimuth = orientation[0].toDouble()
                magHeadingRadians = (rawAzimuth + 2 * PI) % (2 * PI)
            }
        }
    }

    /**
     * Вызывается при подтверждённом шаге:
     * Расчёт новых координат:
     * dx = stepLength * sin(heading)
     * dy = stepLength * cos(heading)
     */
    private fun onStepDetected(timestampNs: Long) {
        stepCount++
        val stepLen = config.stepLength
        totalDistance += stepLen

        // По направлению курса: 0 рад = Север (+Y), PI/2 рад = Восток (+X)
        val dx = stepLen * sin(headingRadians)
        val dy = stepLen * cos(headingRadians)

        currentX += dx
        currentY += dy

        val newPos = Position(
            x = currentX,
            y = currentY,
            floor = currentFloor,
            timestamp = System.currentTimeMillis()
        )

        _trackerState.update { state ->
            val updatedTrajectory = (state.trajectory + newPos).takeLast(500) // Ограничиваем историю для производительности
            state.copy(
                currentPosition = newPos,
                stepCount = stepCount,
                totalDistance = totalDistance,
                headingDegrees = Math.toDegrees(headingRadians).toFloat(),
                trajectory = updatedTrajectory
            )
        }
    }

    /**
     * Нормализация разницы двух углов в диапазон [-PI, PI]
     */
    private fun normalizeAngleDifference(diff: Double): Double {
        var d = diff % (2 * PI)
        if (d > PI) d -= 2 * PI
        if (d < -PI) d += 2 * PI
        return d
    }

    /**
     * Пакет сырых данных датчика.
     */
    private data class RawSensorData(
        val sensorType: Int,
        val values: FloatArray,
        val timestampNs: Long
    )
}
