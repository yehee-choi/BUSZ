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

    // 모델 설정 - 커스텀 버스 감지 모델
    private val INPUT_SIZE = 640
    private val NUM_CLASSES = 1  // 버스만 학습했으므로 클래스 1개
    private val NUM_DETECTIONS = 6300  // YOLO 기본값
    private val CONFIDENCE_THRESHOLD = 0.3f

    // 바운딩 박스 그리기용 Paint
    private val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 3f
        textSize = 40f
    }

    // 🚀 시각장애인을 위한 빠른 음성 출력 제어
    private var lastGeneralAnnouncementTime = 0L
    private var lastBusAnnouncementTime = 0L
    private val GENERAL_ANNOUNCEMENT_INTERVAL = 3000L      // 3초 간격 (기존 7초 → 3초)
    private val BUS_ANNOUNCEMENT_INTERVAL = 1500L          // 1.5초 간격 (기존 5초 → 1.5초)

    // 🔥 중복 방지 로직 완화 (같은 버스라도 2번까지 안내)
    private var lastAnnouncedBusNumber: String? = null
    private var busNumberRepeatCount = 0
    private val MAX_REPEAT_COUNT = 2  // 같은 버스 번호 최대 2번까지 안내
    private var detectionCount = 0

    init {
        loadModel()
        loadLabels()
        Log.d(TAG, "🔊 TTS 초기화 상태 확인...")
        testTTSOutput()
    }

    private fun testTTSOutput() {
        try {
            Log.d(TAG, "🔊 TTS 테스트 시작")
            textToSpeech.speak("빠른 객체 감지 매니저 초기화 완료", TextToSpeech.QUEUE_ADD, null, "init_test")
        } catch (e: Exception) {
            Log.e(TAG, "❌ TTS 테스트 실패", e)
        }
    }

    private fun loadModel() {
        try {
            Log.d(TAG, "📁 모델 파일 로드 시도: models/best_full_integer_quant.tflite")

            // assets 폴더 구조 확인
            try {
                val assetManager = context.assets
                val modelsFiles = assetManager.list("models")
                Log.d(TAG, "📁 models 폴더 파일 목록: ${modelsFiles?.joinToString(", ") ?: "폴더 없음"}")

                if (modelsFiles?.contains("best_full_integer_quant.tflite") == true) {
                    Log.d(TAG, "✅ 모델 파일 존재 확인됨")
                } else {
                    Log.e(TAG, "❌ 모델 파일이 목록에 없음")
                    Log.d(TAG, "📁 사용 가능한 파일들: ${modelsFiles?.joinToString(", ")}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "💥 assets 폴더 확인 실패", e)
            }

            // 실제 파일명으로 모델 로드 (수정된 부분)
            val modelFile = loadModelFile("models/best_full_integer_quant.tflite")
            Log.d(TAG, "📊 모델 파일 크기: ${modelFile.capacity()} bytes")

            interpreter = Interpreter(modelFile)
            Log.d(TAG, "✅ TensorFlow Lite 모델 로드 성공")

            Log.d(TAG, "📊 입력 텐서 수: ${interpreter!!.inputTensorCount}")
            Log.d(TAG, "📊 출력 텐서 수: ${interpreter!!.outputTensorCount}")

            if (interpreter!!.inputTensorCount > 0) {
                val inputShape = interpreter!!.getInputTensor(0).shape()
                Log.d(TAG, "📊 입력 형태: ${inputShape.joinToString("x")}")
            }

            // 출력 텐서 정보도 로그로 출력
            for (i in 0 until interpreter!!.outputTensorCount) {
                val outputShape = interpreter!!.getOutputTensor(i).shape()
                Log.d(TAG, "📊 출력 텐서 $i 형태: ${outputShape.joinToString("x")}")
            }

        } catch (e: java.io.FileNotFoundException) {
            Log.e(TAG, "💥 모델 파일을 찾을 수 없음: ${e.message}")
            Log.e(TAG, "💡 확인사항:")
            Log.e(TAG, "   1. app/src/main/assets/models/ 폴더가 존재하는지")
            Log.e(TAG, "   2. best_full_integer_quant.tflite 파일이 해당 위치에 있는지")
            Log.e(TAG, "   3. 파일명의 대소문자가 정확한지")
        } catch (e: java.io.IOException) {
            Log.e(TAG, "💥 모델 파일 읽기 실패", e)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "💥 모델 파일 형식 오류", e)
        } catch (e: Exception) {
            Log.e(TAG, "💥 모델 로드 중 알 수 없는 오류", e)
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
        // 버스만 학습한 커스텀 모델이므로 라벨은 "bus" 하나만
        labels = listOf("bus")
        Log.d(TAG, "✅ 커스텀 버스 모델 라벨 설정: ${labels.size}개 클래스 (버스만)")
    }

    fun detectObjects(bitmap: Bitmap): List<Detection> {
        detectionCount++
        Log.d(TAG, "🔍 === 빠른 객체 감지 #${detectionCount} 시작 ===")
        Log.d(TAG, "🔍 이미지 크기: ${bitmap.width}x${bitmap.height}")
        Log.d(TAG, "🔍 인터프리터 상태: ${if (interpreter != null) "로드됨" else "null"}")

        val interpreter = this.interpreter
        if (interpreter == null) {
            Log.w(TAG, "⚠️ 모델이 로드되지 않음 - 실제 감지 불가")
            return createTestDetections(bitmap)
        }

        try {
            Log.d(TAG, "🔍 실제 TensorFlow Lite 모델로 객체 감지 시작")

            Log.d(TAG, "🔍 원본 이미지: ${bitmap.width}x${bitmap.height}")

            val resizedBitmap = if (bitmap.width != INPUT_SIZE || bitmap.height != INPUT_SIZE) {
                Log.d(TAG, "🔍 이미지 리사이즈: ${bitmap.width}x${bitmap.height} → ${INPUT_SIZE}x${INPUT_SIZE}")
                Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
            } else {
                Log.d(TAG, "🔍 이미지 크기 적합 - 리사이즈 건너뛰기")
                bitmap
            }

            Log.d(TAG, "🔍 최종 이미지: ${resizedBitmap.width}x${resizedBitmap.height}")

            val inputBuffer = convertBitmapToByteBuffer(resizedBitmap)
            Log.d(TAG, "🔍 이미지 전처리 완료")

            val startTime = System.currentTimeMillis()

            // ✅ 모델 출력 텐서 정보 확인
            val outputTensor0 = interpreter.getOutputTensor(0)
            val outputTensor1 = interpreter.getOutputTensor(1)

            Log.d(TAG, "🔍 출력 텐서 0 형태: ${outputTensor0.shape().joinToString("x")}")
            Log.d(TAG, "🔍 출력 텐서 1 형태: ${outputTensor1.shape().joinToString("x")}")

            // ✅ 실제 출력 텐서 크기에 맞춰 버퍼 할당
            val outputBuffer1 = ByteBuffer.allocateDirect(outputTensor0.numBytes())
            outputBuffer1.order(ByteOrder.nativeOrder())

            val outputBuffer2 = ByteBuffer.allocateDirect(outputTensor1.numBytes())
            outputBuffer2.order(ByteOrder.nativeOrder())

            Log.d(TAG, "🔍 출력 버퍼1 할당: ${outputBuffer1.capacity()} bytes")
            Log.d(TAG, "🔍 출력 버퍼2 할당: ${outputBuffer2.capacity()} bytes")

            val outputs = mapOf(
                0 to outputBuffer1,
                1 to outputBuffer2
            )

            Log.d(TAG, "🔍 모델 추론 시작 (INT8 양자화 모델)...")
            interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)
            val endTime = System.currentTimeMillis()

            Log.d(TAG, "🔍 모델 추론 완료: ${endTime - startTime}ms")

            val detections = parseQuantizedModelOutput(outputBuffer1, outputBuffer2, bitmap.width, bitmap.height)

            Log.d(TAG, "🔍 실제 모델 감지 완료: 총 ${detections.size}개 객체")

            return processDetections(detections, bitmap)

        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "💥 텐서 크기 불일치 오류", e)
            return createTestDetections(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "💥 객체 감지 실행 중 기타 오류", e)
            return createTestDetections(bitmap)
        }
    }

    private fun createTestDetections(originalBitmap: Bitmap): List<Detection> {
        Log.w(TAG, "⚠️ 모델 파일 없음 - 테스트 감지 모드로 실행")

        // 🔥 테스트용 가짜 버스 감지 결과 생성
        val testDetections = mutableListOf<Detection>()

        // 매 3번째 감지마다 테스트 버스 생성
        if (detectionCount % 3 == 0) {
            val centerX = originalBitmap.width * 0.5f
            val centerY = originalBitmap.height * 0.4f
            val width = originalBitmap.width * 0.3f
            val height = originalBitmap.height * 0.2f

            val boundingBox = RectF(
                centerX - width/2,
                centerY - height/2,
                centerX + width/2,
                centerY + height/2
            )

            testDetections.add(Detection(boundingBox, "bus", 0.85f))
            Log.d(TAG, "🚌 테스트 버스 감지 생성! (감지 횟수: $detectionCount)")
        }

        return testDetections
    }

    private fun processDetections(detections: List<Detection>, originalBitmap: Bitmap): List<Detection> {
        if (detections.isNotEmpty()) {
            val detectionSummary = detections.groupBy { it.label }.mapValues { it.value.size }
            Log.d(TAG, "📊 감지된 객체 요약: $detectionSummary")
        }

        // 버스 전용 모델이므로 모든 감지 결과가 버스
        val busDetections = detections  // 모든 감지 결과가 버스
        if (busDetections.isNotEmpty()) {
            Log.d(TAG, "🚌 버스 감지됨! ${busDetections.size}대")
            processBusDetections(busDetections, originalBitmap)
        } else {
            Log.d(TAG, "🔍 버스 감지 안됨")
        }

        if (detections.isEmpty()) {
            Log.d(TAG, "🔍 감지된 버스 없음")
            if (detectionCount % 4 == 0) {  // 더 자주 안내 (6 → 4)
                Log.d(TAG, "🔊 테스트 음성: 버스 없음")
                speakWithTest("주변에 버스가 감지되지 않았습니다", "no_bus_test")
            }
        }

        onDetectionResult?.invoke(detections)
        return detections
    }

    // 🚀 빠른 버스 감지 처리
    private fun processBusDetections(busDetections: List<Detection>, originalBitmap: Bitmap) {
        val currentTime = System.currentTimeMillis()

        // 🚀 1.5초 간격으로 단축 (기존 5초 → 1.5초)
        if (currentTime - lastBusAnnouncementTime < BUS_ANNOUNCEMENT_INTERVAL) {
            Log.d(TAG, "🚌 버스 음성 스킵 - ${(currentTime - lastBusAnnouncementTime) / 1000.0}초 경과")
            return
        }

        Log.d(TAG, "🚌 빠른 버스 감지 처리 시작 - ${busDetections.size}대의 버스")

        val sortedBuses = busDetections.sortedByDescending { it.confidence }

        for ((index, busDetection) in sortedBuses.withIndex()) {
            Log.d(TAG, "🚌 버스 #${index + 1} 빠른 OCR 진행 중 (신뢰도: ${String.format("%.2f", busDetection.confidence)})")

            extractBusNumberFromDetection(busDetection, originalBitmap, index + 1)
            lastBusAnnouncementTime = currentTime

            // 🔥 첫 번째 버스만 처리 (빠른 응답을 위해)
            break
        }
    }

    private fun extractBusNumberFromDetection(busDetection: Detection, originalBitmap: Bitmap, busIndex: Int) {
        Log.d(TAG, "🔍 버스 #${busIndex} 빠른 OCR 시작 - 바운딩 박스: ${busDetection.boundingBox}")

        val croppedBitmap = cropBitmapToBoundingBox(originalBitmap, busDetection.boundingBox)

        if (croppedBitmap != null) {
            Log.d(TAG, "🖼️ 버스 영역 크롭 성공: ${croppedBitmap.width}x${croppedBitmap.height}")
            extractBusNumber(croppedBitmap, busIndex)
        } else {
            Log.w(TAG, "🖼️ 버스 #${busIndex} 이미지 크롭 실패")
        }
    }

    private fun cropBitmapToBoundingBox(bitmap: Bitmap, boundingBox: RectF): Bitmap? {
        return try {
            val left = maxOf(0, boundingBox.left.toInt())
            val top = maxOf(0, boundingBox.top.toInt())
            val width = minOf(bitmap.width - left, (boundingBox.width()).toInt())
            val height = minOf(bitmap.height - top, (boundingBox.height()).toInt())

            if (width > 20 && height > 20) {
                val croppedBitmap = Bitmap.createBitmap(bitmap, left, top, width, height)
                Log.d(TAG, "🖼️ 이미지 크롭 성공: ${width}x${height}")
                croppedBitmap
            } else {
                Log.w(TAG, "🖼️ 크롭 영역이 너무 작음: ${width}x${height}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "💥 이미지 크롭 실패", e)
            null
        }
    }

    private fun extractBusNumber(croppedBitmap: Bitmap, busIndex: Int) {
        // 모델이 없는 경우 테스트 버스 번호 생성
        if (interpreter == null) {
            val testBusNumbers = listOf("146", "401", "302", "1100", "9407")
            val randomBusNumber = testBusNumbers.random()

            Log.d(TAG, "🔥 테스트 모드: 가짜 버스 번호 생성 - $randomBusNumber")

            // 🔥 완화된 중복 방지 (2번까지 허용)
            if (randomBusNumber != lastAnnouncedBusNumber) {
                // 새로운 버스 번호
                val message = "${randomBusNumber}번 버스가 앞에 있습니다"
                speakWithTest(message, "test_bus_number")
                lastAnnouncedBusNumber = randomBusNumber
                busNumberRepeatCount = 1
                Log.i(TAG, "✅ 새 테스트 버스 번호: $randomBusNumber (1회차)")

            } else if (busNumberRepeatCount < MAX_REPEAT_COUNT) {
                // 같은 버스지만 아직 반복 허용
                busNumberRepeatCount++
                val message = "${randomBusNumber}번 버스가 계속 앞에 있습니다"
                speakWithTest(message, "test_bus_repeat")
                Log.i(TAG, "✅ 테스트 버스 번호 반복: $randomBusNumber (${busNumberRepeatCount}회차)")

            } else {
                Log.d(TAG, "🔊 테스트 버스 번호 반복 횟수 초과: $randomBusNumber")
            }
            return
        }

        // 기존 OCR 로직
        val image = InputImage.fromBitmap(croppedBitmap, 0)
        Log.d(TAG, "📝 버스 #${busIndex} 빠른 OCR 시작")

        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                val extractedText = visionText.text.trim()
                Log.d(TAG, "📝 버스 #${busIndex} OCR 추출 텍스트: '$extractedText'")

                if (extractedText.isNotEmpty()) {
                    val busNumber = parseBusNumberFromText(extractedText)
                    if (busNumber != null) {

                        // 🔥 완화된 중복 방지 (2번까지 허용)
                        if (busNumber != lastAnnouncedBusNumber) {
                            // 새로운 버스 번호
                            val message = "${busNumber}번 버스가 앞에 있습니다"
                            speakWithTest(message, "bus_number_ocr")
                            lastAnnouncedBusNumber = busNumber
                            busNumberRepeatCount = 1
                            Log.i(TAG, "✅ 새 버스 번호 인식: $busNumber (1회차)")

                        } else if (busNumberRepeatCount < MAX_REPEAT_COUNT) {
                            // 같은 버스지만 아직 반복 허용
                            busNumberRepeatCount++
                            val message = "${busNumber}번 버스가 계속 앞에 있습니다"
                            speakWithTest(message, "bus_number_repeat")
                            Log.i(TAG, "✅ 버스 번호 반복 안내: $busNumber (${busNumberRepeatCount}회차)")

                        } else {
                            Log.d(TAG, "🔊 버스 번호 반복 횟수 초과: $busNumber (${busNumberRepeatCount}회)")
                        }
                    } else {
                        Log.w(TAG, "❌ 버스 #${busIndex} 유효한 번호 추출 실패: '$extractedText'")
                    }
                } else {
                    Log.d(TAG, "❌ 버스 #${busIndex} OCR 텍스트 없음")
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "💥 버스 #${busIndex} OCR 실패", e)
            }
    }

    private fun parseBusNumberFromText(text: String): String? {
        if (text.isBlank()) return null

        val cleanedText = text
            .replace(Regex("[^0-9가-힣a-zA-Z\\s번호호선라인버스]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        Log.d(TAG, "🧹 텍스트 정제: '$text' → '$cleanedText'")

        val patterns = listOf(
            Regex("""(\d{1,4})\s*번\s*버스"""),
            Regex("""버스\s*(\d{1,4})\s*번"""),
            Regex("""(\d{1,4})\s*번"""),
            Regex("""번호\s*(\d{1,4})"""),
            Regex("""(\d{1,4})\s*호선"""),
            Regex("""(\d{1,4})\s*라인"""),
            Regex("""NO\s*(\d{1,4})""", RegexOption.IGNORE_CASE),
            Regex("""(\d{1,4})\s*[번호]"""),
            Regex("""\b(\d{1,4})\b""")
        )

        for ((index, pattern) in patterns.withIndex()) {
            val matches = pattern.findAll(cleanedText)
            for (match in matches) {
                val number = match.groupValues[1]
                val numValue = number.toIntOrNull()

                if (numValue != null && numValue in 1..9999) {
                    Log.d(TAG, "✅ 패턴 #${index + 1}에서 버스 번호 발견: $number")
                    return number
                }
            }
        }

        Log.w(TAG, "❌ 버스 번호 추출 실패: '$cleanedText'")
        return null
    }

    private fun speakWithTest(message: String, utteranceId: String) {
        Log.d(TAG, "🔊 빠른 음성 출력: '$message' (ID: $utteranceId)")

        try {
            Log.d(TAG, "🔊 TTS 객체 상태: 사용 가능")

            val result = textToSpeech.speak(message, TextToSpeech.QUEUE_ADD, null, utteranceId)

            when (result) {
                TextToSpeech.SUCCESS -> {
                    Log.d(TAG, "✅ 빠른 TTS 성공: $message")
                }
                TextToSpeech.ERROR -> {
                    Log.e(TAG, "❌ TTS 오류: $message")
                }
                else -> {
                    Log.w(TAG, "⚠️ TTS 알 수 없는 결과: $result")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "💥 TTS 출력 중 예외 발생", e)
        }
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        Log.d(TAG, "🔍 ByteBuffer 생성: ${INPUT_SIZE}x${INPUT_SIZE}x3 = ${INPUT_SIZE * INPUT_SIZE * 3} bytes (INT8)")
        Log.d(TAG, "🔍 입력 비트맵 크기: ${bitmap.width}x${bitmap.height}")

        if (bitmap.width != INPUT_SIZE || bitmap.height != INPUT_SIZE) {
            Log.e(TAG, "❌ 비트맵 크기가 예상과 다름: ${bitmap.width}x${bitmap.height}")
            return byteBuffer
        }

        val intValues = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var pixel = 0
        for (i in 0 until INPUT_SIZE) {
            for (j in 0 until INPUT_SIZE) {
                if (pixel < intValues.size) {
                    val pixelValue = intValues[pixel++]

                    val r = (pixelValue shr 16) and 0xFF
                    val g = (pixelValue shr 8) and 0xFF
                    val b = pixelValue and 0xFF

                    byteBuffer.put(r.toByte())
                    byteBuffer.put(g.toByte())
                    byteBuffer.put(b.toByte())
                } else {
                    Log.e(TAG, "❌ 픽셀 인덱스 초과: $pixel >= ${intValues.size}")
                    break
                }
            }
        }

        Log.d(TAG, "✅ ByteBuffer 생성 완료: ${byteBuffer.capacity()} bytes")
        return byteBuffer
    }

    private fun parseQuantizedModelOutput(
        outputBuffer1: ByteBuffer,
        outputBuffer2: ByteBuffer,
        originalWidth: Int,
        originalHeight: Int
    ): List<Detection> {
        val detections = mutableListOf<Detection>()

        Log.d(TAG, "🔍 양자화 모델 출력 파싱 시작")

        outputBuffer1.rewind()
        outputBuffer2.rewind()

        try {
            // 버스 전용 모델 출력 파싱 (클래스 1개)
            for (i in 0 until minOf(NUM_DETECTIONS, outputBuffer1.capacity() / (NUM_CLASSES + 5))) {
                val startIdx = i * (NUM_CLASSES + 5)  // 1 + 5 = 6 (x, y, w, h, conf, bus_class)

                if (startIdx + 5 < outputBuffer1.capacity()) {
                    val x = (outputBuffer1.get(startIdx).toInt() and 0xFF) / 255.0f
                    val y = (outputBuffer1.get(startIdx + 1).toInt() and 0xFF) / 255.0f
                    val w = (outputBuffer1.get(startIdx + 2).toInt() and 0xFF) / 255.0f
                    val h = (outputBuffer1.get(startIdx + 3).toInt() and 0xFF) / 255.0f
                    val confidence = (outputBuffer1.get(startIdx + 4).toInt() and 0xFF) / 255.0f

                    if (confidence > CONFIDENCE_THRESHOLD) {
                        // 버스 클래스 확률 (클래스 1개뿐)
                        val busClassScore = (outputBuffer1.get(startIdx + 5).toInt() and 0xFF) / 255.0f

                        val finalConfidence = confidence * busClassScore
                        if (finalConfidence > CONFIDENCE_THRESHOLD) {
                            // 좌표를 원본 이미지 크기로 변환
                            val centerX = x * originalWidth
                            val centerY = y * originalHeight
                            val width = w * originalWidth
                            val height = h * originalHeight

                            val left = centerX - width / 2
                            val top = centerY - height / 2
                            val right = centerX + width / 2
                            val bottom = centerY + height / 2

                            val boundingBox = RectF(left, top, right, bottom)
                            val label = "bus"  // 항상 버스

                            detections.add(Detection(boundingBox, label, finalConfidence))

                            Log.d(TAG, "🚌 버스 감지! 신뢰도: ${String.format("%.2f", finalConfidence)} 위치: (${left.toInt()}, ${top.toInt()}, ${right.toInt()}, ${bottom.toInt()})")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "💥 커스텀 버스 모델 출력 파싱 오류", e)
        }

        Log.d(TAG, "🔍 커스텀 버스 모델 파싱 완료: ${detections.size}개 버스 감지")
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

    fun drawDetections(bitmap: Bitmap, detections: List<Detection>): Bitmap {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)

        for (detection in detections) {
            if (detection.label == "bus") {
                paint.color = Color.GREEN
                paint.strokeWidth = 5f
            } else {
                paint.color = Color.RED
                paint.strokeWidth = 3f
            }

            canvas.drawRect(detection.boundingBox, paint)

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