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
    // ... 다른 콜백들
    private var ocrResultCallback: ((String) -> Unit)? = null // MainScreen으로 OCR 결과 전달


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

        // WebSocketManager 초기화 (Context 전달)
        webSocketManager = WebSocketManager(this)

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

            // Socket.IO로 데이터 전송 (이미 연결되어 있음)
            sendBusLocationToServer(result)

            // 음성 인식 완료 후 위치 추적 중지로 배터리 절약
            if (::locationManager.isInitialized) {
                locationManager.stopLocationUpdates()
                Log.d(TAG, "음성 인식 완료 - 위치 추적 중지")
            }

            // 🚌 음성 인식 완료 후 카메라 및 객체 감지 시작
            if (::cameraManager.isInitialized) {
                cameraManager.startCamera()
                Log.d(TAG, "🔍 음성 인식 완료 - 객체 감지 시작")
            }
        }

        // 🚀 앱 시작과 동시에 Socket.IO 서버 연결
        connectToSocketIOServer()

        // 3초 후 자동 음성 인식 시작
        Handler(mainLooper).postDelayed({
            startAutoSpeechRecognition()
        }, 3000)
    }

    // 🚀 Socket.IO 서버 연결
    private fun connectToSocketIOServer() {
        Log.d(TAG, "🔌 앱 시작 - Socket.IO 서버 연결 시작")

        webSocketManager.connect(
            onStatusChanged = { isConnected ->
                runOnUiThread {
                    if (isConnected) {
                        Log.d(TAG, "✅ Socket.IO 서버 연결 성공!")
                        Toast.makeText(this@MainActivity, "서버 연결 완료", Toast.LENGTH_SHORT).show()

                        // 파일 저장 테스트
                        testFileSaving()
                    } else {
                        Log.e(TAG, "❌ Socket.IO 서버 연결 실패")
                        Toast.makeText(this@MainActivity, "서버 연결 실패", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onVoiceOutput = { message ->
                // UI 스레드에서 음성 출력
                runOnUiThread {
                    if (::textToSpeech.isInitialized) {
                        textToSpeech.speak(message, TextToSpeech.QUEUE_FLUSH, null, "bus_info")
                        Log.d(TAG, "🔊 TTS 실행: $message")
                    }
                }
            }
        )
    }

    // 파일 저장 테스트
    private fun testFileSaving() {
        Log.d(TAG, "📁 파일 저장 테스트 시작")
        val content = webSocketManager.getResultFileContent()
        if (content != null) {
            Log.d(TAG, "📁 기존 파일 내용:\n$content")
        } else {
            Log.d(TAG, "📁 기존 파일 없음 또는 빈 파일")
        }
    }

    private fun startAutoSpeechRecognition() {
        Log.d(TAG, "자동 음성 인식 시작 시도 - speechCompleted: $speechCompleted")

        if (!speechCompleted && ::speechManager.isInitialized) {
            // 권한 재확인
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {

                speechStatusCallback?.invoke(true)
                speechManager.startSpeechRecognition()
                Log.d(TAG, "음성 인식 시작됨")
            } else {
                Log.e(TAG, "음성 인식 시작 실패 - 마이크 권한 없음")
                Toast.makeText(this, "마이크 권한을 확인해주세요", Toast.LENGTH_LONG).show()
            }
        } else {
            Log.w(TAG, "음성 인식 시작 불가 - speechCompleted: $speechCompleted, speechManager initialized: ${::speechManager.isInitialized}")
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

    // Socket.IO로 버스 위치 정보 전송 - 연결 시도 제거, 데이터만 전송
    private fun sendBusLocationToServer(speechResult: String) {
        val location = locationManager.getLocationForServer()
        val lat = location?.first ?: 37.497928      // 기본값: 강남역 위도
        val lng = location?.second ?: 127.027583    // 기본값: 강남역 경도

        val busNumber = extractBusNumber(speechResult)

        Log.d(TAG, "🚀 서버 전송 시작 - 버스: $busNumber, 위도: $lat, 경도: $lng")
        Log.d(TAG, "🔍 Socket.IO 연결 상태: ${webSocketManager.isConnected}")

        // 연결되어 있으면 바로 전송
        if (webSocketManager.isConnected) {
            Log.d(TAG, "✅ 이미 연결됨 - 바로 전송")
            sendBusLocationData(lat, lng, busNumber)
        } else {
            Log.w(TAG, "⚠️ 소켓이 연결되지 않음 - 전송 실패")
            Toast.makeText(this, "서버 연결 상태를 확인해주세요", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendBusLocationData(latitude: Double, longitude: Double, busNumber: String) {
        Log.d(TAG, "📤 sendBusLocationData 호출됨 - 버스: $busNumber")

        try {
            val success = webSocketManager.sendBusLocationRequest(
                latitude = latitude,        // GPS 위도
                longitude = longitude,      // GPS 경도
                busNumber = busNumber,      // 음성인식된 버스 번호
                interval = 30              // 30초 간격
            )

            Log.d(TAG, "📤 전송 결과: $success")

            // UI 스레드에서 Toast 실행
            runOnUiThread {
                if (success) {
                    Log.d(TAG, "✅ 버스 위치 정보 전송 성공")
                    Log.d(TAG, "✅ 전송된 데이터: 버스 $busNumber, 위치 ($latitude, $longitude)")
                    Toast.makeText(this@MainActivity, "✅ 버스 $busNumber 정보 전송 완료", Toast.LENGTH_SHORT).show()
                } else {
                    Log.e(TAG, "❌ 버스 위치 정보 전송 실패")
                    Toast.makeText(this@MainActivity, "❌ 서버 전송 실패", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 서버 전송 중 예외 발생", e)
            runOnUiThread {
                Toast.makeText(this@MainActivity, "전송 오류: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 음성에서 버스 번호 추출 (쉼표 처리 포함)
    private fun extractBusNumber(speechText: String): String {
        // 1. 먼저 쉼표 제거 (구글 음성인식이 "9201"을 "9,201"로 인식하는 경우 대응)
        val cleanedText = speechText.replace(",", "").replace("，", "") // 영문/한글 쉼표 모두 제거

        Log.d(TAG, "쉼표 제거된 텍스트: $cleanedText (원본: $speechText)")

        //버스 번호 판별(번호인지 아닌지)
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

                // 버스 번호 유효성 검사 (1-4자리 숫자)
                if (busNumber.length >= 1 && busNumber.length <= 4 && busNumber.toIntOrNull() != null) {
                    Log.d(TAG, "유효한 버스 번호 추출: $busNumber (정제된 텍스트: $cleanedText)")
                    return busNumber
                }
            }
        }

        Log.d(TAG, "버스 번호를 찾을 수 없음: $cleanedText")
        return "0000" // 유효한 버스 번호가 없으면 기본값 반환
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

        // 🔍 카메라는 음성 인식 완료 후에만 시작 (객체 감지 포함)
        if (speechCompleted && ::cameraManager.isInitialized) {
            cameraManager.startCamera()
            Log.d(TAG, "🔍 음성 인식 완료됨 - 카메라 및 객체 감지 시작")
        }
    }

    override fun onPause() {
        super.onPause()
        if (::cameraManager.isInitialized) {
            cameraManager.stopCamera()
            Log.d(TAG, "🔍 카메라 및 객체 감지 중지")
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