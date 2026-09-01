package com.example.socket

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.model.ConnectionState
import com.example.model.UserPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Сетевой сервис обмена данными по протоколу raw WebSocket (v2.0).
 * Соответствует технической спецификации сервера https://icv.dotozen.ru.
 *
 * Адрес: wss://icv.dotozen.ru/
 * Формат сообщений: JSON c полем "type"
 */
class SocketService(private val context: Context) {

    companion object {
        private const val TAG = "SocketService"
        // WSS URL (сервер Node.js с Nginx reverse proxy и TLS Let's Encrypt)
        const val SERVER_WSS_URL = "wss://icv.dotozen.ru/"
        private const val PREFS_NAME = "cleaner_tracker_prefs"
        private const val KEY_USER_ID = "unique_user_id"
        private const val KEY_CLEANER_NAME = "cleaner_custom_name"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Постоянный уникальный идентификатор клинера (UUID), генерируется один раз и сохраняется на устройстве.
     */
    val userId: String = getOrCreateUserId()

    private val _cleanerName = MutableStateFlow(getSavedCleanerName())
    val cleanerName: StateFlow<String> = _cleanerName.asStateFlow()

    fun updateCleanerName(name: String) {
        val trimmed = name.trim()
        _cleanerName.value = trimmed
        prefs.edit().putString(KEY_CLEANER_NAME, trimmed).apply()
        // Если уже подключены, отправляем повторную регистрацию с новым именем
        sendRegister()
    }

    private fun getSavedCleanerName(): String {
        return prefs.getString(KEY_CLEANER_NAME, "") ?: ""
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS) // WS keep-alive пинги
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Бессрочный таймаут для WS потока
        .build()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var isIntentionallyClosed = false

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _mapUpdateSnapshot = MutableStateFlow<List<UserPosition>>(emptyList())
    val mapUpdateSnapshot: StateFlow<List<UserPosition>> = _mapUpdateSnapshot.asStateFlow()

    private val _lastSentTimestamp = MutableStateFlow<Long>(0L)
    val lastSentTimestamp: StateFlow<Long> = _lastSentTimestamp.asStateFlow()

    private val _packetsSentCount = MutableStateFlow<Long>(0L)
    val packetsSentCount: StateFlow<Long> = _packetsSentCount.asStateFlow()

    /**
     * Инициализация и подключение к WSS серверу (Raw WebSocket).
     */
    fun connect() {
        if (_connectionState.value == ConnectionState.Connected || _connectionState.value == ConnectionState.Connecting) {
            Log.d(TAG, "WebSocket already connected or connecting")
            return
        }

        isIntentionallyClosed = false
        reconnectJob?.cancel()
        _connectionState.value = ConnectionState.Connecting

        try {
            val request = Request.Builder()
                .url(SERVER_WSS_URL)
                .build()

            webSocket = okHttpClient.newWebSocket(request, createWebSocketListener())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WebSocket: ${e.message}", e)
            _connectionState.value = ConnectionState.Error(e.localizedMessage ?: "Init error")
            scheduleReconnect()
        }
    }

    /**
     * Создание слушателя событий OkHttp WebSocket.
     */
    private fun createWebSocketListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected successfully: $SERVER_WSS_URL")
                _connectionState.value = ConnectionState.Connected
                // Согласно п. 4.1 спецификации v2.0: сразу отправляем регистрацию клинера
                sendRegister()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closing: code=$code, reason=$reason")
                ws.close(1000, null)
                _connectionState.value = ConnectionState.Disconnected
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: code=$code, reason=$reason")
                _connectionState.value = ConnectionState.Disconnected
                if (!isIntentionallyClosed) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                _connectionState.value = ConnectionState.Error(t.localizedMessage ?: "Network error")
                if (!isIntentionallyClosed) {
                    scheduleReconnect()
                }
            }
        }
    }

    /**
     * Отключение от сервера.
     */
    fun disconnect() {
        isIntentionallyClosed = true
        reconnectJob?.cancel()
        try {
            webSocket?.close(1000, "Client disconnect")
            webSocket = null
            _connectionState.value = ConnectionState.Disconnected
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect: ${e.message}", e)
        }
    }

    /**
     * Автоматическое переподключение с экспоненциальной задержкой при обрыве сети (п. 8.5 спецификации).
     */
    private fun scheduleReconnect() {
        if (isIntentionallyClosed) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(2000L)
            if (isActive && !isIntentionallyClosed && _connectionState.value != ConnectionState.Connected) {
                Log.d(TAG, "Attempting reconnect to WebSocket...")
                connect()
            }
        }
    }

    /**
     * 4.1 Регистрация клинера (при старте смены / первом подключении)
     */
    fun sendRegister() {
        val ws = webSocket ?: return
        val displayName = _cleanerName.value.ifEmpty { "Клинер ${userId.takeLast(6)}" }
        val payload = JSONObject().apply {
            put("type", "register")
            put("userId", userId)
            put("role", "cleaner")
            put("name", displayName)
        }
        sendJson(payload)
        Log.i(TAG, "Sent register: userId=$userId, name=$displayName")
    }

    /**
     * 4.2 Обновление позиции и покрытия (каждый шаг или раз в 1-2 сек)
     */
    fun sendPosition(
        xMeters: Double,
        yMeters: Double,
        xPx: Double,
        yPx: Double,
        floor: Int,
        customName: String? = null,
        cleaningWidthMeters: Double = 0.5,
        cleaningModeName: String = "WET_CLEANING",
        cleaningModeTitle: String = "Влажная уборка",
        coveredAreaM2: Double = 0.0,
        headingDegrees: Float = 0f,
        stepCount: Int = 0,
        totalDistanceMeters: Double = 0.0,
        zoneName: String = ""
    ) {
        val now = System.currentTimeMillis()
        val displayName = customName?.ifEmpty { null } ?: _cleanerName.value.ifEmpty { userId }
        val brushRadiusPx = (cleaningWidthMeters * 10.0) / 2.0 // scale = 10 px/м

        val payload = JSONObject().apply {
            put("type", "position_update")
            put("userId", userId)
            put("xMeters", xMeters)
            put("yMeters", yMeters)
            put("x", xPx)
            put("y", yPx)
            put("floor", floor)
            put("heading", headingDegrees.toDouble())
            put("stepCount", stepCount)
            put("totalDistance", totalDistanceMeters)
            put("timestamp", now)
            put("cleaningWidth", cleaningWidthMeters)
            put("brushRadius", brushRadiusPx)
            put("mode", cleaningModeName)
            put("modeName", cleaningModeTitle)
            put("coveredAreaM2", coveredAreaM2)
            if (zoneName.isNotEmpty()) {
                put("zoneName", zoneName)
            }
        }

        if (sendJson(payload)) {
            _lastSentTimestamp.value = now
            _packetsSentCount.value += 1
            Log.d(TAG, "Sent position_update: xM=%.2f, yM=%.2f, mode=$cleaningModeName".format(xMeters, yMeters))
        }
    }

    /**
     * 4.4 Разметка зоны / периметра (Perimeter SLAM)
     */
    fun sendZonePerimeter(
        zoneId: String,
        zoneName: String,
        category: String,
        floor: Int,
        points: List<Triple<Double, Double, Pair<Double, Double>>>, // (xPx, yPx, (xMeters, yMeters))
        areaM2: Double
    ) {
        val pointsArray = JSONArray()
        points.forEach { (xPx, yPx, meters) ->
            pointsArray.put(JSONObject().apply {
                put("x", xPx)
                put("y", yPx)
                put("xMeters", meters.first)
                put("yMeters", meters.second)
            })
        }

        val payload = JSONObject().apply {
            put("type", "perimeter_update")
            put("zoneId", zoneId)
            put("zoneName", zoneName)
            put("category", category)
            put("floor", floor)
            put("points", pointsArray)
            put("areaM2", areaM2)
            put("timestamp", System.currentTimeMillis())
        }

        sendJson(payload)
        Log.i(TAG, "Sent perimeter_update: $zoneName with ${points.size} points, area=%.2f m²".format(areaM2))
    }

    /**
     * 4.5 Завершение смены (Stop tracking)
     */
    fun sendStopTracking() {
        val payload = JSONObject().apply {
            put("type", "stop_tracking")
            put("userId", userId)
        }
        sendJson(payload)
        Log.i(TAG, "Sent stop_tracking for userId=$userId")
    }

    /**
     * Отправка JSON-сообщения по WebSocket.
     */
    private fun sendJson(json: JSONObject): Boolean {
        val ws = webSocket
        if (ws == null || _connectionState.value != ConnectionState.Connected) {
            return false
        }
        return try {
            ws.send(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending WS message: ${e.message}", e)
            false
        }
    }

    /**
     * Обработка входящих сообщений от сервера (п. 5 спецификации).
     */
    private fun handleIncomingMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")

            when (type) {
                "snapshot" -> {
                    // 5.1 Snapshot состояния
                    val cleaners = json.optJSONArray("cleaners")
                    if (cleaners != null) {
                        parseCleanersArray(cleaners)
                    }
                }
                "cleaners" -> {
                    // 5.4 Периодическая сводка клинеров
                    val list = json.optJSONArray("list")
                    if (list != null) {
                        parseCleanersArray(list)
                    }
                }
                "position_update" -> {
                    // 5.2 Релей позиции
                    val uid = json.optString("userId", "")
                    if (uid.isNotEmpty() && uid != userId) {
                        val otherPos = UserPosition(
                            userId = uid,
                            x = json.optDouble("x", 400.0),
                            y = json.optDouble("y", 300.0),
                            floor = json.optInt("floor", 1),
                            timestamp = json.optLong("timestamp", System.currentTimeMillis())
                        )
                        updateSingleCleanerPosition(otherPos)
                    }
                }
                "cleaner_status" -> {
                    val uid = json.optString("userId", "")
                    val online = json.optBoolean("online", false)
                    Log.d(TAG, "Cleaner status: uid=$uid, online=$online")
                }
                "pong" -> {
                    Log.d(TAG, "Received pong from server")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing incoming WS message: ${e.message}", e)
        }
    }

    private fun parseCleanersArray(array: JSONArray) {
        val result = mutableListOf<UserPosition>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val uid = obj.optString("userId", "")
            if (uid.isNotEmpty() && uid != userId && obj.optBoolean("online", true)) {
                result.add(
                    UserPosition(
                        userId = uid,
                        x = obj.optDouble("x", 400.0),
                        y = obj.optDouble("y", 300.0),
                        floor = obj.optInt("floor", 1),
                        timestamp = obj.optLong("lastTs", System.currentTimeMillis())
                    )
                )
            }
        }
        _mapUpdateSnapshot.value = result
    }

    private fun updateSingleCleanerPosition(pos: UserPosition) {
        val current = _mapUpdateSnapshot.value.toMutableList()
        val idx = current.indexOfFirst { it.userId == pos.userId }
        if (idx >= 0) {
            current[idx] = pos
        } else {
            current.add(pos)
        }
        _mapUpdateSnapshot.value = current
    }

    /**
     * Получение или создание постоянного UUID пользователя.
     */
    private fun getOrCreateUserId(): String {
        var id = prefs.getString(KEY_USER_ID, null)
        if (id.isNullOrEmpty()) {
            id = "cleaner_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().take(4)
            prefs.edit().putString(KEY_USER_ID, id).apply()
            Log.i(TAG, "Generated new user ID: $id")
        }
        return id
    }
}
