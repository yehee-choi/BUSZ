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
    private lateinit var textToSpeech: TextToSpeech
    private lateinit var locationManager: LocationManager
    private lateinit var webSocketManager: WebSocketManager

    // 음성 인식 결과를 UI에 전달하기 위한 상태
    private var speechResultCallback: ((String) -> Unit)? = null
    private var speechStatusCallback: ((Boolean) -> Unit)? = null

    // 음성 인식 완료 상태
    private var speechCompleted = false

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
            // 권한이 허용된 후 매니저들 초기화
            initializeManagers()
        } else {
            Toast.makeText(this, "카메라와 마이크 권한이 필요합니다.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // TTS 초기화
        textToSpeech = TextToSpeech(this, this)

        // LocationManager 초기화
        locationManager = LocationManager(this)

        // WebSocketManager 초기화 (Context 없이)
        webSocketManager = WebSocketManager()

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
        // SpeechManager는 권한이 허용된 후에만 초기화
        speechManager = SpeechManager(this, textToSpeech) { result ->
            speechResultCallback?.invoke(result)
            speechStatusCallback?.invoke(false)
            speechCompleted = true // 음성 인식 완료

            if (::textToSpeech.isInitialized) {
                val languageResult = textToSpeech.setLanguage(Locale.KOREAN)
                if (languageResult != TextToSpeech.LANG_MISSING_DATA &&
                    languageResult != TextToSpeech.LANG_NOT_SUPPORTED) {
                    textToSpeech.speak("인식된 음성: $result", TextToSpeech.QUEUE_FLUSH, null, "")
                }
            }

            // 웹소켓으로 데이터 전송
            sendBusLocationToServer(result)

            // 음성 인식 완료 후 위치 추적 중지로 배터리 절약
            if (::locationManager.isInitialized) {
                locationManager.stopLocationUpdates()
                Log.d(TAG, "음성 인식 완료 - 위치 추적 중지")
            }
        }

        // 3초 후 자동 음성 인식 시작
        Handler(mainLooper).postDelayed({
            startAutoSpeechRecognition()
        }, 3000)
    }

    private fun startAutoSpeechRecognition() {
        if (!speechCompleted && ::speechManager.isInitialized) {
            speechStatusCallback?.invoke(true)
            speechManager.startSpeechRecognition()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // 새로운 방식으로 언어 설정
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
            speechStatusCallback?.invoke(true) // 듣기 상태 시작
            speechManager.startSpeechRecognition()

            // 수동 음성 인식 시에는 위치 정보만 로그
            logCurrentLocationForServer()
        } else {
            Toast.makeText(this, "음성 인식이 이미 완료되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    // OCR 결과를 UI로 전달하는 메서드 - 제거됨
    // fun onOCRResult(result: String) {
    //     ocrResultCallback?.invoke(result)
    // }

    // 서버 전송용 위치 정보 로그
    private fun logCurrentLocationForServer() {
        if (::locationManager.isInitialized) {
            val location = locationManager.getLocationForServer()
            if (location != null) {
                Log.d(TAG, "서버 전송용 위치 - 위도: ${location.first}, 경도: ${location.second}")
            } else {
                Log.w(TAG, "위치 정보를 사용할 수 없습니다")
            }
        }
    }

    // 웹소켓으로 버스 위치 정보 전송
    private fun sendBusLocationToServer(speechResult: String) {
        val location = locationManager.getLocationForServer()
        val lat = location?.first ?: 37.4219983
        val lng = location?.second ?: -122.084

        val busNumber = extractBusNumber(speechResult)

        Log.d(TAG, "전송할 데이터 - 버스: $busNumber, 위도: $lat, 경도: $lng")
        Toast.makeText(this, "버스 $busNumber 정보 처리 완료", Toast.LENGTH_SHORT).show()
    }

    // 음성에서 버스 번호 추출 (숫자 패턴 찾기)
    private fun extractBusNumber(speechText: String): String {
        val patterns = listOf(
            Regex("""(\d{3,4})번"""),           // "123번", "1234번"
            Regex("""(\d{3,4})\s*번"""),        // "123 번"
            Regex("""버스\s*(\d{3,4})"""),      // "버스 123"
            Regex("""(\d{3,4})\s*버스"""),      // "123 버스"
            Regex("""(\d{3,4})""")              // 단순 숫자만
        )

        for (pattern in patterns) {
            val match = pattern.find(speechText)
            if (match != null) {
                val busNumber = match.groupValues[1]
                Log.d(TAG, "추출된 버스 번호: $busNumber (원본: $speechText)")
                return busNumber
            }
        }

        return speechText // 숫자가 없으면 원본 텍스트 반환
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

        // 위치 추적만 먼저 시작 (카메라는 음성 인식 완료 후)
        if (::locationManager.isInitialized) {
            locationManager.startLocationUpdates { lat, lng ->
                Log.d(TAG, "위치 업데이트됨 - 위도: $lat, 경도: $lng")
            }
        }

        // 카메라는 음성 인식 완료 후에만 시작
        if (speechCompleted && ::cameraManager.isInitialized) {
            cameraManager.startCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::cameraManager.isInitialized) {
            cameraManager.stopCamera()
        }
        if (::locationManager.isInitialized) {
            locationManager.stopLocationUpdates()
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