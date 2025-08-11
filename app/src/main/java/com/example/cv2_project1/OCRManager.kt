

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

class OCRManager {

    private val TAG = "OCRManager"

    // 한국어 텍스트 인식기 초기화
    private val textRecognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

    fun recognizeText(bitmap: Bitmap, onResult: (String) -> Unit, onError: (Exception) -> Unit) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)

            textRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val recognizedText = visionText.text
                    Log.d(TAG, "OCR 인식 결과: $recognizedText")

                    if (recognizedText.isNotEmpty()) {
                        onResult(recognizedText)
                    } else {
                        onResult("텍스트를 찾을 수 없습니다")
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "OCR 인식 실패", e)
                    onError(e)
                }
        } catch (e: Exception) {
            Log.e(TAG, "OCR 처리 중 오류", e)
            onError(e)
        }
    }

    fun cleanup() {
        textRecognizer.close()
    }
}