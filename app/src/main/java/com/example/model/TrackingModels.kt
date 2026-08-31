package com.example.model

/**
 * Модели данных для системы трекинга внутри помещений (PDR) и Socket.io обмена.
 */

/**
 * Состояние сетевого подключения к Socket.io серверу.
 */
sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    data class Error(val message: String) : ConnectionState()

    val displayName: String
        get() = when (this) {
            is Connected -> "Подключен"
            is Connecting -> "Подключение..."
            is Disconnected -> "Отключен"
            is Error -> "Ошибка"
        }
}

/**
 * Локальная точка позиции клинера.
 * @property x Координата X в метрах относительно точки старта (0,0) (восток/право)
 * @property y Координата Y в метрах относительно точки старта (0,0) (север/вперёд)
 * @property floor Текущий этаж здания
 * @property timestamp Временная метка Unix ms
 */
data class Position(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val floor: Int = 1,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Позиция другого пользователя из снапшота "map_update" от сервера.
 */
data class UserPosition(
    val userId: String,
    val x: Double,
    val y: Double,
    val floor: Int,
    val timestamp: Long
)

/**
 * Общее состояние PDR-трекера для UI.
 * @property isTracking Флаг активной уборки/записи трека
 * @property currentPosition Текущие координаты
 * @property stepCount Количество зафиксированных шагов
 * @property totalDistance Пройденная дистанция в метрах
 * @property headingDegrees Текущий азимут/курс в градусах (0..360)
 * @property currentStepLength Длина одного шага в метрах
 * @property trajectory История пройденных точек для отрисовки траектории
 * @property currentAccelMagnitude Текущий модуль ускорения (для отладки)
 * @property packetsSentCount Количество успешно отправленных пакетов
 * @property lastSentTimestamp Время последней отправки на сервер
 */
data class TrackerState(
    val isTracking: Boolean = false,
    val currentPosition: Position = Position(),
    val stepCount: Int = 0,
    val totalDistance: Double = 0.0,
    val headingDegrees: Float = 0f,
    val currentStepLength: Double = 0.7,
    val trajectory: List<Position> = emptyList(),
    val currentAccelMagnitude: Float = 0f,
    val packetsSentCount: Long = 0L,
    val lastSentTimestamp: Long = 0L
)

/**
 * Параметры калибровки алгоритма PDR (Pedestrian Dead Reckoning).
 * @property stepLength Длина шага по умолчанию в метрах (~0.7 м)
 * @property stepThreshold Порог пика динамического ускорения (м/с²) для детекции шага
 * @property stepDeadTimeMs Минимальное окно нечувствительности между шагами (мс)
 * @property useMagnetometerCorrection Использовать ли магнитометр для коррекции курса гироскопа
 */
data class PdrConfig(
    val stepLength: Double = 0.7,
    val stepThreshold: Float = 1.5f,
    val stepDeadTimeMs: Long = 320L,
    val useMagnetometerCorrection: Boolean = true
)

/**
 * Конфигурация привязки к серверной карте (800x600 пикселей).
 * @property originX Начальная координата X на карте (по умолчанию 400.0 - центр)
 * @property originY Начальная координата Y на карте (по умолчанию 300.0 - центр)
 * @property pixelsPerMeter Масштаб: сколько пикселей карты приходится на 1 метр перемещения
 * @property cleanerName Имя клинера, отображаемое на веб-сайте
 */
data class ServerMapConfig(
    val originX: Double = 400.0,
    val originY: Double = 300.0,
    val pixelsPerMeter: Double = 20.0,
    val cleanerName: String = ""
)

