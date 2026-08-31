package com.example.model

/**
 * Модели данных для системы трекинга внутри помещений (PDR),
 * покрытия зон уборки (Coverage Mapping), разметки периметров объектов (подъезды, дворы)
 * и Socket.io обмена с сервером.
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
 * Режимы работы клинера
 */
enum class CleaningMode(val title: String, val shortName: String, val iconEmoji: String, val colorHex: Long) {
    WET_CLEANING("Влажная уборка", "Влажная", "💧", 0xFF0284C7),
    DRY_VACUUM("Сухая уборка / Пылесос", "Сухая", "🧹", 0xFFF59E0B),
    IDLE_TRANSIT("Переход / Простой", "Простой", "🚶", 0xFF6B7280)
}

/**
 * Категория объекта уборки
 */
enum class ObjectCategory(val title: String, val emoji: String) {
    ENTRANCE_BUILDING("Подъезд", "🏢"),
    OUTDOOR_YARD("Придомовая территория", "🌳")
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
 * Пройденный отрезок уборки с шириной захвата (полоса покрытия).
 */
data class CoverageSegment(
    val start: Position,
    val end: Position,
    val cleaningWidthMeters: Double = 0.5,
    val mode: CleaningMode = CleaningMode.WET_CLEANING,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Размеченный объект/зона (этаж подъезда, холл, придомовая площадка, дорожка и т.д.).
 */
data class FacilityZone(
    val id: String,
    val name: String,
    val category: ObjectCategory,
    val floor: Int = 1,
    val polygonPoints: List<Position> = emptyList(),
    val areaSquareMeters: Double = 0.0,
    val targetCoveragePercent: Double = 100.0,
    val colorHex: Long = 0xFF3B82F6
)

/**
 * Состояние текущей разметки периметра объекта.
 */
data class PerimeterMappingState(
    val isMapping: Boolean = false,
    val zoneName: String = "",
    val category: ObjectCategory = ObjectCategory.ENTRANCE_BUILDING,
    val floor: Int = 1,
    val perimeterPoints: List<Position> = emptyList(),
    val isClosed: Boolean = false,
    val computedPerimeterMeters: Double = 0.0,
    val computedAreaMeters: Double = 0.0
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
 * Общее состояние PDR-трекера и зоны уборки для UI.
 */
data class TrackerState(
    val isTracking: Boolean = false,
    val currentPosition: Position = Position(),
    val stepCount: Int = 0,
    val totalDistance: Double = 0.0,
    val headingDegrees: Float = 0f,
    val currentStepLength: Double = 0.7,
    val trajectory: List<Position> = emptyList(),
    val coverageSegments: List<CoverageSegment> = emptyList(),
    val cleaningWidthMeters: Double = 0.5, // 50 см ширина швабры / насадки
    val cleaningMode: CleaningMode = CleaningMode.WET_CLEANING,
    val coveredAreaM2: Double = 0.0,
    val currentZone: FacilityZone? = null,
    val savedZones: List<FacilityZone> = emptyList(),
    val perimeterState: PerimeterMappingState = PerimeterMappingState(),
    val currentAccelMagnitude: Float = 0f,
    val packetsSentCount: Long = 0L,
    val lastSentTimestamp: Long = 0L
)

/**
 * Параметры калибровки алгоритма PDR (Pedestrian Dead Reckoning).
 */
data class PdrConfig(
    val stepLength: Double = 0.7,
    val stepThreshold: Float = 1.5f,
    val stepDeadTimeMs: Long = 320L,
    val useMagnetometerCorrection: Boolean = true
)

/**
 * Конфигурация привязки к серверной карте (800x600 пикселей).
 */
data class ServerMapConfig(
    val originX: Double = 400.0,
    val originY: Double = 300.0,
    val pixelsPerMeter: Double = 20.0,
    val cleanerName: String = ""
)
