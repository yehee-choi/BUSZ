package com.example.cv2_project1

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit

@Serializable
data class BusLocationRequest(
    val lat: Double,
    val lng: Double,
    val bus_number: String,
    val interval: Int = 30
)

class WebSocketManager {

    private val TAG = "WebSocketManager"

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // JSON 직렬화 설정
    private val json = Json {
        prettyPrint = true
        isLenient = true
    }

    // 웹소켓 연결 상태
    var isConnected: Boolean = false
        private set

    // 연결 상태 콜백
    private var onConnectionStatusChanged: ((Boolean) -> Unit)? = null

    // 서버 URL - ngrok 주소
    private val serverUrl = "wss://driven-sweeping-sheep.ngrok-free.app/websocket"

    fun connect(onStatusChanged: (Boolean) -> Unit = {}) {
        onConnectionStatusChanged = onStatusChanged

        if (isConnected) {
            Log.d(TAG, "이미 웹소켓에 연결되어 있습니다")
            return
        }

        val request = Request.Builder()
            .url(serverUrl)
            .addHeader("ngrok-skip-browser-warning", "true")  // ngrok 경고 페이지 스킵
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "웹소켓 연결 성공")
                isConnected = true
                onConnectionStatusChanged?.invoke(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "서버로부터 메시지 수신: $text")
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(TAG, "서버로부터 바이너리 메시지 수신")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "웹소켓 연결 종료 중: $reason")
                isConnected = false
                onConnectionStatusChanged?.invoke(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "웹소켓 연결 종료됨: $reason")
                isConnected = false
                onConnectionStatusChanged?.invoke(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "웹소켓 연결 실패", t)
                isConnected = false
                onConnectionStatusChanged?.invoke(false)
            }
        })
    }

    fun sendBusLocationRequest(
        latitude: Double,
        longitude: Double,
        busNumber: String,
        interval: Int = 30
    ): Boolean {
        if (!isConnected || webSocket == null) {
            Log.w(TAG, "웹소켓이 연결되지 않았습니다")
            return false
        }

        val request = BusLocationRequest(
            lat = latitude,
            lng = longitude,
            bus_number = busNumber,
            interval = interval
        )

        return try {
            val jsonString = json.encodeToString(request)
            val success = webSocket!!.send(jsonString)

            if (success) {
                Log.d(TAG, "버스 위치 요청 전송 성공: $jsonString")
            } else {
                Log.e(TAG, "버스 위치 요청 전송 실패")
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "JSON 직렬화 또는 전송 중 오류", e)
            false
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "앱 종료")
        webSocket = null
        isConnected = false
        onConnectionStatusChanged?.invoke(false)
        Log.d(TAG, "웹소켓 연결 해제")
    }

    fun cleanup() {
        disconnect()
        client.dispatcher.executorService.shutdown()
    }
}