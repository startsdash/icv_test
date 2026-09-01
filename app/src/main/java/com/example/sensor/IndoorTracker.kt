package com.example.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import com.example.model.CleaningMode
import com.example.model.CoverageSegment
import com.example.model.FacilityZone
import com.example.model.ObjectCategory
import com.example.model.PdrConfig
import com.example.model.PerimeterMappingState
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
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Модуль автономной навигации PDR (Pedestrian Dead Reckoning),
 * расчета карты покрытия уборки (Coverage Mapping) и разметки контуров объектов (подъезды, дворы).
 */
class IndoorTracker(
    private val context: Context,
    private var config: PdrConfig = PdrConfig()
) : SensorEventListener {

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magnetometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val trackerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Выделенный системный поток для непрерывного опроса датчиков
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    private val sensorEventFlow = MutableSharedFlow<RawSensorData>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val _trackerState = MutableStateFlow(
        TrackerState(
            currentPosition = Position(x = 0.0, y = 0.0, floor = 1),
            currentStepLength = config.stepLength,
            cleaningWidthMeters = 0.5,
            cleaningMode = CleaningMode.WET_CLEANING
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

    // Параметры уборочного инвентаря
    private var cleaningWidthMeters = 0.5
    private var currentCleaningMode = CleaningMode.WET_CLEANING
    private var coveredAreaM2 = 0.0

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
        // Добавляем типовые зоны по умолчанию для быстрого старта клинера
        val defaultZones = listOf(
            FacilityZone(
                id = "zone_entrance_1_fl1",
                name = "Подъезд 1 • Холл 1 этажа",
                category = ObjectCategory.ENTRANCE_BUILDING,
                floor = 1,
                areaSquareMeters = 36.0,
                polygonPoints = listOf(
                    Position(-3.0, -3.0, 1),
                    Position(3.0, -3.0, 1),
                    Position(3.0, 3.0, 1),
                    Position(-3.0, 3.0, 1)
                ),
                colorHex = 0xFF0284C7
            ),
            FacilityZone(
                id = "zone_yard_playground",
                name = "Двор • Детская площадка",
                category = ObjectCategory.OUTDOOR_YARD,
                floor = 1,
                areaSquareMeters = 120.0,
                polygonPoints = listOf(
                    Position(5.0, 5.0, 1),
                    Position(15.0, 5.0, 1),
                    Position(15.0, 15.0, 1),
                    Position(5.0, 15.0, 1)
                ),
                colorHex = 0xFF10B981
            )
        )
        _trackerState.update { it.copy(savedZones = defaultZones, currentZone = defaultZones.first()) }

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

        isGravityInitialized = false
        lastStepTimestampNs = 0L
        lastGyroTimestampNs = 0L

        try {
            val thread = HandlerThread("PdrSensorBackgroundThread", Process.THREAD_PRIORITY_MORE_FAVORABLE)
            thread.start()
            sensorThread = thread
            val handler = Handler(thread.looper)
            sensorHandler = handler

            sensorManager?.let { sm ->
                accelerometer?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler) }
                gyroscope?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler) }
                magnetometer?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler) }
            }
            Log.i("IndoorTracker", "Sensor listener registered on background HandlerThread")
        } catch (e: Exception) {
            Log.e("IndoorTracker", "Failed to start sensor thread: ${e.message}", e)
        }

        _trackerState.update {
            it.copy(
                isTracking = true,
                currentStepLength = config.stepLength
            )
        }
    }

    /**
     * Остановка отслеживания.
     */
    fun stopTracking() {
        if (!isTracking) return
        isTracking = false
        try {
            sensorManager?.unregisterListener(this)
            sensorThread?.quitSafely()
            sensorThread = null
            sensorHandler = null
            Log.i("IndoorTracker", "Sensor listener unregistered and thread stopped")
        } catch (e: Exception) {
            Log.e("IndoorTracker", "Error stopping sensor tracker: ${e.message}", e)
        }
        _trackerState.update { it.copy(isTracking = false) }
    }

    /**
     * Смена режима уборки (Влажная, Сухая, Санобработка, Простой)
     */
    fun updateCleaningMode(mode: CleaningMode) {
        currentCleaningMode = mode
        _trackerState.update { it.copy(cleaningMode = mode) }
    }

    /**
     * Смена ширины захвата швабры / насадки пылесоса в метрах
     */
    fun updateCleaningWidth(widthMeters: Double) {
        cleaningWidthMeters = widthMeters.coerceIn(0.2, 2.5)
        _trackerState.update { it.copy(cleaningWidthMeters = cleaningWidthMeters) }
    }

    /**
     * Сброс координат в (0,0) или заданную начальную точку.
     */
    fun resetPosition(x: Double = 0.0, y: Double = 0.0, headingDeg: Float = 0f) {
        currentX = x
        currentY = y
        stepCount = 0
        totalDistance = 0.0
        coveredAreaM2 = 0.0
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
                coveredAreaM2 = 0.0,
                headingDegrees = headingDeg,
                trajectory = listOf(startPos),
                coverageSegments = emptyList()
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

    // =========================================================================
    //  РЕЖИМ РАЗМЕТКИ ПЕРИМЕТРА ОБЪЕКТА (Building Map / Perimeter SLAM)
    // =========================================================================

    /**
     * Старт режима разметки периметра для нового объекта (этаж подъезда, двор и т.д.)
     */
    fun startPerimeterMapping(name: String, category: ObjectCategory, floor: Int = currentFloor) {
        val startPt = Position(currentX, currentY, floor, System.currentTimeMillis())
        val newState = PerimeterMappingState(
            isMapping = true,
            zoneName = name.ifEmpty { "Объект #${_trackerState.value.savedZones.size + 1}" },
            category = category,
            floor = floor,
            perimeterPoints = listOf(startPt),
            isClosed = false,
            computedPerimeterMeters = 0.0,
            computedAreaMeters = 0.0
        )
        _trackerState.update { it.copy(perimeterState = newState) }
    }

    /**
     * Добавление контрольной точки / угла в контур объекта
     */
    fun addPerimeterPoint(position: Position = _trackerState.value.currentPosition) {
        val currentPerimeter = _trackerState.value.perimeterState
        if (!currentPerimeter.isMapping || currentPerimeter.isClosed) return

        val newPoints = currentPerimeter.perimeterPoints + position
        val perimeterLen = computePerimeterLength(newPoints)
        val area = computePolygonArea(newPoints)

        val updated = currentPerimeter.copy(
            perimeterPoints = newPoints,
            computedPerimeterMeters = perimeterLen,
            computedAreaMeters = area
        )
        _trackerState.update { it.copy(perimeterState = updated) }
    }

    /**
     * Замыкание контура и сохранение размеченного объекта в библиотеку зон
     */
    fun closePerimeter(): FacilityZone? {
        val pState = _trackerState.value.perimeterState
        if (!pState.isMapping || pState.perimeterPoints.size < 3) return null

        val finalPoints = pState.perimeterPoints
        val finalArea = computePolygonArea(finalPoints).coerceAtLeast(1.0)
        val zoneColor = when (pState.category) {
            ObjectCategory.ENTRANCE_BUILDING -> 0xFF0284C7
            ObjectCategory.OUTDOOR_YARD -> 0xFF10B981
        }

        val newZone = FacilityZone(
            id = "zone_${UUID.randomUUID().toString().take(8)}",
            name = pState.zoneName,
            category = pState.category,
            floor = pState.floor,
            polygonPoints = finalPoints,
            areaSquareMeters = finalArea,
            colorHex = zoneColor
        )

        _trackerState.update { state ->
            val updatedZones = state.savedZones + newZone
            state.copy(
                savedZones = updatedZones,
                currentZone = newZone,
                perimeterState = pState.copy(isClosed = true, isMapping = false)
            )
        }
        return newZone
    }

    /**
     * Быстрое создание объекта с заданными геометрическими размерами (прямоугольник W x H).
     */
    fun createQuickZone(
        name: String,
        category: ObjectCategory,
        floor: Int,
        widthMeters: Double,
        heightMeters: Double
    ): FacilityZone {
        val halfW = widthMeters / 2.0
        val halfH = heightMeters / 2.0
        val points = listOf(
            Position(-halfW, -halfH, floor),
            Position(halfW, -halfH, floor),
            Position(halfW, halfH, floor),
            Position(-halfW, halfH, floor)
        )
        val area = widthMeters * heightMeters
        val zoneColor = when (category) {
            ObjectCategory.ENTRANCE_BUILDING -> 0xFF0284C7
            ObjectCategory.OUTDOOR_YARD -> 0xFF10B981
        }

        val newZone = FacilityZone(
            id = "zone_${UUID.randomUUID().toString().take(8)}",
            name = name.ifEmpty { "${category.title} (${widthMeters.toInt()}×${heightMeters.toInt()} м)" },
            category = category,
            floor = floor,
            polygonPoints = points,
            areaSquareMeters = area,
            colorHex = zoneColor
        )

        _trackerState.update { state ->
            val updatedZones = state.savedZones + newZone
            state.copy(
                savedZones = updatedZones,
                currentZone = newZone
            )
        }
        return newZone
    }

    /**
     * Отмена текущей разметки периметра
     */
    fun cancelPerimeterMapping() {
        _trackerState.update { it.copy(perimeterState = PerimeterMappingState()) }
    }

    /**
     * Выбор активного объекта/зоны
     */
    fun selectActiveZone(zone: FacilityZone?) {
        _trackerState.update { it.copy(currentZone = zone) }
    }

    /**
     * Удаление сохраненного объекта
     */
    fun deleteZone(zoneId: String) {
        _trackerState.update { state ->
            val filtered = state.savedZones.filterNot { it.id == zoneId }
            val active = if (state.currentZone?.id == zoneId) filtered.firstOrNull() else state.currentZone
            state.copy(savedZones = filtered, currentZone = active)
        }
    }

    // =========================================================================
    //  ГЕОМЕТРИЧЕСКИЕ ВЫЧИСЛЕНИЯ ПЛОЩАДИ И ПЕРИМЕТРА (Формула Гаусса)
    // =========================================================================

    private fun computePolygonArea(points: List<Position>): Double {
        if (points.size < 3) return 0.0
        var sum = 0.0
        val n = points.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            sum += (points[i].x * points[j].y) - (points[j].x * points[i].y)
        }
        return abs(sum) / 2.0
    }

    private fun computePerimeterLength(points: List<Position>): Double {
        if (points.size < 2) return 0.0
        var dist = 0.0
        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            val dx = p2.x - p1.x
            val dy = p2.y - p1.y
            dist += sqrt(dx * dx + dy * dy)
        }
        return dist
    }

    // =========================================================================
    //  ОБРАБОТКА ДАТЧИКОВ (SensorEventListener)
    // =========================================================================

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isTracking || event == null) return

        val raw = RawSensorData(
            sensorType = event.sensor.type,
            values = event.values.clone(),
            timestampNs = event.timestamp
        )
        sensorEventFlow.tryEmit(raw)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun processSensorEvent(data: RawSensorData) {
        when (data.sensorType) {
            Sensor.TYPE_ACCELEROMETER -> handleAccelerometer(data.values, data.timestampNs)
            Sensor.TYPE_GYROSCOPE -> handleGyroscope(data.values, data.timestampNs)
            Sensor.TYPE_MAGNETIC_FIELD -> handleMagnetometer(data.values)
        }
    }

    private fun handleAccelerometer(values: FloatArray, timestampNs: Long) {
        val ax = values[0]
        val ay = values[1]
        val az = values[2]

        lastAccelRaw[0] = ax
        lastAccelRaw[1] = ay
        lastAccelRaw[2] = az
        hasAccel = true

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

        val linearX = ax - gravity[0]
        val linearY = ay - gravity[1]
        val linearZ = az - gravity[2]

        val dynamicAccelMagnitude = sqrt(linearX * linearX + linearY * linearY + linearZ * linearZ)
        val totalMagnitude = sqrt(ax * ax + ay * ay + az * az)

        val minIntervalNs = config.stepDeadTimeMs * 1_000_000L
        val timeSinceLastStepNs = timestampNs - lastStepTimestampNs

        if (dynamicAccelMagnitude > config.stepThreshold) {
            if (!isPeakCandidate || dynamicAccelMagnitude > peakCandidateVal) {
                isPeakCandidate = true
                peakCandidateVal = dynamicAccelMagnitude
            }
        } else if (isPeakCandidate) {
            if (timeSinceLastStepNs >= minIntervalNs) {
                onStepDetected(timestampNs)
                lastStepTimestampNs = timestampNs
            }
            isPeakCandidate = false
            peakCandidateVal = 0f
        }

        lastAccelMagnitude = totalMagnitude
        _trackerState.update { it.copy(currentAccelMagnitude = totalMagnitude) }
    }

    private fun handleGyroscope(values: FloatArray, timestampNs: Long) {
        if (lastGyroTimestampNs != 0L) {
            val dt = (timestampNs - lastGyroTimestampNs) * 1.0e-9

            if (dt in 0.0001..0.5) {
                val gz = values[2].toDouble()
                headingRadians += gz * dt
                headingRadians = (headingRadians % (2 * PI) + 2 * PI) % (2 * PI)

                if (config.useMagnetometerCorrection && hasMag && hasAccel) {
                    val angleDiff = normalizeAngleDifference(magHeadingRadians - headingRadians)
                    headingRadians += 0.02 * angleDiff
                    headingRadians = (headingRadians % (2 * PI) + 2 * PI) % (2 * PI)
                }

                val headingDegrees = Math.toDegrees(headingRadians).toFloat()
                _trackerState.update { it.copy(headingDegrees = headingDegrees) }
            }
        }
        lastGyroTimestampNs = timestampNs
    }

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
                val rawAzimuth = orientation[0].toDouble()
                magHeadingRadians = (rawAzimuth + 2 * PI) % (2 * PI)
            }
        }
    }

    private fun onStepDetected(timestampNs: Long) {
        val prevPos = Position(currentX, currentY, currentFloor, System.currentTimeMillis())

        stepCount++
        val stepLen = config.stepLength
        totalDistance += stepLen

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

        // Добавляем сегмент полосы покрытия уборки
        val segment = CoverageSegment(
            start = prevPos,
            end = newPos,
            cleaningWidthMeters = cleaningWidthMeters,
            mode = currentCleaningMode,
            timestamp = System.currentTimeMillis()
        )

        // Площадь уборки (м²): при активной уборке увеличиваем на (длина шага * ширина насадки)
        if (currentCleaningMode != CleaningMode.IDLE_TRANSIT) {
            coveredAreaM2 += stepLen * cleaningWidthMeters
        }

        _trackerState.update { state ->
            val updatedTrajectory = (state.trajectory + newPos).takeLast(500)
            val updatedSegments = (state.coverageSegments + segment).takeLast(500)

            // Если включен режим разметки периметра, также автоматически добавляем точки в периметр
            val updatedPerimeter = if (state.perimeterState.isMapping && !state.perimeterState.isClosed) {
                val pPoints = state.perimeterState.perimeterPoints + newPos
                state.perimeterState.copy(
                    perimeterPoints = pPoints,
                    computedPerimeterMeters = computePerimeterLength(pPoints),
                    computedAreaMeters = computePolygonArea(pPoints)
                )
            } else {
                state.perimeterState
            }

            state.copy(
                currentPosition = newPos,
                stepCount = stepCount,
                totalDistance = totalDistance,
                coveredAreaM2 = coveredAreaM2,
                headingDegrees = Math.toDegrees(headingRadians).toFloat(),
                trajectory = updatedTrajectory,
                coverageSegments = updatedSegments,
                perimeterState = updatedPerimeter
            )
        }
    }

    /**
     * Симуляция шага для тестирования полосы покрытия и траектории
     */
    fun simulateStep(pos: Position, headingDeg: Float, newStepCount: Int) {
        val prevPos = Position(currentX, currentY, currentFloor, System.currentTimeMillis())

        currentX = pos.x
        currentY = pos.y
        stepCount = newStepCount
        val stepLen = config.stepLength
        totalDistance = newStepCount * stepLen
        headingRadians = Math.toRadians(headingDeg.toDouble())

        if (currentCleaningMode != CleaningMode.IDLE_TRANSIT) {
            coveredAreaM2 += stepLen * cleaningWidthMeters
        }

        val segment = CoverageSegment(
            start = prevPos,
            end = pos,
            cleaningWidthMeters = cleaningWidthMeters,
            mode = currentCleaningMode,
            timestamp = System.currentTimeMillis()
        )

        _trackerState.update { state ->
            val updatedTrajectory = (state.trajectory + pos).takeLast(500)
            val updatedSegments = (state.coverageSegments + segment).takeLast(500)
            state.copy(
                currentPosition = pos,
                stepCount = stepCount,
                totalDistance = totalDistance,
                coveredAreaM2 = coveredAreaM2,
                headingDegrees = headingDeg,
                trajectory = updatedTrajectory,
                coverageSegments = updatedSegments
            )
        }
    }

    private fun normalizeAngleDifference(diff: Double): Double {
        var d = diff % (2 * PI)
        if (d > PI) d -= 2 * PI
        if (d < -PI) d += 2 * PI
        return d
    }

    private data class RawSensorData(
        val sensorType: Int,
        val values: FloatArray,
        val timestampNs: Long
    )
}
