package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.model.ConnectionState
import com.example.model.PdrConfig
import com.example.model.Position
import com.example.model.ServerMapConfig
import com.example.model.TrackerState
import com.example.model.UserPosition
import com.example.sensor.IndoorTracker
import com.example.socket.SocketService
import com.example.wifi.WifiScanner
import com.example.wifi.WifiScannerStub
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * ViewModel для управления бизнес-логикой приложения клинера:
 * - Управление PDR-трекером (IndoorTracker)
 * - Преобразование относительных метров PDR в координаты карты сервера [0..800, 0..600]
 * - Управление Socket.io подключением и отправкой координат каждые 2 сек
 * - Предоставление реактивного состояния для Jetpack Compose UI
 */
class CleanerTrackerViewModel(application: Application) : AndroidViewModel(application) {

    private val socketService = SocketService(application.applicationContext)
    private val indoorTracker = IndoorTracker(application.applicationContext)
    val wifiScanner: WifiScanner = WifiScannerStub()

    val userId: String = socketService.userId
    val connectionState: StateFlow<ConnectionState> = socketService.connectionState
    val mapUpdates: StateFlow<List<UserPosition>> = socketService.mapUpdateSnapshot

    private var periodicSendJob: Job? = null
    private var durationTimerJob: Job? = null
    private var simulationJob: Job? = null

    // Таймер текущей сессии уборки в секундах
    private val _sessionDurationSeconds = MutableStateFlow<Long>(0L)
    val sessionDurationSeconds: StateFlow<Long> = _sessionDurationSeconds.asStateFlow()

    // Флаг активной симуляции
    private val _isSimulating = MutableStateFlow(false)
    val isSimulating: StateFlow<Boolean> = _isSimulating.asStateFlow()

    // Настройки PDR
    private val _pdrConfig = MutableStateFlow(PdrConfig())
    val pdrConfig: StateFlow<PdrConfig> = _pdrConfig.asStateFlow()

    // Настройки отображения на карте сервера (800x600 px)
    private val _serverMapConfig = MutableStateFlow(
        ServerMapConfig(
            originX = 400.0,
            originY = 300.0,
            pixelsPerMeter = 20.0,
            cleanerName = socketService.cleanerName.value.ifEmpty { "cleaner-1" }
        )
    )
    val serverMapConfig: StateFlow<ServerMapConfig> = _serverMapConfig.asStateFlow()

    // Логи для панели отладки
    private val _activityLogs = MutableStateFlow<List<String>>(listOf("Система инициализирована. Готов к уборке."))
    val activityLogs: StateFlow<List<String>> = _activityLogs.asStateFlow()

