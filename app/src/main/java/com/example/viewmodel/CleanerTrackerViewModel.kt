package com.example.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.manager.TrackingManager
import com.example.model.CleaningMode
import com.example.model.ConnectionState
import com.example.model.FacilityZone
import com.example.model.ObjectCategory
import com.example.model.PdrConfig
import com.example.model.ServerMapConfig
import com.example.model.TrackerState
import com.example.model.UserPosition
import com.example.wifi.WifiScanner
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel для управления UI приложения клинера:
 * Делегирует управление трекингом, картографией покрытия и сокетом в синглтон TrackingManager.
 */
class CleanerTrackerViewModel(application: Application) : AndroidViewModel(application) {

    private val trackingManager = TrackingManager.getInstance(application.applicationContext)

    val userId: String = trackingManager.userId
    val connectionState: StateFlow<ConnectionState> = trackingManager.connectionState
    val mapUpdates: StateFlow<List<UserPosition>> = trackingManager.mapUpdates
    val wifiScanner: WifiScanner = trackingManager.wifiScanner

    val sessionDurationSeconds: StateFlow<Long> = trackingManager.sessionDurationSeconds
    val isSimulating: StateFlow<Boolean> = trackingManager.isSimulating
    val pdrConfig: StateFlow<PdrConfig> = trackingManager.pdrConfig
    val serverMapConfig: StateFlow<ServerMapConfig> = trackingManager.serverMapConfig
    val activityLogs: StateFlow<List<String>> = trackingManager.activityLogs

    val uiState: StateFlow<TrackerUiState> = trackingManager.uiState

    fun toggleCleaningSession(context: Context = getApplication()) {
        trackingManager.toggleCleaningSession(context)
    }

    fun startCleaning(context: Context = getApplication()) {
        trackingManager.startCleaningSession(context)
    }

    fun stopCleaning(context: Context = getApplication()) {
        trackingManager.stopCleaningSession(context)
    }

    fun toggleSimulation(context: Context = getApplication()) {
        trackingManager.toggleSimulation(context)
    }

    fun setFloor(floor: Int) {
        trackingManager.setFloor(floor)
    }

    fun resetPosition(x: Double = 0.0, y: Double = 0.0, headingDeg: Float = 0f) {
        trackingManager.resetPosition(x, y, headingDeg)
    }

    fun updateCleanerName(name: String) {
        trackingManager.updateCleanerName(name)
    }

    fun updateServerMapConfig(originX: Double, originY: Double, scalePxPerMeter: Double) {
        trackingManager.updateServerMapConfig(originX, originY, scalePxPerMeter)
    }

    fun updateStepLength(newLengthMeters: Double) {
        trackingManager.updateStepLength(newLengthMeters)
    }

    fun updateStepThreshold(newThreshold: Float) {
        trackingManager.updateStepThreshold(newThreshold)
    }

    fun updateCleaningMode(mode: CleaningMode) {
        trackingManager.updateCleaningMode(mode)
    }

    fun updateCleaningWidth(widthMeters: Double) {
        trackingManager.updateCleaningWidth(widthMeters)
    }

    fun createQuickZone(name: String, category: ObjectCategory, floor: Int, widthMeters: Double, heightMeters: Double) {
        trackingManager.createQuickZone(name, category, floor, widthMeters, heightMeters)
    }

    fun startPerimeterMapping(name: String, category: ObjectCategory, floor: Int) {
        trackingManager.startPerimeterMapping(name, category, floor)
    }

    fun addPerimeterPoint() {
        trackingManager.addPerimeterPoint()
    }

    fun closePerimeter() {
        trackingManager.closePerimeter()
    }

    fun cancelPerimeterMapping() {
        trackingManager.cancelPerimeterMapping()
    }

    fun selectActiveZone(zone: FacilityZone?) {
        trackingManager.selectActiveZone(zone)
    }

    fun deleteZone(zoneId: String) {
        trackingManager.deleteZone(zoneId)
    }

    fun reconnectSocket() {
        trackingManager.reconnectSocket()
    }

    fun sendCurrentServerPosition() {
        trackingManager.sendCurrentServerPosition()
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
