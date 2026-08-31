package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.model.ConnectionState
import com.example.model.PdrConfig
import com.example.model.Position
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

/**
 * ViewModel для управления бизнес-логикой приложения клинера:
 * - Управление PDR-трекером (IndoorTracker)
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

    // Таймер текущей сессии уборки в секундах
    private val _sessionDurationSeconds = MutableStateFlow<Long>(0L)
    val sessionDurationSeconds: StateFlow<Long> = _sessionDurationSeconds.asStateFlow()

    // Настройки PDR
    private val _pdrConfig = MutableStateFlow(PdrConfig())
    val pdrConfig: StateFlow<PdrConfig> = _pdrConfig.asStateFlow()

    // Логи для панели отладки
    private val _activityLogs = MutableStateFlow<List<String>>(listOf("Система инициализирована. Готов к уборке."))
    val activityLogs: StateFlow<List<String>> = _activityLogs.asStateFlow()

    // Объединённый поток состояния UI
    val uiState: StateFlow<TrackerUiState> = combine(
        indoorTracker.trackerState,
        connectionState,
        socketService.packetsSentCount,
        socketService.lastSentTimestamp
    ) { trackerState, connState, packetsCount, lastSent ->
        TrackerUiState(
            trackerState = trackerState.copy(
                packetsSentCount = packetsCount,
                lastSentTimestamp = lastSent
            ),
            connectionState = connState,
            userId = userId
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
        startPeriodicPositionSending()
        startDurationTimer()
        addLog("Уборка начата. Сенсоры активны. Отправка каждые 2 сек.")
    }

    /**
     * Остановка сессии уборки.
     */
    fun stopCleaning() {
        // Отправляем финальную точку
        val pos = indoorTracker.trackerState.value.currentPosition
        socketService.sendPosition(pos.x, pos.y, pos.floor)

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
        // Немедленная отправка обновлённого этажа
        val pos = indoorTracker.trackerState.value.currentPosition
        socketService.sendPosition(pos.x, pos.y, floor)
    }

    /**
     * Сброс текущей позиции в (0,0) и калибровка курса.
     */
    fun resetPosition(x: Double = 0.0, y: Double = 0.0, headingDeg: Float = 0f) {
        indoorTracker.resetPosition(x, y, headingDeg)
        addLog("Координаты сброшены в (x=%.1f, y=%.1f, курс=%.0f°)".format(x, y, headingDeg))
        val currentFloor = indoorTracker.trackerState.value.currentPosition.floor
        socketService.sendPosition(x, y, currentFloor)
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
     * Периодическая отправка координат на сервер каждые 2 секунды.
     */
    private fun startPeriodicPositionSending() {
        periodicSendJob?.cancel()
        periodicSendJob = viewModelScope.launch {
            while (isActive) {
                val currentPos = indoorTracker.trackerState.value.currentPosition
                socketService.sendPosition(currentPos.x, currentPos.y, currentPos.floor)
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
    val userId: String = ""
)
