package com.example.cv2_project1

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.speech.tts.TextToSpeech
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.*

data class Detection(
    val boundingBox: RectF,
    val label: String,
    val confidence: Float
)

class ObjectDetectionManager(
    private val context: Context,
    private val textToSpeech: TextToSpeech,
    private val onDetectionResult: ((List<Detection>) -> Unit)? = null
) {
    private val TAG = "ObjectDetectionManager"

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()

    // OCR 텍스트 인식기 (한국어 지원)
    private val textRecognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

    // 모델 설정
    private val INPUT_SIZE = 640  // COCO
    private val NUM_CLASSES = 80  // COCO 데이터셋 클래스 수
    private val NUM_DETECTIONS = 6300  // 예측 결과 수
    private val CONFIDENCE_THRESHOLD = 0.5f

    // 바운딩 박스 그리기용 Paint
    private val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 3f
        textSize = 40f
    }

    // 마지막 버스 번호 발표 시간 (중복 방지)
    private var lastBusAnnouncementTime = 0L
    private val BUS_ANNOUNCEMENT_INTERVAL = 5000L // 5초 간격

    init {
        loadModel()
        loadLabels()
    }
    private fun loadModel() {
        try {
            val modelFile = loadModelFile("models/best_full_integer_quant.tflite")
            interpreter = Interpreter(modelFile)
            Log.d(TAG, "✅ TensorFlow Lite 모델 로드 성공")
        } catch (e: Exception) {
            Log.e(TAG, "💥 모델 로드 실패", e)
        }
    }
    private fun loadModelFile(modelPath: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }
    private fun loadLabels() {
        try {
            labels = context.assets.open("models/coco_labels.txt").bufferedReader().readLines()
            Log.d(TAG, "✅ 라벨 파일 로드 성공: ${labels.size}개 클래스")
        } catch (e: Exception) {
            Log.e(TAG, "💥 라벨 파일 로드 실패, 기본 라벨 사용", e)
            // COCO 데이터셋 기본 라벨 (일부)
            labels = listOf(
                "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
                "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
                "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra",
                "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee"
                // ... 더 많은 라벨들
            )
        }
    }

    fun detectObjects(bitmap: Bitmap): List<Detection> {
        val interpreter = this.interpreter ?: return emptyList()

        try {
            // 1. 이미지 전처리
            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, false)
            val inputBuffer = convertBitmapToByteBuffer(resizedBitmap)

            // 2. 모델 추론
            val outputArray = Array(1) { Array(NUM_DETECTIONS) { FloatArray(NUM_CLASSES + 5) } }
            interpreter.run(inputBuffer, outputArray)

            // 3. 결과 후처리
            val detections = parseModelOutput(outputArray[0], bitmap.width, bitmap.height)

            // 4. 버스 감지 시 OCR 실행
            processBusDetections(bitmap, detections)

            // 5. 일반 객체 음성 출력 (버스 제외)
            announceNonBusObjects(detections)

            // 6. 콜백 호출
            onDetectionResult?.invoke(detections)

            Log.d(TAG, "🔍 객체 감지 완료: ${detections.size}개 객체 발견")
            return detections

        } catch (e: Exception) {
            Log.e(TAG, "💥 객체 감지 실패", e)
            return emptyList()
        }
    }

    private fun processBusDetections(originalBitmap: Bitmap, detections: List<Detection>) {
        val currentTime = System.currentTimeMillis()

        // 중복 발표 방지
        if (currentTime - lastBusAnnouncementTime < BUS_ANNOUNCEMENT_INTERVAL) {
            return
        }

        val busDetections = detections.filter { it.label == "bus" }

        if (busDetections.isNotEmpty()) {
            Log.d(TAG, "🚌 버스 감지됨! OCR로 번호 추출 시작")

            for (busDetection in busDetections) {
                // 버스 영역만 크롭
                val croppedBitmap = cropBitmapToBoundingBox(originalBitmap, busDetection.boundingBox)

                if (croppedBitmap != null) {
                    // OCR로 텍스트 추출
                    extractBusNumber(croppedBitmap)
                    lastBusAnnouncementTime = currentTime
                    break // 첫 번째 버스만 처리
                }
            }
        }
    }

    private fun cropBitmapToBoundingBox(bitmap: Bitmap, boundingBox: RectF): Bitmap? {
        return try {
            val left = maxOf(0, boundingBox.left.toInt())
            val top = maxOf(0, boundingBox.top.toInt())
            val width = minOf(bitmap.width - left, (boundingBox.width()).toInt())
            val height = minOf(bitmap.height - top, (boundingBox.height()).toInt())

            if (width > 0 && height > 0) {
                Bitmap.createBitmap(bitmap, left, top, width, height)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "💥 이미지 크롭 실패", e)
            null
        }
    }

    private fun extractBusNumber(croppedBitmap: Bitmap) {
        val image = InputImage.fromBitmap(croppedBitmap, 0)

        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                val extractedText = visionText.text
                Log.d(TAG, "📝 OCR 추출 텍스트: $extractedText")

                val busNumber = parseBusNumberFromText(extractedText)
                if (busNumber != null) {
                    val message = "${busNumber}번 버스가 앞에 있습니다"
                    textToSpeech.speak(message, TextToSpeech.QUEUE_ADD, null, "bus_number_ocr")
                    Log.d(TAG, "🔊 버스 번호 음성: $message")
                } else {
                    Log.d(TAG, "❌ 버스 번호를 인식할 수 없습니다")
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "💥 OCR 실패", e)
            }
    }

    private fun parseBusNumberFromText(text: String): String? {
        if (text.isBlank()) return null

        // 버스 번호 추출 패턴들
        val patterns = listOf(
            Regex("""\b(\d{1,4})\b"""),           // 1-4자리 숫자
            Regex("""(\d{1,4})\s*번"""),          // 숫자 + 번
            Regex("""번호\s*(\d{1,4})"""),        // 번호 + 숫자
            Regex("""(\d{1,4})\s*호선"""),        // 숫자 + 호선
            Regex("""(\d{1,4})\s*라인""")         // 숫자 + 라인
        )

        for (pattern in patterns) {
            val matches = pattern.findAll(text)
            for (match in matches) {
                val number = match.groupValues[1]
                val numValue = number.toIntOrNull()

                // 버스 번호 유효성 검사 (1~9999)
                if (numValue != null && numValue in 1..9999) {
                    Log.d(TAG, "✅ 버스 번호 발견: $number (원본: $text)")
                    return number
                }
            }
        }

        Log.d(TAG, "❌ 유효한 버스 번호 없음: $text")
        return null
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var pixel = 0
        for (i in 0 until INPUT_SIZE) {
            for (j in 0 until INPUT_SIZE) {
                val pixelValue = intValues[pixel++]

                // 정규화 (0~255 -> 0~1)
                byteBuffer.putFloat(((pixelValue shr 16) and 0xFF) / 255.0f) // R
                byteBuffer.putFloat(((pixelValue shr 8) and 0xFF) / 255.0f)  // G
                byteBuffer.putFloat((pixelValue and 0xFF) / 255.0f)          // B
            }
        }

        return byteBuffer
    }

    private fun parseModelOutput(output: Array<FloatArray>, originalWidth: Int, originalHeight: Int): List<Detection> {
        val detections = mutableListOf<Detection>()

        for (i in output.indices) {
            val detection = output[i]
            val confidence = detection[4]

            if (confidence > CONFIDENCE_THRESHOLD) {
                // 바운딩 박스 좌표 (정규화된 값을 원본 크기로 변환)
                val centerX = detection[0] * originalWidth
                val centerY = detection[1] * originalHeight
                val width = detection[2] * originalWidth
                val height = detection[3] * originalHeight

                val left = centerX - width / 2
                val top = centerY - height / 2
                val right = centerX + width / 2
                val bottom = centerY + height / 2

                // 클래스 확률에서 최고 점수 클래스 찾기
                var maxClassScore = 0f
                var maxClassIndex = 0
                for (j in 5 until detection.size) {
                    val classScore = detection[j]
                    if (classScore > maxClassScore) {
                        maxClassScore = classScore
                        maxClassIndex = j - 5
                    }
                }

                val finalConfidence = confidence * maxClassScore
                if (finalConfidence > CONFIDENCE_THRESHOLD && maxClassIndex < labels.size) {
                    val boundingBox = RectF(left, top, right, bottom)
                    val label = labels[maxClassIndex]

                    detections.add(Detection(boundingBox, label, finalConfidence))
                }
            }
        }

        // NMS (Non-Maximum Suppression) 적용
        return applyNMS(detections)
    }

    private fun applyNMS(detections: List<Detection>): List<Detection> {
        if (detections.isEmpty()) return emptyList()

        val sortedDetections = detections.sortedByDescending { it.confidence }
        val finalDetections = mutableListOf<Detection>()

        for (detection in sortedDetections) {
            var shouldAdd = true
            for (finalDetection in finalDetections) {
                if (calculateIoU(detection.boundingBox, finalDetection.boundingBox) > 0.5f) {
                    shouldAdd = false
                    break
                }
            }
            if (shouldAdd) {
                finalDetections.add(detection)
            }
        }

        return finalDetections
    }

    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val intersectionArea = maxOf(0f, minOf(box1.right, box2.right) - maxOf(box1.left, box2.left)) *
                maxOf(0f, minOf(box1.bottom, box2.bottom) - maxOf(box1.top, box2.top))

        val box1Area = (box1.right - box1.left) * (box1.bottom - box1.top)
        val box2Area = (box2.right - box2.left) * (box2.bottom - box2.top)

        val unionArea = box1Area + box2Area - intersectionArea

        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }

    private fun announceNonBusObjects(detections: List<Detection>) {
        val nonBusDetections = detections.filter { it.label != "bus" }

        if (nonBusDetections.isEmpty()) {
            return // 버스 외 객체가 없으면 음성 출력 안 함
        }

        // 신뢰도가 높은 객체들만 선택 (상위 2개, 버스 제외)
        val topDetections = nonBusDetections.sortedByDescending { it.confidence }.take(2)

        val objectNames = topDetections.map { detection ->
            when (detection.label) {
                "person" -> "사람"
                "car" -> "자동차"
                "truck" -> "트럭"
                "bicycle" -> "자전거"
                "motorcycle" -> "오토바이"
                "traffic light" -> "신호등"
                "stop sign" -> "정지 표지판"
                "bench" -> "벤치"
                "chair" -> "의자"
                "dog" -> "개"
                "cat" -> "고양이"
                else -> detection.label
            }
        }.distinct()

        if (objectNames.isNotEmpty()) {
            val message = if (objectNames.size == 1) {
                "앞에 ${objectNames[0]}이 있습니다"
            } else {
                "앞에 ${objectNames.joinToString(", ")}이 있습니다"
            }

            textToSpeech.speak(message, TextToSpeech.QUEUE_ADD, null, "object_detection")
            Log.d(TAG, "🔊 일반 객체 음성: $message")
        }
    }

    fun drawDetections(bitmap: Bitmap, detections: List<Detection>): Bitmap {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)

        for (detection in detections) {
            // 바운딩 박스 그리기
            canvas.drawRect(detection.boundingBox, paint)

            // 라벨과 신뢰도 텍스트 그리기
            val text = "${detection.label} (${String.format("%.2f", detection.confidence)})"
            canvas.drawText(text, detection.boundingBox.left, detection.boundingBox.top - 10, paint)
        }

        return mutableBitmap
    }

    fun cleanup() {
        interpreter?.close()
        interpreter = null
        textRecognizer.close()
        Log.d(TAG, "🧹 ObjectDetectionManager 정리 완료")
    }
}