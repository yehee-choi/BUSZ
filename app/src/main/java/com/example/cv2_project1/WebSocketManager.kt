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
    private val serverUrl = "ws://driven-sweeping-sheep.ngrok-free.app/"

    fun connect(onStatusChanged: (Boolean) -> Unit = {}) {

        Log.d(TAG, "🔌 connect() 메서드 호출됨")
        Log.d(TAG, "🔌 현재 연결 상태: $isConnected")
        Log.d(TAG, "🔌 서버 URL: $serverUrl")

        onConnectionStatusChanged = onStatusChanged

        if (isConnected) {
            Log.d(TAG, "이미 웹소켓에 연결되어 있습니다")
            return
        }

        Log.d(TAG, "📡 WebSocket 요청 생성 중...")

        val request = Request.Builder()
            .url(serverUrl)
            .addHeader("ngrok-skip-browser-warning", "true")  // ngrok 경고 페이지 스킵
            .build()

        Log.d(TAG, "📡 요청 생성 완료 - URL: ${request.url}")
        Log.d(TAG, "📡 요청 헤더: ${request.headers}")

        Log.d(TAG, "📡 newWebSocket 호출 시작...")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "🎉 onOpen 호출됨!")
                Log.d(TAG, "✅ 웹소켓 연결 성공!")
                Log.d(TAG, "✅ 응답 코드: ${response.code}")
                Log.d(TAG, "✅ 응답 메시지: ${response.message}")
                Log.d(TAG, "✅ 응답 헤더: ${response.headers}")

                isConnected = true
                onConnectionStatusChanged?.invoke(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "📨 서버로부터 텍스트 메시지 수신: $text")
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(TAG, "📨 서버로부터 바이너리 메시지 수신: ${bytes.hex()}")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "⚠️ onClosing 호출됨!")
                Log.d(TAG, "⚠️ 웹소켓 연결 종료 중: $reason (코드: $code)")
                isConnected = false
                onConnectionStatusChanged?.invoke(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "❌ onClosed 호출됨!")
                Log.d(TAG, "❌ 웹소켓 연결 종료됨: $reason (코드: $code)")
                isConnected = false
                onConnectionStatusChanged?.invoke(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "💥 onFailure 호출됨!")
                Log.e(TAG, "💥 웹소켓 연결 실패!")
                Log.e(TAG, "💥 에러 메시지: ${t.message}")
                Log.e(TAG, "💥 에러 타입: ${t.javaClass.simpleName}")
                Log.e(TAG, "💥 응답 코드: ${response?.code}")
                Log.e(TAG, "💥 응답 메시지: ${response?.message}")
                Log.e(TAG, "💥 응답 헤더: ${response?.headers}")
                Log.e(TAG, "💥 스택 트레이스:", t)

                isConnected = false
                onConnectionStatusChanged?.invoke(false)
            }
        })

        Log.d(TAG, "📡 newWebSocket 호출 완료")
    }

    fun sendBusLocationRequest(
        latitude: Double,
        longitude: Double,
        busNumber: String,
        interval: Int = 30
    ): Boolean {
        Log.d(TAG, "📤 sendBusLocationRequest 호출됨")
        Log.d(TAG, "📤 연결 상태: $isConnected")
        Log.d(TAG, "📤 webSocket null 여부: ${webSocket == null}")

        if (!isConnected || webSocket == null) {
            Log.w(TAG, "⚠️ 웹소켓이 연결되지 않았습니다")
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
            Log.d(TAG, "📤 전송할 JSON: $jsonString")

            val success = webSocket!!.send(jsonString)

            if (success) {
                Log.d(TAG, "✅ 버스 위치 요청 전송 성공!")
            } else {
                Log.e(TAG, "❌ 버스 위치 요청 전송 실패")
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "💥 JSON 직렬화 또는 전송 중 오류", e)
            false
        }
    }

    fun disconnect() {
        Log.d(TAG, "🔌 disconnect 호출됨")
        webSocket?.close(1000, "앱 종료")
        webSocket = null
        isConnected = false
        onConnectionStatusChanged?.invoke(false)
        Log.d(TAG, "🔌 웹소켓 연결 해제 완료")
    }

    fun cleanup() {
        Log.d(TAG, "🧹 cleanup 호출됨")
        disconnect()
        client.dispatcher.executorService.shutdown()
        Log.d(TAG, "🧹 cleanup 완료")
    }
}