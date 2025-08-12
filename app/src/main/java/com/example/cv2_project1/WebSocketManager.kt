package com.example.cv2_project1

import android.content.Context
import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.*
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

    // 세션 ID 저장
    var sessionId: String? = null
        private set

    // 연결 상태 콜백
    private var onConnectionStatusChanged: ((Boolean) -> Unit)? = null

    // 음성 출력 콜백 (🔊 버스 도착 시간 안내 포함)
    private var onVoiceOutputCallback: ((String) -> Unit)? = null

    // 🔥 버스 없을 때 콜백
    private var onBusNotFoundCallback: (() -> Unit)? = null

    // 🔥 버스 찾았을 때 콜백
    private var onBusFoundCallback: (() -> Unit)? = null

    // 🔥 재시도 제어
    private var busNotFoundCallbackUsed = false

    // 비동기 응답 처리를 위한 코루틴 스코프
    private val responseScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 서버 URL
    private val serverUrl = "https://driven-sweeping-sheep.ngrok-free.app"

    fun connect(
        onStatusChanged: (Boolean) -> Unit = {},
        onVoiceOutput: ((String) -> Unit)? = null,
        onBusNotFound: (() -> Unit)? = null,  // 🔥 버스 없을 때
        onBusFound: (() -> Unit)? = null      // 🔥 버스 찾았을 때
    ) {
        Log.d(TAG, "🔌 Socket.IO 연결 시작")
        Log.d(TAG, "🔌 서버 URL: $serverUrl")

        // 🔍 콜백 저장 상태 디버깅
        onConnectionStatusChanged = onStatusChanged
        onVoiceOutputCallback = onVoiceOutput
        onBusNotFoundCallback = onBusNotFound
        onBusFoundCallback = onBusFound

        Log.d(TAG, "🔍 콜백 등록 상태:")
        Log.d(TAG, "  - onVoiceOutputCallback: ${if (onVoiceOutput != null) "등록됨" else "null"}")
        Log.d(TAG, "  - onBusNotFoundCallback: ${if (onBusNotFound != null) "등록됨" else "null"}")
        Log.d(TAG, "  - onBusFoundCallback: ${if (onBusFound != null) "등록됨" else "null"}")

        if (isConnected) {
            Log.d(TAG, "이미 소켓에 연결되어 있습니다")
            return
        }

        try {
            socket = IO.socket(serverUrl)
            setupSocketEvents()
            socket.connect()
            Log.d(TAG, "📡 Socket.IO 연결 시도 중...")

        } catch (e: URISyntaxException) {
            Log.e(TAG, "💥 Socket.IO URI 오류", e)
            onConnectionStatusChanged?.invoke(false)
        }
    }

    private fun setupSocketEvents() {
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
            sessionId = null
            onConnectionStatusChanged?.invoke(false)
        }

        // 연결 오류 이벤트
        socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
            Log.e(TAG, "💥 Socket.IO 연결 오류: ${args[0]}")
            isConnected = false
            sessionId = null
            onConnectionStatusChanged?.invoke(false)
        }

        // 서버 연결 확인 메시지 수신
        socket.on("connection_confirmed") { args ->
            handleConnectionConfirmed(args)
        }

        // 버스 모니터링 시작 응답 수신
        socket.on("bus_monitoring_started") { args ->
            handleBusMonitoringStarted(args)
        }

        // 🚀 실시간 버스 위치 업데이트 수신 (30초마다 - 비동기 처리)
        socket.on("bus_update") { args ->
            Log.d(TAG, "📡 bus_update 이벤트 수신됨!")
            handleBusUpdateAsync(args)
        }

        // 버스 모니터링 중단 응답 수신
        socket.on("bus_monitoring_stopped") { args ->
            handleBusMonitoringStopped(args)
        }

        // 버스 모니터링 상태 응답 수신
        socket.on("monitoring_status") { args ->
            handleMonitoringStatus(args)
        }

        // 에러 메시지 수신
        socket.on("error") { args ->
            if (args.isNotEmpty()) {
                Log.e(TAG, "❌ 서버 에러 수신: ${args[0]}")
            }
        }
    }

    private fun handleConnectionConfirmed(args: Array<Any>) {
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

    private fun handleBusMonitoringStarted(args: Array<Any>) {
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

                // 🔥 새로운 모니터링 시작 시 재시도 플래그 초기화
                busNotFoundCallbackUsed = false

            } catch (e: Exception) {
                Log.e(TAG, "버스 모니터링 시작 응답 파싱 오류", e)
            }
        }
    }

    // 🚀 비동기 버스 업데이트 처리 (30초마다 서버에서 자동 전송) - 디버깅 강화
    private fun handleBusUpdateAsync(args: Array<Any>) {
        responseScope.launch {
            try {
                Log.d(TAG, "🚨 === 서버 응답 디버깅 시작 ===")

                if (args.isNotEmpty()) {
                    val response = args[0] as JSONObject

                    // 🔍 상세 디버깅 로그
                    Log.d(TAG, "📋 전체 JSON 응답: $response")

                    val timestamp = response.optString("timestamp", "")
                    val busFound = response.optBoolean("bus_found", false)
                    val busNumber = response.optString("bus_number", "")
                    val message = response.optString("message", "")
                    val stationName = response.optString("station_name", "")

                    Log.d(TAG, "🔍 timestamp: '$timestamp'")
                    Log.d(TAG, "🔍 bus_found: $busFound")
                    Log.d(TAG, "🔍 bus_number: '$busNumber'")
                    Log.d(TAG, "🔍 message: '$message'")
                    Log.d(TAG, "🔍 station_name: '$stationName'")

                    Log.d(TAG, "📍 버스 위치 업데이트 수신 [$timestamp]")
                    Log.d(TAG, "📍 정류장: $stationName, 버스: ${busNumber}번")

                    // 응답을 파일에 저장 (비동기)
                    saveJsonToFileAsync(response)

                    if (busFound) {
                        Log.d(TAG, "✅ 버스 발견됨 - handleBusFoundResponse 호출")

                        // 🔍 도착 시간 정보 추가 디버깅
                        val arrivalTime = response.optInt("arrival_time", 0)
                        val arrivalTimeFormatted = response.optString("arrival_time_formatted", "")
                        val remainingStations = response.optInt("remaining_stations", 0)

                        Log.d(TAG, "⏰ arrival_time: ${arrivalTime}초")
                        Log.d(TAG, "⏰ arrival_time_formatted: '$arrivalTimeFormatted'")
                        Log.d(TAG, "🚏 remaining_stations: $remainingStations")

                        handleBusFoundResponse(response)
                    } else {
                        Log.w(TAG, "❌ 버스 발견되지 않음 - handleBusNotFoundResponse 호출")
                        handleBusNotFoundResponse(response)
                    }
                } else {
                    Log.e(TAG, "💥 서버 응답이 비어있음!")
                }

                Log.d(TAG, "🚨 === 서버 응답 디버깅 종료 ===")

            } catch (e: Exception) {
                Log.e(TAG, "💥 비동기 버스 업데이트 처리 오류", e)
                Log.e(TAG, "💥 원본 데이터: ${if (args.isNotEmpty()) args[0] else "빈 배열"}")
            }
        }
    }

    // 🚌⏰ 버스 발견 시 도착 시간 안내 - 디버깅 강화
    private suspend fun handleBusFoundResponse(response: JSONObject) {
        Log.d(TAG, "🚨 === handleBusFoundResponse 시작 ===")

        val arrivalTime = response.optInt("arrival_time", 0)                    // 초 단위
        val arrivalTimeFormatted = response.optString("arrival_time_formatted", "") // "5분 30초"
        val remainingStations = response.optInt("remaining_stations", 0)        // 남은 정류장 수
        val vehicleType = response.optString("vehicle_type", "")                // 차량 타입
        val routeType = response.optString("route_type", "")                    // 노선 타입
        val stationName = response.optString("station_name", "")                // 정류장 이름
        val busNumber = response.optString("bus_number", "")                    // 버스 번호

        Log.d(TAG, "✅ 버스 발견!")
        Log.d(TAG, "⏰ 도착 예정: $arrivalTimeFormatted (${arrivalTime}초)")
        Log.d(TAG, "🚏 남은 정류장: ${remainingStations}개")
        Log.d(TAG, "🚌 차량 정보: $vehicleType ($routeType)")
        Log.d(TAG, "📍 현재 정류장: $stationName")

        // 🔥 버스 찾았을 때 콜백 호출 (카메라 시작)
        withContext(Dispatchers.Main) {
            Log.d(TAG, "📷 카메라 시작 콜백 호출")
            if (onBusFoundCallback != null) {
                Log.d(TAG, "✅ onBusFoundCallback 존재함 - 호출")
                onBusFoundCallback?.invoke()
            } else {
                Log.e(TAG, "❌ onBusFoundCallback이 null!")
            }
        }

        // 🔊 상세한 도착 시간 음성 안내
        Log.d(TAG, "🔊 TTS 메시지 생성 중...")

        val voiceMessage = when {
            arrivalTime <= 60 -> {
                "🚨 ${busNumber}번 버스가 곧 도착합니다! 1분 이내에 도착 예정입니다."
            }
            arrivalTime <= 180 -> {
                "${busNumber}번 버스가 ${arrivalTimeFormatted} 후에 도착합니다. ${remainingStations}개 정류장 남았습니다."
            }
            arrivalTime <= 600 -> {
                "${busNumber}번 버스가 ${arrivalTimeFormatted} 후에 도착 예정입니다. 조금 더 기다려주세요."
            }
            else -> {
                "${busNumber}번 버스가 ${arrivalTimeFormatted} 후에 도착 예정입니다. 시간이 많이 남았습니다."
            }
        }

        Log.d(TAG, "🔊 생성된 TTS 메시지: '$voiceMessage'")

        withContext(Dispatchers.Main) {
            Log.d(TAG, "🔊 TTS 콜백 호출 시작")

            if (onVoiceOutputCallback != null) {
                Log.d(TAG, "✅ onVoiceOutputCallback 존재함 - TTS 호출")
                onVoiceOutputCallback?.invoke(voiceMessage)
                Log.d(TAG, "✅ TTS 콜백 호출 완료")
            } else {
                Log.e(TAG, "❌ onVoiceOutputCallback이 null!")
            }
        }

        Log.d(TAG, "🚨 === handleBusFoundResponse 종료 ===")
    }

    private suspend fun handleBusNotFoundResponse(response: JSONObject) {
        Log.d(TAG, "🚨 === handleBusNotFoundResponse 시작 ===")

        val message = response.optString("message", "")
        val busNumber = response.optString("bus_number", "")

        Log.w(TAG, "❌ 버스 발견되지 않음")
        Log.w(TAG, "❌ 서버 메시지: $message")
        Log.w(TAG, "❌ 요청한 버스 번호: $busNumber")

        // 🔥 첫 번째 버스 없음 응답일 때만 재시도 콜백 호출
        if (!busNotFoundCallbackUsed) {
            busNotFoundCallbackUsed = true
            Log.d(TAG, "🔄 첫 번째 버스 없음 - 재시도 콜백 호출")

            withContext(Dispatchers.Main) {
                if (onBusNotFoundCallback != null) {
                    Log.d(TAG, "✅ onBusNotFoundCallback 존재함 - 재시도 호출")
                    onBusNotFoundCallback?.invoke()
                } else {
                    Log.e(TAG, "❌ onBusNotFoundCallback이 null!")
                }
            }
        } else {
            // 🔊 재시도 후에도 버스 없을 때는 일반 음성 출력
            Log.d(TAG, "🔊 재시도 후에도 버스 없음 - 일반 음성 출력")

            val voiceMessage = if (busNumber.isNotEmpty()) {
                "${busNumber}번 버스는 현재 운행하지 않거나 해당 지역에 없습니다."
            } else {
                "현재 운행 중인 버스가 없습니다."
            }

            Log.d(TAG, "🔊 버스 없음 TTS 메시지: '$voiceMessage'")

            withContext(Dispatchers.Main) {
                if (onVoiceOutputCallback != null) {
                    Log.d(TAG, "✅ 버스 없음 TTS 호출")
                    onVoiceOutputCallback?.invoke(voiceMessage)
                } else {
                    Log.e(TAG, "❌ onVoiceOutputCallback이 null!")
                }
            }
        }

        Log.d(TAG, "🚨 === handleBusNotFoundResponse 종료 ===")
    }

    private fun handleBusMonitoringStopped(args: Array<Any>) {
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

    private fun handleMonitoringStatus(args: Array<Any>) {
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

    fun sendBusLocationRequest(
        latitude: Double,
        longitude: Double,
        busNumber: String,
        interval: Int = 30
    ): Boolean {
        Log.d(TAG, "📤 === sendBusLocationRequest 시작 ===")
        Log.d(TAG, "📤 연결 상태: $isConnected")
        Log.d(TAG, "📤 소켓 초기화 상태: ${if (::socket.isInitialized) "초기화됨" else "초기화 안됨"}")

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
            Log.d(TAG, "🔄 서버에서 ${interval}초마다 자동으로 bus_update 이벤트 전송 시작")
            Log.d(TAG, "📤 === sendBusLocationRequest 종료 ===")

            true

        } catch (e: Exception) {
            Log.e(TAG, "💥 데이터 전송 중 오류", e)
            false
        }
    }

    fun disconnect() {
        Log.d(TAG, "🔌 disconnect 호출됨")

        // 코루틴 스코프 취소
        responseScope.cancel()

        if (::socket.isInitialized) {
            socket.disconnect()
            socket.off() // 모든 이벤트 리스너 제거
        }

        isConnected = false
        sessionId = null
        onConnectionStatusChanged?.invoke(false)
        Log.d(TAG, "🔌 Socket.IO 연결 해제 완료")
    }

    fun cleanup() {
        Log.d(TAG, "🧹 cleanup 호출됨")
        disconnect()
        Log.d(TAG, "🧹 cleanup 완료")
    }

    // 비동기 JSON 파일 저장
    private suspend fun saveJsonToFileAsync(jsonResponse: JSONObject) {
        withContext(Dispatchers.IO) {
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
    }

    // result.txt 파일 내용 읽기
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

    // result.txt 파일 삭제
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