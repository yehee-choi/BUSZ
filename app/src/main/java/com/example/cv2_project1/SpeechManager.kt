// SpeechManager.kt

package com.example.cv2_project1

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class SpeechManager(
    private val activity: ComponentActivity,
    private val textToSpeech: TextToSpeech,
    private val onSpeechResult: (String) -> Unit
) {

    private val TAG = "SpeechManager"
    private var speechRecognizer: SpeechRecognizer? = null

    private val speechRecognitionLauncher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val results = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                results?.firstOrNull()?.let { spokenText ->
                    Log.d(TAG, "음성 인식 결과: $spokenText")
                    onSpeechResult(spokenText)
                }
            } else {
                Log.d(TAG, "음성 인식이 취소되었습니다")
                textToSpeech.speak("음성 인식이 취소되었습니다", TextToSpeech.QUEUE_FLUSH, null, "")
            }
        }

    init {
        initializeSpeechRecognizer()
    }

    private fun initializeSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(activity)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(activity)
            if (isEmulator()) {
                Toast.makeText(activity, "에뮬레이터에서 실행 중입니다. 음성 인식이 제한될 수 있습니다.", Toast.LENGTH_SHORT).show()
            }
        } else {
            val message = if (isEmulator()) {
                "에뮬레이터에서 음성 인식을 사용하려면:\n1. AVD Manager에서 마이크 설정 활성화\n2. Google Play Services 설치\n3. 호스트 마이크 권한 허용"
            } else {
                "음성 인식 서비스를 사용할 수 없습니다."
            }
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        }
    }

    public fun startSpeechRecognition() {
        if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR")
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "무엇을 찾고 계신가요?")
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }

            // Google 음성 인식 서비스가 있는지 확인
            if (intent.resolveActivity(activity.packageManager) != null) {
                try {
                    speechRecognitionLauncher.launch(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "음성 인식 시작 실패", e)
                    textToSpeech.speak("음성 인식을 시작할 수 없습니다", TextToSpeech.QUEUE_FLUSH, null, "")
                }
            } else {
                val errorMessage = if (isEmulator()) {
                    "에뮬레이터에서 음성 인식 서비스를 찾을 수 없습니다. AVD 설정에서 마이크를 활성화하고 Google Play Services를 설치해주세요."
                } else {
                    "음성 인식 서비스를 찾을 수 없습니다. Google 앱이 설치되어 있는지 확인해주세요."
                }
                Toast.makeText(activity, errorMessage, Toast.LENGTH_LONG).show()
                textToSpeech.speak("음성 인식 서비스를 찾을 수 없습니다", TextToSpeech.QUEUE_FLUSH, null, "")
            }
        } else {
            Toast.makeText(activity, "마이크 권한이 필요합니다", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || "QC_Reference_Phone" == Build.BOARD && !"Xiaomi".equals(Build.MANUFACTURER, ignoreCase = true)
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.HOST.startsWith("Build")
                || Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")
                || "google_sdk" == Build.PRODUCT)
    }

    fun cleanup() {
        speechRecognizer?.destroy()
    }
}