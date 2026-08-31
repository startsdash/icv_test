package com.example.manager

import android.content.Context
import android.util.Log
import com.example.model.ConnectionState
import com.example.model.PdrConfig
import com.example.model.Position
import com.example.model.ServerMapConfig
import com.example.model.TrackerState
import com.example.model.UserPosition
import com.example.sensor.IndoorTracker
import com.example.service.TrackingForegroundService
import com.example.socket.SocketService
import com.example.viewmodel.TrackerUiState
import com.example.wifi.WifiScanner
import com.example.wifi.WifiScannerStub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Singleton-менеджер трекинга и синхронизации.
 * Управляет жизненным циклом PDR-датчиков, Socket.io соединением и фоновой отправкой,
 * независимо от активности UI (работает в фоне и при выключенном экране).
 */
class TrackingManager private constructor(private val appContext: Context) {

    private val managerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val socketService = SocketService(appContext)
    val indoorTracker = IndoorTracker(appContext)
    val wifiScanner: WifiScanner = WifiScannerStub()

    val userId: String = socketService.userId
    val connectionState: StateFlow<ConnectionState> = socketService.connectionState
    val mapUpdates: StateFlow<List<UserPosition>> = socketService.mapUpdateSnapshot

    private var periodicSendJob: Job? = null
    private var durationTimerJob: Job? = null
    private var simulationJob: Job? = null

    // Таймер текущей сессии уборки в секундах
    private val _sessionDurationSeconds = MutableStateFlow(0L)
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

    // Логи событий для UI
    private val _activityLogs = MutableStateFlow<List<String>>(
        listOf("[${getTimestamp()}] Инициализация TrackingManager")
    )
    val activityLogs: StateFlow<List<String>> = _activityLogs.asStateFlow()

