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

        // TTS 초기화
        textToSpeech = TextToSpeech(this, this)

        setContent {
            CV2_Project1Theme {
                MainScreen(
                    activity = this@MainActivity,
                    onCameraManagerReady = { manager -> cameraManager = manager },
                    onSpeechManagerReady = { resultCallback, statusCallback ->
                        speechResultCallback = resultCallback
                        speechStatusCallback = statusCallback
                    }
                )
            }
        }

        // 권한 확인 후 매니저 초기화
        checkAndRequestPermissions()
    }

    private fun initializeManagers() {
        // 1. 위치 매니저 초기화
        locationManager = LocationManager(this)

        // 2. WebSocket 매니저 초기화
        webSocketManager = WebSocketManager(this)

        // 3. 음성 인식 매니저 초기화
        speechManager = SpeechManager(this, textToSpeech) { result ->
            handleSpeechResult(result)
        }

        // 4. 서버 연결 시작
        connectToServer()

        // 5. 3초 후 자동 음성 인식 시작
        Handler(mainLooper).postDelayed({
            startSpeechRecognitionFlow()
        }, 3000)
    }

    private fun connectToServer() {
        Log.d(TAG, "🔌 서버 연결 시작")

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
                // 서버 응답을 TTS로 출력 (비동기 처리)
                runOnUiThread {
                    if (::textToSpeech.isInitialized) {
                        textToSpeech.speak(message, TextToSpeech.QUEUE_ADD, null, "server_response")
                        Log.d(TAG, "🔊 서버 응답 TTS: $message")
                    }
                }
            }
        )
    }

    private fun startSpeechRecognitionFlow() {
        Log.d(TAG, "🎤 음성 인식 플로우 시작")

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
        Log.d(TAG, "🎤 음성 인식 완료: $speechResult")

        speechResultCallback?.invoke(speechResult)
        speechStatusCallback?.invoke(false)
        speechCompleted = true

        // TTS로 인식 결과 안내
        if (::textToSpeech.isInitialized) {
            textToSpeech.speak("인식된 음성: $speechResult", TextToSpeech.QUEUE_FLUSH, null, "speech_result")
        }

        // 버스 번호 추출
        val busNumber = extractBusNumber(speechResult)
        Log.d(TAG, "🚌 추출된 버스 번호: $busNumber")

        // 위치 정보 수집 및 서버 전송
        sendLocationToServer(busNumber)

        // 비동기 서비스 시작
        startAsyncServices()
    }

    private fun sendLocationToServer(busNumber: String) {
        val location = locationManager.getLocationForServer()
        val lat = location?.first ?: 37.497928      // 기본값: 강남역
        val lng = location?.second ?: 127.027583

        Log.d(TAG, "📍 위치 정보 서버 전송: 버스 $busNumber, 위도 $lat, 경도 $lng")

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
    }

    private fun startAsyncServices() {
        Log.d(TAG, "🚀 비동기 서비스 시작")

        // 1. 카메라 객체 감지 시작 (독립적인 스레드, 3초 주기)
        startAsyncCameraService()

        // 2. 서버 모니터링은 이미 활성화됨 (30초 주기는 서버에서 자동 처리)
        Log.d(TAG, "📡 서버 모니터링 활성화됨 (30초 주기)")
    }

    private fun startAsyncCameraService() {
        if (::cameraManager.isInitialized && speechCompleted && !isCameraActive) {
            cameraManager.startCamera()
            cameraManager.startAsyncObjectDetection()  // 비동기 객체 감지 시작
            isCameraActive = true
            Log.d(TAG, "🔍 비동기 카메라 서비스 시작 (3초 주기)")
        }
    }

    private fun extractBusNumber(speechText: String): String {
        val cleanedText = speechText.replace(",", "").replace("，", "")

        val patterns = listOf(
            Regex("""(\d{1,4})번"""),
            Regex("""(\d{1,4})\s*번"""),
            Regex("""버스\s*(\d{1,4})"""),
            Regex("""(\d{1,4})\s*버스"""),
            Regex("""\b(\d{1,4})\b""")
        )

        for (pattern in patterns) {
            val match = pattern.find(cleanedText)
            if (match != null) {
                val busNumber = match.groupValues[1]
                if (busNumber.length >= 1 && busNumber.length <= 4 && busNumber.toIntOrNull() != null) {
                    return busNumber
                }
            }
        }

        return "0000"
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech.setLanguage(Locale.KOREAN)

            when (result) {
                TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> {
                    Log.e(TAG, "한국어가 지원되지 않습니다")
                    Toast.makeText(this, "한국어 TTS가 지원되지 않습니다", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    Log.d(TAG, "TTS 한국어 설정 완료")
                    Toast.makeText(this, "음성 인식이 준비되었습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Log.e(TAG, "TTS 초기화 실패")
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
        val permissionsNeeded = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsNeeded.isNotEmpty()) {
            permissionLauncher.launch(permissionsNeeded.toTypedArray())
        } else {
            initializeManagers()
        }
    }

    override fun onResume() {
        super.onResume()

        // 위치 추적 시작 (아직 서버로 전송하지 않은 경우에만)
        if (::locationManager.isInitialized && !locationSent) {
            locationManager.startLocationUpdates { lat, lng ->
                Log.d(TAG, "📍 위치 업데이트: 위도 $lat, 경도 $lng")
            }
        }

        // 음성 인식 완료 후 카메라 서비스 재시작
        if (speechCompleted && ::cameraManager.isInitialized && !isCameraActive) {
            startAsyncCameraService()
        }
    }

    override fun onPause() {
        super.onPause()

        if (::cameraManager.isInitialized && isCameraActive) {
            cameraManager.pauseDetection()
            Log.d(TAG, "🔍 카메라 감지 일시정지")
        }
    }

    override fun onDestroy() {
        if (::textToSpeech.isInitialized) {
            textToSpeech.shutdown()
        }
        if (::speechManager.isInitialized) {
            speechManager.cleanup()
        }
        if (::cameraManager.isInitialized) {
            cameraManager.cleanup()
        }
        if (::locationManager.isInitialized) {
            locationManager.cleanup()
        }
        if (::webSocketManager.isInitialized) {
            webSocketManager.cleanup()
        }
        super.onDestroy()
    }
}