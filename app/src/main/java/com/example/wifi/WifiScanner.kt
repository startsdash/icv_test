package com.example.wifi

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Точка доступа WiFi для будущего этапа позиционирования (Fingerprinting / RTT / Trilateration).
 */
data class WifiAccessPoint(
    val bssid: String,
    val ssid: String,
    val rssi: Int,
    val frequencyMhz: Int,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Результат сканирования окружения WiFi сетей.
 */
data class WifiScanResult(
    val accessPoints: List<WifiAccessPoint> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val isAvailable: Boolean = false
)

/**
 * Интерфейс WiFi-сканера для интеграции с PDR в будущих версиях.
 */
interface WifiScanner {
    /**
     * Поток результатов сканирования WiFi точек.
     */
    val scanResults: Flow<WifiScanResult>

    /**
     * Запуск периодического или однократного сканирования.
     */
    fun startScan()

    /**
     * Остановка сканирования.
     */
    fun stopScan()

    /**
     * Доступность сканирования на текущем устройстве.
     */
    fun isSupported(): Boolean
}

/**
 * Заглушка WiFi-сканера (Stub), готовая для расширения.
 */
class WifiScannerStub : WifiScanner {
    override val scanResults: Flow<WifiScanResult> = flowOf(
        WifiScanResult(
            accessPoints = listOf(
                WifiAccessPoint("00:11:22:33:44:55", "Office_Mesh_5G", -58, 5180),
                WifiAccessPoint("66:77:88:99:AA:BB", "Cleaners_IoT", -64, 2412)
            ),
            isAvailable = true
        )
    )

    override fun startScan() {
        // Заглушка: в будущем здесь будет WifiManager.startScan() + BroadcastReceiver
    }

    override fun stopScan() {
        // Остановка слушателя WiFi
    }

    override fun isSupported(): Boolean = false
}
