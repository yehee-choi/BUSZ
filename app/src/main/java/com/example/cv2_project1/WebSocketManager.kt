package com.example.cv2_project1

import android.content.Context
import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.net.URISyntaxException
import java.text.SimpleDateFormat
import java.util.*

class WebSocketManager(private val context: Context) {

    private val TAG = "WebSocketManager"
    private lateinit var socket: Socket

    // 웹소켓 연결 상태
    var isConnected: Boolean = false
        private set

    // 세션 ID 저장 (플로우 2에서 사용)
    var sessionId: String? = null
        private set

    // 연결 상태 콜백
    private var onConnectionStatusChanged: ((Boolean) -> Unit)? = null

    // 음성 출력 콜백
    private var onVoiceOutputCallback: ((String) -> Unit)? = null

    // 서버 URL - ngrok 주소
    private val serverUrl = "https://driven-sweeping-sheep.ngrok-free.app"

    fun connect(onStatusChanged: (Boolean) -> Unit = {}, onVoiceOutput: ((String) -> Unit)? = null) {
        Log.d(TAG, "🔌 Socket.IO 연결 시작")
        Log.d(TAG, "🔌 서버 URL: $serverUrl")

        onConnectionStatusChanged = onStatusChanged
        onVoiceOutputCallback = onVoiceOutput

        if (isConnected) {
            Log.d(TAG, "이미 소켓에 연결되어 있습니다")
            return
        }

        try {
            socket = IO.socket(serverUrl)

            // 연결 성공 이벤트
            socket.on(Socket.EVENT_CONNECT) {
                Log.d(TAG, "🎉 Socket.IO 연결 성공!")
                isConnected = true
                onConnectionStatusChanged?.invoke(true)
            }

            // 연결 해제 이벤트
            socket.on(Socket.EVENT_DISCONNECT) {
                Log.d(TAG, "❌ Socket.IO 연결 해제됨")
                isConnected = false
                sessionId = null // 연결 해제 시 세션 ID 초기화
                onConnectionStatusChanged?.invoke(false)
            }

            // 연결 오류 이벤트
            socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
                Log.e(TAG, "💥 Socket.IO 연결 오류: ${args[0]}")
                isConnected = false
                sessionId = null // 연결 오류 시 세션 ID 초기화
                onConnectionStatusChanged?.invoke(false)
            }

            // 서버 연결 확인 메시지 수신 (session_id 포함)
            socket.on("connection_confirmed") { args ->
                if (args.isNotEmpty()) {
                    try {
                        val response = args[0] as JSONObject
                        val message = response.optString("message", "")
                        val newSessionId = response.optString("session_id", "")

                        Log.d(TAG, "🔗 연결 확인 메시지: $message")

                        if (newSessionId.isNotEmpty()) {
                            sessionId = newSessionId
                            Log.d(TAG, "💾 세션 ID 저장: $sessionId")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "연결 확인 메시지 파싱 오류", e)
                    }
                }
            }

            // 서버로부터 메시지 수신
            socket.on("message") { args ->
                if (args.isNotEmpty()) {
                    Log.d(TAG, "📨 서버로부터 일반 메시지 수신: ${args[0]}")
                }
            }

            // 버스 모니터링 시작 응답 수신
            socket.on("bus_monitoring_started") { args ->
                if (args.isNotEmpty()) {
                    try {
                        val response = args[0] as JSONObject
                        val message = response.optString("message", "")
                        val busNumber = response.optString("bus_number", "")
                        val interval = response.optInt("interval", 30)
                        val newSessionId = response.optString("session_id", "")

                        Log.d(TAG, "🚌 버스 모니터링 시작: $message")
                        Log.d(TAG, "🚌 버스 번호: $busNumber, 간격: ${interval}초")

                        if (newSessionId.isNotEmpty()) {
                            sessionId = newSessionId
                            Log.d(TAG, "💾 세션 ID 업데이트: $sessionId")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "버스 모니터링 시작 응답 파싱 오류", e)
                    }
                }
            }

            // 실시간 버스 위치 업데이트 수신 (30초마다)
            socket.on("bus_update") { args ->
                if (args.isNotEmpty()) {
                    try {
                        val response = args[0] as JSONObject

                        // 📋 전체 JSON 응답 로그 출력
                        Log.d(TAG, "📋 서버 응답 JSON: $response")

                        val timestamp = response.optString("timestamp", "")
                        val busFound = response.optBoolean("bus_found", false)
                        val stationName = response.optString("station_name", "")
                        val busNumber = response.optString("bus_number", "")

                        Log.d(TAG, "📍 버스 위치 업데이트 수신 [$timestamp]")
                        Log.d(TAG, "📍 정류장: $stationName, 버스: ${busNumber}번")

                        if (busFound) {
                            // 버스 발견된 경우
                            val arrivalTime = response.optInt("arrival_time", 0)
                            val arrivalTimeFormatted = response.optString("arrival_time_formatted", "")
                            val remainingStations = response.optInt("remaining_stations", 0)
                            val vehicleType = response.optString("vehicle_type", "")
                            val routeType = response.optString("route_type", "")

                            Log.d(TAG, "✅ 버스 발견!")
                            Log.d(TAG, "⏰ 도착 예정: $arrivalTimeFormatted (${arrivalTime}초)")
                            Log.d(TAG, "🚏 남은 정류장: ${remainingStations}개")
                            Log.d(TAG, "🚌 차량 정보: $vehicleType ($routeType)")

                            // 🔊 버스 발견 시 음성 출력: "버스가 곧 도착합니다"
                            val voiceMessage = "버스가 ${arrivalTimeFormatted} 후에 도착합니다."
                            onVoiceOutputCallback?.invoke(voiceMessage)
                            Log.d(TAG, "🔊 음성 출력: $voiceMessage")

                        } else {
                            // 버스 못 찾은 경우
                            val message = response.optString("message", "")
                            Log.w(TAG, "❌ 버스 발견되지 않음")
                            Log.w(TAG, "❌ 서버 메시지: $message")

                            // 🔊 버스 없을 때 음성 출력: "버스가 없습니다"
                            val voiceMessage = "버스가 없습니다"
                            onVoiceOutputCallback?.invoke(voiceMessage)
                            Log.d(TAG, "🔊 음성 출력: $voiceMessage")
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "💥 버스 위치 업데이트 파싱 오류", e)
                        Log.e(TAG, "💥 원본 데이터: ${args[0]}")
                    }
                }
            }

            // 버스 모니터링 중단 응답 수신
            socket.on("bus_monitoring_stopped") { args ->
                if (args.isNotEmpty()) {
                    try {
                        val response = args[0] as JSONObject
                        val message = response.optString("message", "")
                        Log.d(TAG, "⏹️ 버스 모니터링 중단: $message")
                    } catch (e: Exception) {
                        Log.e(TAG, "버스 모니터링 중단 응답 파싱 오류", e)
                    }
                }
            }

            // 버스 모니터링 상태 응답 수신
            socket.on("monitoring_status") { args ->
                if (args.isNotEmpty()) {
                    try {
                        val response = args[0] as JSONObject
                        val active = response.optBoolean("active", false)
                        val busNumber = response.optString("bus_number", "")
                        val interval = response.optInt("interval", 30)

                        Log.d(TAG, "📊 모니터링 상태: ${if (active) "활성" else "비활성"}")
                        if (active) {
                            Log.d(TAG, "📊 모니터링 중: ${busNumber}번 (${interval}초 간격)")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "모니터링 상태 응답 파싱 오류", e)
                    }
                }
            }

            // 버스 도착 정보 수신
            socket.on("bus_arrival_info") { args ->
                if (args.isNotEmpty()) {
                    Log.d(TAG, "⏰ 버스 도착 정보 수신: ${args[0]}")
                }
            }

            // 에러 메시지 수신
            socket.on("error") { args ->
                if (args.isNotEmpty()) {
                    Log.e(TAG, "❌ 서버 에러 수신: ${args[0]}")
                }
            }

            // 실제 연결 시작
            socket.connect()
            Log.d(TAG, "📡 Socket.IO 연결 시도 중...")

        } catch (e: URISyntaxException) {
            Log.e(TAG, "💥 Socket.IO URI 오류", e)
            onConnectionStatusChanged?.invoke(false)
        }
    }

    fun sendBusLocationRequest(
        latitude: Double,
        longitude: Double,
        busNumber: String,
        interval: Int = 30
    ): Boolean {
        Log.d(TAG, "📤 sendBusLocationRequest 호출됨")
        Log.d(TAG, "📤 연결 상태: $isConnected")

        if (!isConnected || !::socket.isInitialized) {
            Log.w(TAG, "⚠️ 소켓이 연결되지 않았습니다")
            return false
        }

        return try {
            val data = JSONObject().apply {
                put("lat", latitude)
                put("lng", longitude)
                put("bus_number", busNumber)
                put("interval", interval)
            }

            Log.d(TAG, "📤 전송할 데이터: $data")
            Log.d(TAG, "📤 좌표: ($latitude, $longitude), 버스: ${busNumber}번, 간격: ${interval}초")

            // start_bus_monitoring 이벤트로 데이터 전송
            socket.emit("start_bus_monitoring", data)

            Log.d(TAG, "✅ 버스 모니터링 요청 전송 성공!")
            true

        } catch (e: Exception) {
            Log.e(TAG, "💥 데이터 전송 중 오류", e)
            false
        }
    }

    fun disconnect() {
        Log.d(TAG, "🔌 disconnect 호출됨")

        if (::socket.isInitialized) {
            socket.disconnect()
            socket.off() // 모든 이벤트 리스너 제거
        }

        isConnected = false
        sessionId = null // 연결 해제 시 세션 ID 초기화
        onConnectionStatusChanged?.invoke(false)
        Log.d(TAG, "🔌 Socket.IO 연결 해제 완료")
    }

    fun cleanup() {
        Log.d(TAG, "🧹 cleanup 호출됨")
        disconnect()
        Log.d(TAG, "🧹 cleanup 완료")
    }

    // JSON 응답을 result.txt 파일에 저장
    private fun saveJsonToFile(jsonResponse: JSONObject) {
        try {
            val file = File(context.filesDir, "result.txt")
            val fileWriter = FileWriter(file, true) // append mode

            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val currentTime = dateFormat.format(Date())

            fileWriter.append("[$currentTime] $jsonResponse\n")
            fileWriter.close()

            Log.d(TAG, "📁 JSON 파일 저장 성공: ${file.absolutePath}")

        } catch (e: IOException) {
            Log.e(TAG, "💥 JSON 파일 저장 실패", e)
        }
    }

    // result.txt 파일 내용 읽기 (선택사항)
    fun getResultFileContent(): String? {
        return try {
            val file = File(context.filesDir, "result.txt")
            if (file.exists()) {
                file.readText()
            } else {
                null
            }
        } catch (e: IOException) {
            Log.e(TAG, "💥 파일 읽기 실패", e)
            null
        }
    }

    // result.txt 파일 삭제 (선택사항)
    fun clearResultFile(): Boolean {
        return try {
            val file = File(context.filesDir, "result.txt")
            if (file.exists()) {
                file.delete()
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "💥 파일 삭제 실패", e)
            false
        }
    }
}