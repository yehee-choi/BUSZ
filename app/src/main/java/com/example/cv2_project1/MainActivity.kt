package com.example.cv2_project1

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.cv2_project1.ui.theme.CV2_Project1Theme
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private val TAG = "MainActivity"

    private lateinit var cameraManager: CameraManager
    private lateinit var speechManager: SpeechManager
    lateinit var textToSpeech: TextToSpeech
    private lateinit var locationManager: LocationManager
    private lateinit var webSocketManager: WebSocketManager

    // 음성 인식 결과를 UI에 전달하기 위한 상태
    private var speechResultCallback: ((String) -> Unit)? = null
    private var speechStatusCallback: ((Boolean) -> Unit)? = null

    // 앱 상태 관리
    private var speechCompleted = false
    private var locationSent = false
    private var isCameraActive = false
    private var isMonitoringActive = false

    // 🔥 음성 인식 재시도 제어
    private var speechRetryCount = 0
    private val MAX_SPEECH_RETRY = 1  // 최대 1번만 재시도
    private var hasTriedRetry = false  // 이미 재시도했는지 확인

    // 필요한 권한들
    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.MODIFY_AUDIO_SETTINGS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "모든 권한이 허용되었습니다.", Toast.LENGTH_SHORT).show()
            initializeManagers()
        } else {
            Toast.makeText(this, "카메라, 마이크, 위치 권한이 필요합니다.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Log.d(TAG, "🚀 === MainActivity onCreate 시작 ===")

        // TTS 초기화
        textToSpeech = TextToSpeech(this, this)
        Log.d(TAG, "🔊 TTS 초기화 시작")

        setContent {
            CV2_Project1Theme {
                MainScreen(
                    activity = this@MainActivity,
                    onCameraManagerReady = { manager ->
                        cameraManager = manager
                        Log.d(TAG, "📷 카메라 매니저 준비 완료")
                    },
                    onSpeechManagerReady = { resultCallback, statusCallback ->
                        speechResultCallback = resultCallback
                        speechStatusCallback = statusCallback
                        Log.d(TAG, "🎤 음성 매니저 콜백 등록 완료")
                    }
                )
            }
        }

        // 권한 확인 후 매니저 초기화
        checkAndRequestPermissions()
    }

    private fun initializeManagers() {
        Log.d(TAG, "🚀 === 매니저 초기화 시작 ===")

        // 1. 위치 매니저 초기화
        locationManager = LocationManager(this)
        Log.d(TAG, "📍 위치 매니저 초기화 완료")

        // 2. WebSocket 매니저 초기화
        webSocketManager = WebSocketManager(this)
        Log.d(TAG, "📡 WebSocket 매니저 초기화 완료")

        // 3. 음성 인식 매니저 초기화
        speechManager = SpeechManager(this, textToSpeech) { result ->
            handleSpeechResult(result)
        }
        Log.d(TAG, "🎤 음성 인식 매니저 초기화 완료")

        // 4. 서버 연결 시작
        connectToServer()

        // 5. 3초 후 자동 음성 인식 시작
        Handler(mainLooper).postDelayed({
            startSpeechRecognitionFlow()
        }, 3000)

        Log.d(TAG, "🚀 === 매니저 초기화 완료 ===")
    }

    private fun connectToServer() {
        Log.d(TAG, "🔌 === 서버 연결 시작 ===")

        webSocketManager.connect(
            onStatusChanged = { isConnected ->
                runOnUiThread {
                    if (isConnected) {
                        Log.d(TAG, "✅ 서버 연결 성공!")
                        Toast.makeText(this@MainActivity, "서버 연결 완료", Toast.LENGTH_SHORT).show()
                    } else {
                        Log.e(TAG, "❌ 서버 연결 실패")
                        Toast.makeText(this@MainActivity, "서버 연결 실패", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onVoiceOutput = { message ->
                // 🔊 서버 응답을 TTS로 출력 (비동기 처리)
                Log.d(TAG, "🔊 === 서버 TTS 콜백 호출됨 ===")
                Log.d(TAG, "🔊 TTS 메시지: '$message'")

                runOnUiThread {
                    if (::textToSpeech.isInitialized) {
                        Log.d(TAG, "✅ TTS 초기화됨 - 음성 출력 시작")
                        val result = textToSpeech.speak(message, TextToSpeech.QUEUE_ADD, null, "server_response")
                        Log.d(TAG, "🔊 TTS speak 결과: ${if (result == TextToSpeech.SUCCESS) "성공" else "실패"}")
                    } else {
                        Log.e(TAG, "❌ TTS가 초기화되지 않음!")
                    }
                }
            },
            // 🔥 버스 없을 때 재시도 콜백 추가
            onBusNotFound = {
                Log.d(TAG, "❌ 버스 없음 콜백 호출됨")
                runOnUiThread {
                    handleBusNotFound()
                }
            },
            // 🔥 버스 찾았을 때 카메라 시작 콜백 추가
            onBusFound = {
                Log.d(TAG, "✅ 버스 찾음 콜백 호출됨")
                runOnUiThread {
                    handleBusFound()
                }
            }
        )

        Log.d(TAG, "🔌 === 서버 연결 설정 완료 ===")
    }

    // 🔥 버스 찾았을 때 처리 함수
    private fun handleBusFound() {
        Log.d(TAG, "✅ === handleBusFound 시작 ===")
        Log.d(TAG, "✅ 버스 찾음 - 카메라 서비스 시작")

        // 🔥 버스를 찾았을 때만 카메라 시작
        Handler(mainLooper).postDelayed({
            startAsyncServices()
        }, 500) // 0.5초 후 시작

        Log.d(TAG, "✅ === handleBusFound 종료 ===")
    }

    // 🔥 버스 없을 때 처리 함수
    private fun handleBusNotFound() {
        Log.w(TAG, "❌ === handleBusNotFound 시작 ===")
        Log.w(TAG, "❌ 버스를 찾을 수 없음 - 재시도 여부 확인")
        Log.w(TAG, "❌ hasTriedRetry: $hasTriedRetry, speechRetryCount: $speechRetryCount")

        if (!hasTriedRetry && speechRetryCount < MAX_SPEECH_RETRY) {
            Log.d(TAG, "🔄 음성 인식 재시도 시작 (${speechRetryCount + 1}/${MAX_SPEECH_RETRY})")

            hasTriedRetry = true
            speechRetryCount++

            // TTS로 재시도 안내
            if (::textToSpeech.isInitialized) {
                Log.d(TAG, "🔊 재시도 안내 TTS 출력")
                textToSpeech.speak(
                    "해당 버스를 찾을 수 없습니다. 다시 버스 번호를 말씀해주세요.",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "retry_prompt"
                )
            }

            Toast.makeText(this, "❌ 버스를 찾을 수 없습니다. 다시 말씀해주세요.", Toast.LENGTH_LONG).show()

            // 3초 후 음성 인식 재시작
            Handler(mainLooper).postDelayed({
                restartSpeechRecognition()
            }, 3000)

        } else {
            Log.w(TAG, "⚠️ 재시도 횟수 초과 또는 이미 재시도함")

            if (::textToSpeech.isInitialized) {
                Log.d(TAG, "🔊 최종 포기 TTS 출력")
                textToSpeech.speak(
                    "버스 정보를 확인할 수 없습니다. 앱을 다시 시작해주세요.",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "final_error"
                )
            }

            Toast.makeText(this, "❌ 버스 정보를 확인할 수 없습니다.", Toast.LENGTH_LONG).show()
        }

        Log.w(TAG, "❌ === handleBusNotFound 종료 ===")
    }

    // 🔥 음성 인식 재시작 함수
    private fun restartSpeechRecognition() {
        Log.d(TAG, "🔄 === 음성 인식 재시작 ===")

        // 상태 초기화
        speechCompleted = false
        locationSent = false

        // UI 업데이트
        speechResultCallback?.invoke("다시 버스 번호를 말씀해주세요")

        // 음성 인식 시작
        startSpeechRecognitionFlow()
    }

    private fun startSpeechRecognitionFlow() {
        Log.d(TAG, "🎤 === 음성 인식 플로우 시작 ===")

        if (!speechCompleted && ::speechManager.isInitialized) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {

                speechStatusCallback?.invoke(true)
                speechManager.startSpeechRecognition()
                Log.d(TAG, "🎤 음성 인식 시작됨")
            } else {
                Log.e(TAG, "❌ 마이크 권한 없음")
                Toast.makeText(this, "마이크 권한을 확인해주세요", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleSpeechResult(speechResult: String) {
        Log.d(TAG, "🎤 === 음성 인식 완료 ===")
        Log.d(TAG, "🎤 원본 음성 인식 결과: '$speechResult'")

        speechResultCallback?.invoke(speechResult)
        speechStatusCallback?.invoke(false)
        speechCompleted = true

        // TTS로 인식 결과 안내
        if (::textToSpeech.isInitialized) {
            textToSpeech.speak("인식된 음성: $speechResult", TextToSpeech.QUEUE_FLUSH, null, "speech_result")
        }

        // 🔍 버스 번호 추출 과정 상세 로깅
        Log.d(TAG, "🔍 === 버스 번호 추출 과정 시작 ===")
        val busNumber = extractBusNumber(speechResult)
        Log.d(TAG, "🚌 === 최종 추출된 버스 번호: [$busNumber] ===")

        // 🔍 추출 결과 상세 분석
        if (busNumber == "0000") {
            Log.w(TAG, "⚠️ 기본값 사용됨 - 음성에서 번호 추출 실패")
            Log.w(TAG, "⚠️ 원본 음성: '$speechResult' → 기본값: '$busNumber'")
        } else {
            Log.d(TAG, "✅ 음성에서 성공적으로 추출됨")
            Log.d(TAG, "✅ 변환: '$speechResult' → '$busNumber'")
        }

        // 🔍 서버 전송 직전 최종 확인
        Log.d(TAG, "📤 === 서버 전송 직전 확인 ===")
        Log.d(TAG, "📤 전송할 버스 번호: '$busNumber'")
        Log.d(TAG, "📤 원본 음성 텍스트: '$speechResult'")

        // 위치 정보 수집 및 서버 전송
        sendLocationToServer(busNumber)

        // 🔥 카메라 시작은 서버 응답 확인 후에만 (재시도 중이 아닐 때만)
        if (speechRetryCount == 0) {
            Log.d(TAG, "🔥 첫 번째 음성 인식 - 서버 응답 대기 중 (카메라 시작 보류)")
        } else {
            Log.d(TAG, "🔥 재시도 음성 인식 - 서버 응답 대기 중 (카메라 시작 보류)")
        }
    }

    private fun sendLocationToServer(busNumber: String) {
        Log.d(TAG, "📍 === 위치 정보 서버 전송 시작 ===")

        val location = locationManager.getLocationForServer()
        val lat = location?.first ?: 37.497928      // 기본값: 강남역
        val lng = location?.second ?: 127.027583

        Log.d(TAG, "📍 위치 정보: 위도=$lat, 경도=$lng")
        Log.d(TAG, "🚌 버스 번호: $busNumber")
        Log.d(TAG, "📡 WebSocket 연결 상태: ${webSocketManager.isConnected}")

        if (webSocketManager.isConnected) {
            val success = webSocketManager.sendBusLocationRequest(
                latitude = lat,
                longitude = lng,
                busNumber = busNumber,
                interval = 30  // 30초 간격
            )

            runOnUiThread {
                if (success) {
                    Log.d(TAG, "✅ 서버 전송 성공")
                    Toast.makeText(this@MainActivity, "✅ 버스 $busNumber 모니터링 시작", Toast.LENGTH_SHORT).show()
                    locationSent = true
                    isMonitoringActive = true

                    // 위치 전송 후 GPS 중지 (배터리 절약)
                    locationManager.stopLocationUpdates()
                } else {
                    Log.e(TAG, "❌ 서버 전송 실패")
                    Toast.makeText(this@MainActivity, "❌ 서버 전송 실패", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Log.w(TAG, "⚠️ 서버 미연결 상태")
            Toast.makeText(this, "서버 연결을 확인해주세요", Toast.LENGTH_SHORT).show()
        }

        Log.d(TAG, "📍 === 위치 정보 서버 전송 완료 ===")
    }

    private fun startAsyncServices() {
        Log.d(TAG, "🚀 === 비동기 서비스 시작 ===")

        // 🔥 카메라 매니저 확인 및 시작
        if (::cameraManager.isInitialized) {
            Log.d(TAG, "📷 카메라 매니저 준비됨 - 카메라 시작")

            try {
                // 카메라 시작
                cameraManager.startCamera()
                Log.d(TAG, "📷 카메라 시작 완료")

                // 1초 후 객체 감지 시작 (카메라 초기화 시간 확보)
                Handler(mainLooper).postDelayed({
                    try {
                        cameraManager.startAsyncObjectDetection()
                        isCameraActive = true
                        Log.d(TAG, "🔍 비동기 객체 감지 시작 완료")

                        // 🔊 카메라 감지 시작 안내
                        if (::textToSpeech.isInitialized) {
                            textToSpeech.speak(
                                "카메라 객체 감지가 시작되었습니다",
                                TextToSpeech.QUEUE_ADD,
                                null,
                                "camera_start"
                            )
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "💥 객체 감지 시작 실패", e)
                    }
                }, 1000)

            } catch (e: Exception) {
                Log.e(TAG, "💥 카메라 시작 실패", e)
                Toast.makeText(this, "카메라 시작 실패: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Log.e(TAG, "❌ 카메라 매니저가 초기화되지 않음")
            Toast.makeText(this, "카메라 매니저 초기화 실패", Toast.LENGTH_SHORT).show()
        }

        // 서버 모니터링은 이미 활성화됨 (30초 주기는 서버에서 자동 처리)
        Log.d(TAG, "📡 서버 모니터링 활성화됨 (30초 주기)")
        Log.d(TAG, "🚀 === 비동기 서비스 시작 완료 ===")
    }

    private fun extractBusNumber(speechText: String): String {
        Log.d(TAG, "🔍 === 버스 번호 추출 시작 ===")
        Log.d(TAG, "🔍 원본 텍스트: '$speechText'")

        val cleanedText = speechText.replace(",", "").replace("，", "")
        Log.d(TAG, "🔍 정제된 텍스트: '$cleanedText'")

        val patterns = listOf(
            Regex("""(\d{1,4})번"""),
            Regex("""(\d{1,4})\s*번"""),
            Regex("""버스\s*(\d{1,4})"""),
            Regex("""(\d{1,4})\s*버스"""),
            Regex("""\b(\d{1,4})\b""")
        )

        for ((index, pattern) in patterns.withIndex()) {
            val match = pattern.find(cleanedText)
            if (match != null) {
                val busNumber = match.groupValues[1]
                if (busNumber.length >= 1 && busNumber.length <= 4 && busNumber.toIntOrNull() != null) {
                    Log.d(TAG, "✅ 패턴 #${index + 1}에서 버스 번호 발견: $busNumber")
                    return busNumber
                }
            }
        }

        Log.w(TAG, "❌ 버스 번호 추출 실패 - 기본값 반환: 0000")
        return "0000"
    }

    override fun onInit(status: Int) {
        Log.d(TAG, "🔊 === TTS 초기화 콜백 ===")
        Log.d(TAG, "🔊 TTS 초기화 상태: ${if (status == TextToSpeech.SUCCESS) "성공" else "실패"}")

        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech.setLanguage(Locale.KOREAN)

            when (result) {
                TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> {
                    Log.e(TAG, "❌ 한국어가 지원되지 않습니다")
                    Toast.makeText(this, "한국어 TTS가 지원되지 않습니다", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    Log.d(TAG, "✅ TTS 한국어 설정 완료")
                    Toast.makeText(this, "음성 인식이 준비되었습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Log.e(TAG, "❌ TTS 초기화 실패")
            Toast.makeText(this, "음성 합성 초기화에 실패했습니다", Toast.LENGTH_SHORT).show()
        }
    }

    fun startSpeechRecognition() {
        if (!speechCompleted && ::speechManager.isInitialized) {
            speechStatusCallback?.invoke(true)
            speechManager.startSpeechRecognition()
        } else {
            Toast.makeText(this, "음성 인식이 이미 완료되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAndRequestPermissions() {
        Log.d(TAG, "🔒 === 권한 확인 시작 ===")

        val permissionsNeeded = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        Log.d(TAG, "🔒 필요한 권한: ${permissionsNeeded.size}개")
        permissionsNeeded.forEach { Log.d(TAG, "  - $it") }

        if (permissionsNeeded.isNotEmpty()) {
            permissionLauncher.launch(permissionsNeeded.toTypedArray())
        } else {
            Log.d(TAG, "✅ 모든 권한이 이미 허용됨")
            initializeManagers()
        }
    }

    override fun onResume() {
        super.onResume()

        Log.d(TAG, "📱 === onResume ===")
        Log.d(TAG, "📱 speechCompleted: $speechCompleted, isCameraActive: $isCameraActive")
        Log.d(TAG, "📱 locationSent: $locationSent")

        // 위치 추적 시작 (아직 서버로 전송하지 않은 경우에만)
        if (::locationManager.isInitialized && !locationSent) {
            Log.d(TAG, "📍 위치 추적 시작")
            locationManager.startLocationUpdates { lat, lng ->
                Log.d(TAG, "📍 위치 업데이트: 위도 $lat, 경도 $lng")
            }
        }

        // 🔥 음성 인식 완료 후 카메라 서비스 재시작 (중요!)
        if (speechCompleted && ::cameraManager.isInitialized && !isCameraActive) {
            Log.d(TAG, "🔄 카메라 서비스 재시작")
            Handler(mainLooper).postDelayed({
                startAsyncServices()
            }, 500)
        }
    }

    override fun onPause() {
        super.onPause()

        Log.d(TAG, "📱 onPause - 카메라 일시정지")

        if (::cameraManager.isInitialized && isCameraActive) {
            cameraManager.pauseDetection()
            Log.d(TAG, "🔍 카메라 감지 일시정지")
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "📱 === onDestroy - 모든 리소스 정리 ===")

        if (::textToSpeech.isInitialized) {
            textToSpeech.shutdown()
            Log.d(TAG, "🔊 TTS 종료")
        }
        if (::speechManager.isInitialized) {
            speechManager.cleanup()
            Log.d(TAG, "🎤 SpeechManager 정리")
        }
        if (::cameraManager.isInitialized) {
            cameraManager.cleanup()
            Log.d(TAG, "📷 CameraManager 정리")
        }
        if (::locationManager.isInitialized) {
            locationManager.cleanup()
            Log.d(TAG, "📍 LocationManager 정리")
        }
        if (::webSocketManager.isInitialized) {
            webSocketManager.cleanup()
            Log.d(TAG, "📡 WebSocketManager 정리")
        }
        super.onDestroy()

        Log.d(TAG, "📱 === onDestroy 완료 ===")
    }
}