    // Объединённый поток состояния UI
    val uiState: StateFlow<TrackerUiState> = combine(
        indoorTracker.trackerState,
        connectionState,
        socketService.packetsSentCount,
        _serverMapConfig,
        _isSimulating
    ) { trackerState, connState, packetsCount, mapCfg, simulating ->
        val (sx, sy) = calculateServerCoords(trackerState.currentPosition.x, trackerState.currentPosition.y, mapCfg)
        TrackerUiState(
            trackerState = trackerState.copy(
                packetsSentCount = packetsCount,
                lastSentTimestamp = socketService.lastSentTimestamp.value
            ),
            connectionState = connState,
            userId = userId,
            serverX = sx,
            serverY = sy,
            cleanerName = mapCfg.cleanerName,
            isSimulating = simulating
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = TrackerUiState(userId = userId)
    )

    init {
        // Подключаемся к сокет-серверу при запуске
        socketService.connect()
        addLog("Подключение к серверу ${SocketService.SERVER_URL}...")
    }

    /**
     * Конвертация относительных метров PDR (x, y) в абсолютные пиксели карты сервера [0..800, 0..600].
     */
    fun calculateServerCoordinates(pdrX: Double, pdrY: Double): Pair<Double, Double> {
        return calculateServerCoords(pdrX, pdrY, _serverMapConfig.value)
    }

    private fun calculateServerCoords(pdrX: Double, pdrY: Double, cfg: ServerMapConfig): Pair<Double, Double> {
        val sx = (cfg.originX + pdrX * cfg.pixelsPerMeter).coerceIn(10.0, 790.0)
        // В декартовой системе север = +Y, а на канвасе Y идет вниз
        val sy = (cfg.originY - pdrY * cfg.pixelsPerMeter).coerceIn(10.0, 590.0)
        return Pair(sx, sy)
    }

    /**
     * Переключение режима уборки (Старт / Стоп).
     */
    fun toggleCleaningSession() {
        val currentState = indoorTracker.trackerState.value
        if (currentState.isTracking) {
            stopCleaning()
        } else {
            startCleaning()
        }
    }

    /**
     * Старт сессии уборки:
     * - Активирует сенсоры IMU
     * - Запускает корутину отправки координат каждые 2 секунды
     */
    fun startCleaning() {
        _sessionDurationSeconds.value = 0L
        indoorTracker.startTracking()
        socketService.connect()
        sendCurrentServerPosition()
        startPeriodicPositionSending()
        startDurationTimer()
        addLog("Уборка начата. Отправка координат на WSS каждые 2 сек.")
    }

    /**
     * Остановка сессии уборки.
     */
    fun stopCleaning() {
        stopSimulation()
        sendCurrentServerPosition()

        periodicSendJob?.cancel()
        periodicSendJob = null
        durationTimerJob?.cancel()
        durationTimerJob = null
        indoorTracker.stopTracking()
        addLog("Уборка завершена. Пройдено шагов: ${indoorTracker.trackerState.value.stepCount}, дистанция: %.1f м".format(indoorTracker.trackerState.value.totalDistance))
    }

    private fun startDurationTimer() {
        durationTimerJob?.cancel()
        durationTimerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000L)
                _sessionDurationSeconds.update { it + 1 }
            }
        }
    }

    /**
     * Смена этажа.
     */
    fun setFloor(floor: Int) {
        if (floor < -3 || floor > 100) return
        indoorTracker.setFloor(floor)
        addLog("Установлен этаж: $floor")
        sendCurrentServerPosition()
    }

    /**
     * Сброс текущей позиции в (0,0) и калибровка курса.
     */
    fun resetPosition(x: Double = 0.0, y: Double = 0.0, headingDeg: Float = 0f) {
        indoorTracker.resetPosition(x, y, headingDeg)
        addLog("Координаты сброшены в (x=%.1f, y=%.1f, курс=%.0f°)".format(x, y, headingDeg))
        sendCurrentServerPosition()
    }

    /**
     * Обновление имени клинера.
     */
    fun updateCleanerName(name: String) {
        val trimmed = name.trim().ifEmpty { "cleaner-1" }
        _serverMapConfig.update { it.copy(cleanerName = trimmed) }
        socketService.updateCleanerName(trimmed)
        addLog("Имя клинера обновлено: $trimmed")
        sendCurrentServerPosition()
    }

    /**
     * Обновление центра и масштаба привязки карты.
     */
    fun updateServerMapConfig(originX: Double, originY: Double, scalePxPerMeter: Double) {
        _serverMapConfig.update {
            it.copy(
                originX = originX.coerceIn(50.0, 750.0),
                originY = originY.coerceIn(50.0, 550.0),
                pixelsPerMeter = scalePxPerMeter.coerceIn(5.0, 60.0)
            )
        }
        addLog("Привязка к карте: центр (%.0f, %.0f), масштаб: %.0f px/м".format(originX, originY, scalePxPerMeter))
        sendCurrentServerPosition()
    }

    /**
     * Калибровка длины шага.
     */
    fun updateStepLength(newLengthMeters: Double) {
        val updated = _pdrConfig.value.copy(stepLength = newLengthMeters)
        _pdrConfig.value = updated
        indoorTracker.updateConfig(updated)
        addLog("Калибровка шага: %.2f м".format(newLengthMeters))
    }

    /**
     * Калибровка чувствительности (порога) акселерометра.
     */
    fun updateStepThreshold(newThreshold: Float) {
        val updated = _pdrConfig.value.copy(stepThreshold = newThreshold)
        _pdrConfig.value = updated
        indoorTracker.updateConfig(updated)
        addLog("Порог акселерометра: %.2f м/с²".format(newThreshold))
    }

    /**
     * Ручное переподключение к сокету при ошибках.
     */
    fun reconnectSocket() {
        addLog("Переподключение к сокет-серверу...")
        socketService.disconnect()
        socketService.connect()
    }

    /**
     * Симуляция движения для наглядного теста отображения на веб-сайте.
     */
    fun toggleSimulation() {
        if (_isSimulating.value) {
            stopSimulation()
        } else {
            startSimulation()
        }
    }

    private fun startSimulation() {
        _isSimulating.value = true
        if (!indoorTracker.trackerState.value.isTracking) {
            startCleaning()
        }
        addLog("Запущена тестовая симуляция шагов...")

        simulationJob?.cancel()
        simulationJob = viewModelScope.launch {
            var angle = 0.0
            var radius = 6.0 // 6 метров
            while (isActive && _isSimulating.value) {
                delay(800L) // шаг каждые 0.8 сек
                angle += 0.25
                val simX = radius * cos(angle)
                val simY = (radius * 0.7) * sin(angle)
                val heading = ((Math.toDegrees(angle + Math.PI / 2) % 360 + 360) % 360).toFloat()
                indoorTracker.resetPosition(simX, simY, heading)
                sendCurrentServerPosition()
            }
        }
    }

    private fun stopSimulation() {
        _isSimulating.value = false
        simulationJob?.cancel()
        simulationJob = null
        addLog("Симуляция шагов остановлена.")
    }

    /**
     * Отправка текущих вычисленных координат на сервер.
     */
    fun sendCurrentServerPosition() {
        val pos = indoorTracker.trackerState.value.currentPosition
        val (sx, sy) = calculateServerCoordinates(pos.x, pos.y)
        val name = _serverMapConfig.value.cleanerName
        socketService.sendPosition(sx, sy, pos.floor, name)
    }

    /**
     * Периодическая отправка координат на сервер каждые 2 секунды.
     */
    private fun startPeriodicPositionSending() {
        periodicSendJob?.cancel()
        periodicSendJob = viewModelScope.launch {
            while (isActive) {
                sendCurrentServerPosition()
                delay(2000L) // Ровно каждые 2 секунды
            }
        }
    }

    private fun addLog(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        _activityLogs.update {
            (listOf("[$time] $message") + it).take(30)
        }
    }

    override fun onCleared() {
        super.onCleared()
        simulationJob?.cancel()
        periodicSendJob?.cancel()
        indoorTracker.stopTracking()
        socketService.disconnect()
    }

    companion object {
        fun provideFactory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return CleanerTrackerViewModel(application) as T
                }
            }
    }
}

/**
 * Состояние экрана приложения.
 */
data class TrackerUiState(
    val trackerState: TrackerState = TrackerState(),
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val userId: String = "",
    val serverX: Double = 400.0,
    val serverY: Double = 300.0,
    val cleanerName: String = "cleaner-1",
    val isSimulating: Boolean = false
)
