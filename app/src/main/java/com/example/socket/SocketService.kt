package com.example.socket

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.model.ConnectionState
import com.example.model.UserPosition
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

/**
 * Сервис управления сетевым соединением через Socket.io клиент.
 * Подключается к backend-серверу на wss://icv.dotozen.ru.
 *
 * Функциональность:
 * - Подключение/отключение по WebSocket протоколу Socket.io
 * - Автопереподключение при обрыве сети
 * - Отправка события "position_update"
 * - Приём события "map_update" для отладки и отображения других пользователей
 * - Генерация и сохранение постоянного UUID пользователя в SharedPreferences
 */
class SocketService(private val context: Context) {

    companion object {
        private const val TAG = "SocketService"
        // Константа URL Socket.io сервера (WSS с валидным TLS-сертификатом)
        const val SERVER_URL = "https://icv.dotozen.ru"
        private const val PREFS_NAME = "cleaner_tracker_prefs"
        private const val KEY_USER_ID = "unique_user_id"
        private const val KEY_CLEANER_NAME = "cleaner_custom_name"

        // Имена событий протокола Socket.io
        const val EVENT_POSITION_UPDATE = "position_update"
        const val EVENT_MAP_UPDATE = "map_update"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Постоянный уникальный идентификатор клинера (UUID), генерируется при первом запуске.
     */
    val userId: String = getOrCreateUserId()

    private val _cleanerName = MutableStateFlow(getSavedCleanerName())
    val cleanerName: StateFlow<String> = _cleanerName.asStateFlow()

    fun updateCleanerName(name: String) {
        val trimmed = name.trim()
        _cleanerName.value = trimmed
        prefs.edit().putString(KEY_CLEANER_NAME, trimmed).apply()
    }

    private fun getSavedCleanerName(): String {
        return prefs.getString(KEY_CLEANER_NAME, "") ?: ""
    }

    private var socket: Socket? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _mapUpdateSnapshot = MutableStateFlow<List<UserPosition>>(emptyList())
    val mapUpdateSnapshot: StateFlow<List<UserPosition>> = _mapUpdateSnapshot.asStateFlow()

    private val _lastSentTimestamp = MutableStateFlow<Long>(0L)
    val lastSentTimestamp: StateFlow<Long> = _lastSentTimestamp.asStateFlow()

    private val _packetsSentCount = MutableStateFlow<Long>(0L)
    val packetsSentCount: StateFlow<Long> = _packetsSentCount.asStateFlow()

    /**
     * Инициализация и подключение к Socket.io серверу.
     */
    fun connect() {
        if (socket != null && socket?.connected() == true) {
            Log.d(TAG, "Socket already connected")
            return
        }

        try {
            _connectionState.value = ConnectionState.Connecting

            val options = IO.Options().apply {
                reconnection = true
                reconnectionAttempts = Int.MAX_VALUE
                reconnectionDelay = 1000L
                reconnectionDelayMax = 5000L
                timeout = 10000L
                // Использование надежных транспортов websocket с фоллбеком на polling
                transports = arrayOf("websocket", "polling")
            }

            val uri = URI.create(SERVER_URL)
            val s = IO.socket(uri, options)

            s.on(Socket.EVENT_CONNECT) {
                Log.i(TAG, "Socket connected successfully: ID = ${s.id()}")
                _connectionState.value = ConnectionState.Connected
            }

            s.on(Socket.EVENT_DISCONNECT) { args ->
                val reason = args.firstOrNull()?.toString() ?: "Unknown reason"
                Log.w(TAG, "Socket disconnected: $reason")
                _connectionState.value = ConnectionState.Disconnected
            }

            s.on(Socket.EVENT_CONNECT_ERROR) { args ->
                val errorMsg = args.firstOrNull()?.toString() ?: "Connection error"
                Log.e(TAG, "Socket connect error: $errorMsg")
                _connectionState.value = ConnectionState.Error(errorMsg)
            }

            // Слушаем событие "map_update" от сервера (снапшот всех пользователей)
            s.on(EVENT_MAP_UPDATE) { args ->
                try {
                    val rawData = args.firstOrNull()
                    val parsedUsers = parseMapUpdate(rawData)
                    _mapUpdateSnapshot.value = parsedUsers
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing map_update payload: ${e.message}", e)
                }
            }

            socket = s
            s.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize socket: ${e.message}", e)
            _connectionState.value = ConnectionState.Error(e.localizedMessage ?: "Init error")
        }
    }

    /**
     * Отключение от Socket.io сервера.
     */
    fun disconnect() {
        try {
            socket?.disconnect()
            socket?.off()
            socket = null
            _connectionState.value = ConnectionState.Disconnected
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect: ${e.message}", e)
        }
    }

    /**
     * Отправка текущей позиции клинера на backend:
     * Событие: "position_update"
     * Payload: { "userId": "string", "id": "string", "name": "string", "x": number, "y": number, "floor": number, "timestamp": long }
     */
    fun sendPosition(x: Double, y: Double, floor: Int, customName: String? = null) {
        val s = socket
        if (s == null || !s.connected()) {
            Log.w(TAG, "Cannot send position: Socket is not connected (state=${_connectionState.value})")
            return
        }

        scope.launch {
            try {
                val now = System.currentTimeMillis()
                val displayName = customName?.ifEmpty { null } ?: _cleanerName.value.ifEmpty { userId }
                val payload = JSONObject().apply {
                    put("userId", userId)
                    put("id", userId)
                    put("name", displayName)
                    put("x", x)
                    put("y", y)
                    put("floor", floor)
                    put("timestamp", now)
                }

                s.emit(EVENT_POSITION_UPDATE, payload)
                _lastSentTimestamp.value = now
                _packetsSentCount.value += 1
                Log.d(TAG, "Sent position: name=$displayName (x=%.2f, y=%.2f, floor=%d)".format(x, y, floor))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to emit position_update: ${e.message}", e)
            }
        }
    }

    /**
     * Парсинг снапшота "map_update" от сервера.
     * Сервер может слать JSON-массив объектов или объект с полем "users".
     */
    private fun parseMapUpdate(data: Any?): List<UserPosition> {
        val result = mutableListOf<UserPosition>()
        when (data) {
            is JSONArray -> {
                for (i in 0 until data.length()) {
                    val obj = data.optJSONObject(i) ?: continue
                    parseUserPosition(obj)?.let { result.add(it) }
                }
            }
            is JSONObject -> {
                val usersArray = data.optJSONArray("users")
                if (usersArray != null) {
                    for (i in 0 until usersArray.length()) {
                        val obj = usersArray.optJSONObject(i) ?: continue
                        parseUserPosition(obj)?.let { result.add(it) }
                    }
                } else {
                    // Возможно передан один объект
                    parseUserPosition(data)?.let { result.add(it) }
                }
            }
            is String -> {
                // Если данные пришли в виде сырой строки JSON
                val jsonTrim = data.trim()
                if (jsonTrim.startsWith("[")) {
                    val jsonArray = JSONArray(jsonTrim)
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.optJSONObject(i) ?: continue
                        parseUserPosition(obj)?.let { result.add(it) }
                    }
                } else if (jsonTrim.startsWith("{")) {
                    val jsonObj = JSONObject(jsonTrim)
                    parseMapUpdate(jsonObj)
                }
            }
        }
        return result
    }

    private fun parseUserPosition(obj: JSONObject): UserPosition? {
        val uid = obj.optString("userId", obj.optString("id", ""))
        if (uid.isEmpty()) return null

        val x = obj.optDouble("x", 0.0)
        val y = obj.optDouble("y", 0.0)
        val floor = obj.optInt("floor", 1)
        val timestamp = obj.optLong("timestamp", System.currentTimeMillis())

        return UserPosition(userId = uid, x = x, y = y, floor = floor, timestamp = timestamp)
    }

    /**
     * Получение или создание постоянного UUID пользователя.
     */
    private fun getOrCreateUserId(): String {
        var id = prefs.getString(KEY_USER_ID, null)
        if (id.isNullOrEmpty()) {
            id = "cleaner-" + UUID.randomUUID().toString().take(8)
            prefs.edit().putString(KEY_USER_ID, id).apply()
            Log.i(TAG, "Generated new user ID: $id")
        }
        return id
    }
}
