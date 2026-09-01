package com.example.manager

import android.content.Context
import android.util.Log
import com.example.model.CleaningMode
import com.example.model.ConnectionState
import com.example.model.FacilityZone
import com.example.model.ObjectCategory
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
 * Singleton-менеджер трекинга, расчета покрытия уборки и разметки контуров объектов.
 * Управляет жизненным циклом PDR-датчиков, Socket.io соединением и фоновой отправкой.
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
    fun calculateServerCoords(pdrX: Double, pdrY: Double, config: ServerMapConfig = _serverMapConfig.value): Pair<Double, Double> {
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
        val mode = indoorTracker.trackerState.value.cleaningMode
        val zone = indoorTracker.trackerState.value.currentZone?.name ?: "Без объекта"
        addLog("Сессия уборки начата [${mode.title}] на объекте: $zone")
    }

    fun stopCleaningSession(context: Context) {
        sendCurrentServerPosition()
        socketService.sendStopTracking()
        indoorTracker.stopTracking()
        stopPeriodicSending()
        stopDurationTimer()
        TrackingForegroundService.stop(context)
        val tState = indoorTracker.trackerState.value
        addLog("Сессия завершена. Шагов: ${tState.stepCount}, Покрыто: %.1f м²".format(tState.coveredAreaM2))
    }

    /**
     * Смена режима уборки (Влажная, Сухая, Санобработка, Простой)
     */
    fun updateCleaningMode(mode: CleaningMode) {
        indoorTracker.updateCleaningMode(mode)
        addLog("Режим уборки изменен: ${mode.iconEmoji} ${mode.title}")
        sendCurrentServerPosition()
    }

    /**
     * Смена ширины захвата швабры/пылесоса
     */
    fun updateCleaningWidth(widthMeters: Double) {
        indoorTracker.updateCleaningWidth(widthMeters)
        addLog("Ширина захвата инвентаря: %.0f см".format(widthMeters * 100))
        sendCurrentServerPosition()
    }

    // =========================================================================
    //  РАЗМЕТКА ПЕРИМЕТРА ОБЪЕКТА (ПОДЪЕЗД / ДВОР)
    // =========================================================================

    fun startPerimeterMapping(name: String, category: ObjectCategory, floor: Int) {
        indoorTracker.startTracking()
        indoorTracker.startPerimeterMapping(name, category, floor)
        addLog("Начата разметка периметра: $name (${category.title}, эт. $floor)")
        sendCurrentServerPosition()
    }

    fun addPerimeterPoint() {
        indoorTracker.addPerimeterPoint()
        val pState = indoorTracker.trackerState.value.perimeterState
        addLog("Добавлена точка #${pState.perimeterPoints.size} в контур (Периметр: %.1f м)".format(pState.computedPerimeterMeters))
        sendCurrentServerPosition()
    }

    fun closePerimeter() {
        val zone = indoorTracker.closePerimeter()
        if (zone != null) {
            addLog("Контур замкнут! Создан объект: ${zone.name} (Площадь: %.1f м²)".format(zone.areaSquareMeters))
            // Отправляем полигон на сервер через сокет с пикселями и метрами
            val serverPoints = zone.polygonPoints.map { pt ->
                val (px, py) = calculateServerCoords(pt.x, pt.y, _serverMapConfig.value)
                Triple(px, py, Pair(pt.x, pt.y))
            }
            socketService.sendZonePerimeter(
                zoneId = zone.id,
                zoneName = zone.name,
                category = zone.category.name,
                floor = zone.floor,
                points = serverPoints,
                areaM2 = zone.areaSquareMeters
            )
            sendCurrentServerPosition()
        } else {
            addLog("Для замыкания контура требуется минимум 3 точки!")
        }
    }

    fun createQuickZone(name: String, category: ObjectCategory, floor: Int, widthMeters: Double, heightMeters: Double) {
        val zone = indoorTracker.createQuickZone(name, category, floor, widthMeters, heightMeters)
        addLog("Создан объект: ${zone.name} (Площадь: %.1f м²)".format(zone.areaSquareMeters))
        val serverPoints = zone.polygonPoints.map { pt ->
            val (px, py) = calculateServerCoords(pt.x, pt.y, _serverMapConfig.value)
            Triple(px, py, Pair(pt.x, pt.y))
        }
        socketService.sendZonePerimeter(
            zoneId = zone.id,
            zoneName = zone.name,
            category = zone.category.name,
            floor = zone.floor,
            points = serverPoints,
            areaM2 = zone.areaSquareMeters
        )
        sendCurrentServerPosition()
    }

    fun cancelPerimeterMapping() {
        indoorTracker.cancelPerimeterMapping()
        addLog("Разметка периметра отменена")
        sendCurrentServerPosition()
    }

    fun selectActiveZone(zone: FacilityZone?) {
        indoorTracker.selectActiveZone(zone)
        zone?.let {
            indoorTracker.setFloor(it.floor)
            addLog("Выбран активный объект: ${it.name}")
        }
        sendCurrentServerPosition()
    }

    fun deleteZone(zoneId: String) {
        indoorTracker.deleteZone(zoneId)
        addLog("Объект удален из списка")
    }

    // =========================================================================
    //  СИМУЛЯЦИЯ И ОТПРАВКА ДАННЫХ
    // =========================================================================

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
        
        val zone = indoorTracker.trackerState.value.currentZone
        val zoneName = zone?.name ?: "Тестовая комната (6×4 м)"
        addLog("Запущена уборка внутри объекта: $zoneName")

        simulationJob?.cancel()
        simulationJob = managerScope.launch {
            var simStep = 0
            
            // Определяем границы объекта для уборки строго внутри помещения
            val zonePoints = zone?.polygonPoints ?: listOf(
                Position(-3.0, -2.0, indoorTracker.trackerState.value.currentPosition.floor),
                Position(3.0, -2.0, indoorTracker.trackerState.value.currentPosition.floor),
                Position(3.0, 2.0, indoorTracker.trackerState.value.currentPosition.floor),
                Position(-3.0, 2.0, indoorTracker.trackerState.value.currentPosition.floor)
            )

            val minX = zonePoints.minOf { it.x } + 0.3
            val maxX = zonePoints.maxOf { it.x } - 0.3
            val minY = zonePoints.minOf { it.y } + 0.3
            val maxY = zonePoints.maxOf { it.y } - 0.3

            var currentSimX = minX
            var currentSimY = minY
            var movingRight = true
            val widthStep = indoorTracker.trackerState.value.cleaningWidthMeters.coerceIn(0.35, 0.8)
            val stepDist = 0.35 // 35 см за 1 тик

            while (isActive && _isSimulating.value) {
                simStep++

                // Траектория "змейка" (boustrophedon) строго внутри стен помещения
                if (movingRight) {
                    currentSimX += stepDist
                    if (currentSimX >= maxX) {
                        currentSimX = maxX
                        currentSimY += widthStep
                        movingRight = false
                    }
                } else {
                    currentSimX -= stepDist
                    if (currentSimX <= minX) {
                        currentSimX = minX
                        currentSimY += widthStep
                        movingRight = true
                    }
                }

                // Если достигли верхней стены, разворачиваемся вниз
                if (currentSimY > maxY) {
                    currentSimY = minY
                }

                val headingDeg = if (movingRight) 90f else 270f
                val newPos = Position(
                    x = currentSimX,
                    y = currentSimY,
                    floor = indoorTracker.trackerState.value.currentPosition.floor,
                    timestamp = System.currentTimeMillis()
                )

                indoorTracker.simulateStep(newPos, headingDeg, simStep)
                sendCurrentServerPosition()
                delay(650L) // Шаг каждые 650 мс
            }
        }
    }

    /**
     * Ручной шаг клинера вперед (для интерактивного мастера разметки или тестирования)
     */
    fun manualStepForward(meters: Double = 0.6) {
        indoorTracker.manualStepForward(meters)
        sendCurrentServerPosition()
        addLog("Шаг вперед: +%.1f м".format(meters))
    }

    /**
     * Ручной поворот клинера на заданный угол (+90° по часовой стрелке)
     */
    fun manualTurn(degrees: Float = 90f) {
        indoorTracker.manualTurn(degrees)
        sendCurrentServerPosition()
        addLog("Поворот курса: %+.0f°".format(degrees))
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
        val tState = indoorTracker.trackerState.value
        val currentPdr = tState.currentPosition
        val (sx, sy) = calculateServerCoords(currentPdr.x, currentPdr.y, _serverMapConfig.value)

        socketService.sendPosition(
            xMeters = currentPdr.x,
            yMeters = currentPdr.y,
            xPx = sx,
            yPx = sy,
            floor = currentPdr.floor,
            customName = _serverMapConfig.value.cleanerName,
            cleaningWidthMeters = tState.cleaningWidthMeters,
            cleaningModeName = tState.cleaningMode.name,
            cleaningModeTitle = tState.cleaningMode.title,
            coveredAreaM2 = tState.coveredAreaM2,
            headingDegrees = tState.headingDegrees,
            stepCount = tState.stepCount,
            totalDistanceMeters = tState.totalDistance,
            zoneName = tState.currentZone?.name ?: ""
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
        sendCurrentServerPosition()
    }

    fun setFloor(floor: Int) {
        indoorTracker.setFloor(floor)
        addLog("Установлен этаж: $floor")
        sendCurrentServerPosition()
    }

    fun updateCleanerName(name: String) {
        socketService.updateCleanerName(name)
        _serverMapConfig.update { it.copy(cleanerName = name) }
        addLog("Имя клинера: ${socketService.cleanerName.value}")
        sendCurrentServerPosition()
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