    // Единое состояние для UI
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
            cleanerName = socketService.cleanerName.value,
            serverX = sx,
            serverY = sy,
            isSimulating = simulating
        )
    }.stateIn(
        scope = managerScope,
        started = SharingStarted.Eagerly,
        initialValue = TrackerUiState(
            trackerState = TrackerState(
                currentPosition = Position(0.0, 0.0, 1)
            ),
            connectionState = ConnectionState.Disconnected,
            userId = userId,
            cleanerName = socketService.cleanerName.value,
            serverX = 400.0,
            serverY = 300.0,
            isSimulating = false
        )
    )

    init {
        // Подключаемся к WSS серверу при запуске
        socketService.connect()
        addLog("Подключение к wss://icv.dotozen.ru...")
    }

    /**
     * Преобразование метров PDR в пиксели холста сервера 800x600
     */
    private fun calculateServerCoords(pdrX: Double, pdrY: Double, config: ServerMapConfig): Pair<Double, Double> {
        val serverX = (config.originX + (pdrX * config.pixelsPerMeter)).coerceIn(10.0, 790.0)
        val serverY = (config.originY - (pdrY * config.pixelsPerMeter)).coerceIn(10.0, 590.0)
        return Pair(serverX, serverY)
    }

    /**
     * Запуск/остановка сессии уборки
     */
    fun toggleCleaningSession(context: Context) {
        val currentlyTracking = indoorTracker.trackerState.value.isTracking
        if (currentlyTracking) {
            stopCleaningSession(context)
        } else {
            startCleaningSession(context)
        }
    }

    fun startCleaningSession(context: Context) {
        stopSimulation()
        indoorTracker.startTracking()
        TrackingForegroundService.start(context)

        startPeriodicSending()
        startDurationTimer()
        addLog("Сессия уборки начата. Фоновый сервис запущен.")
    }

    fun stopCleaningSession(context: Context) {
        sendCurrentServerPosition()
        indoorTracker.stopTracking()
        stopPeriodicSending()
        stopDurationTimer()
        TrackingForegroundService.stop(context)
        addLog("Сессия уборки завершена. Пройдено шагов: ${indoorTracker.trackerState.value.stepCount}")
    }

    /**
     * Симуляция движения клинера
     */
    fun toggleSimulation(context: Context) {
        if (_isSimulating.value) {
            stopSimulation()
            if (!indoorTracker.trackerState.value.isTracking) {
                TrackingForegroundService.stop(context)
            }
            addLog("Симуляция остановлена")
        } else {
            startSimulation(context)
        }
    }

    fun startSimulation(context: Context) {
        if (_isSimulating.value) return
        _isSimulating.value = true
        TrackingForegroundService.start(context)
        addLog("Запущена непрерывная симуляция шагов")

        simulationJob?.cancel()
        simulationJob = managerScope.launch {
            var angle = 0.0
            var simStep = 0
            val radiusMeters = 8.0

            while (isActive && _isSimulating.value) {
                angle += 0.15
                simStep++
                val pdrX = radiusMeters * cos(angle)
                val pdrY = (radiusMeters * 0.6) * sin(angle * 1.5)
                val headingDeg = ((Math.toDegrees(angle) + 90) % 360).toFloat()

                val newPos = Position(
                    x = pdrX,
                    y = pdrY,
                    floor = 1,
                    timestamp = System.currentTimeMillis()
                )

                indoorTracker.simulateStep(newPos, headingDeg, simStep)

                val (sx, sy) = calculateServerCoords(pdrX, pdrY, _serverMapConfig.value)
                socketService.sendPosition(
                    x = sx,
                    y = sy,
                    floor = newPos.floor,
                    customName = _serverMapConfig.value.cleanerName
                )

                delay(800L) // Шаг каждые 800 мс
            }
        }
    }

    fun stopSimulation() {
        _isSimulating.value = false
        simulationJob?.cancel()
        simulationJob = null
    }

    private fun startPeriodicSending() {
        periodicSendJob?.cancel()
        periodicSendJob = managerScope.launch {
            while (isActive) {
                sendCurrentServerPosition()
                delay(2000L) // Отправка пакета каждые 2 секунды
            }
        }
    }

    private fun stopPeriodicSending() {
        periodicSendJob?.cancel()
        periodicSendJob = null
    }

    fun sendCurrentServerPosition() {
        val currentPdr = indoorTracker.trackerState.value.currentPosition
        val (sx, sy) = calculateServerCoords(currentPdr.x, currentPdr.y, _serverMapConfig.value)

        socketService.sendPosition(
            x = sx,
            y = sy,
            floor = currentPdr.floor,
            customName = _serverMapConfig.value.cleanerName
        )
    }

    private fun startDurationTimer() {
        durationTimerJob?.cancel()
        _sessionDurationSeconds.value = 0L
        durationTimerJob = managerScope.launch {
            while (isActive) {
                delay(1000L)
                _sessionDurationSeconds.update { it + 1 }
            }
        }
    }

    private fun stopDurationTimer() {
        durationTimerJob?.cancel()
        durationTimerJob = null
    }

    fun reconnectSocket() {
        socketService.disconnect()
        socketService.connect()
        addLog("Переподключение к сокету...")
    }

    fun resetPosition(x: Double = 0.0, y: Double = 0.0, headingDeg: Float = 0f) {
        indoorTracker.resetPosition(x, y, headingDeg)
        addLog("Координаты сброшены в ($x, $y)")
    }

    fun setFloor(floor: Int) {
        indoorTracker.setFloor(floor)
        addLog("Установлен этаж: $floor")
        sendCurrentServerPosition()
    }

    fun updateCleanerName(name: String) {
        socketService.updateCleanerName(name)
        addLog("Имя клинера: ${socketService.cleanerName.value}")
    }

    fun updateServerMapConfig(originX: Double, originY: Double, pixelsPerMeter: Double) {
        _serverMapConfig.update {
            it.copy(
                originX = originX,
                originY = originY,
                pixelsPerMeter = pixelsPerMeter
            )
        }
        addLog("Обновлена карта: Центр=($originX, $originY), Масштаб=$pixelsPerMeter px/м")
        sendCurrentServerPosition()
    }

    fun updateStepLength(lengthMeters: Double) {
        val updated = _pdrConfig.value.copy(stepLength = lengthMeters)
        _pdrConfig.value = updated
        indoorTracker.updateConfig(updated)
        addLog("Длина шага изменена: %.2f м".format(lengthMeters))
    }

    fun updateStepThreshold(threshold: Float) {
        val updated = _pdrConfig.value.copy(stepThreshold = threshold)
        _pdrConfig.value = updated
        indoorTracker.updateConfig(updated)
        addLog("Порог детекции шага: %.2f м/с²".format(threshold))
    }

    fun addLog(message: String) {
        val entry = "[${getTimestamp()}] $message"
        Log.d(TAG, entry)
        _activityLogs.update { current ->
            (listOf(entry) + current).take(100)
        }
    }

    private fun getTimestamp(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    companion object {
        private const val TAG = "TrackingManager"

        @Volatile
        private var INSTANCE: TrackingManager? = null

        fun getInstance(context: Context): TrackingManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TrackingManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
