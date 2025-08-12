package com.example.cv2_project1

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val textToSpeech: TextToSpeech
) {
    private val TAG = "CameraManager"

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // 객체 감지 매니저
    private var objectDetectionManager: ObjectDetectionManager? = null

    // 비동기 감지 제어
    private val detectionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isDetectionRunning = false
    private val DETECTION_INTERVAL_MS = 3000L // 3초 간격

    // 프레임 처리 제어
    private var isProcessingFrame = false
    private var lastDetectionTime = 0L

    init {
        initializeObjectDetection()
    }

    private fun initializeObjectDetection() {
        objectDetectionManager = ObjectDetectionManager(
            context = context,
            textToSpeech = textToSpeech
        ) { detections ->
            // 감지 결과 콜백
            Log.d(TAG, "🔍 감지된 객체: ${detections.size}개")

            val busCount = detections.count { it.label == "bus" }
            if (busCount > 0) {
                Log.d(TAG, "🚌 버스 ${busCount}개 감지됨 - OCR 진행 중")
            }
        }
    }

    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
                Log.d(TAG, "📷 카메라 시작 성공")
            } catch (e: Exception) {
                Log.e(TAG, "💥 카메라 시작 실패", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = this.cameraProvider ?: return

        // 프리뷰 설정
        val preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        // 이미지 분석 설정
        imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImageProxy(imageProxy)
                }
            }

        // 카메라 선택 (후면 카메라)
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()

            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )

        } catch (e: Exception) {
            Log.e(TAG, "💥 카메라 바인딩 실패", e)
        }
    }

    // 🚀 비동기 객체 감지 시작 (독립적인 스레드)
    fun startAsyncObjectDetection() {
        if (isDetectionRunning) {
            Log.d(TAG, "🔍 객체 감지가 이미 실행 중입니다")
            return
        }

        isDetectionRunning = true
        lastDetectionTime = 0L

        detectionScope.launch {
            Log.d(TAG, "🚀 비동기 객체 감지 스레드 시작 (${DETECTION_INTERVAL_MS/1000}초 간격)")

            while (isDetectionRunning) {
                try {
                    delay(DETECTION_INTERVAL_MS)

                    val currentTime = System.currentTimeMillis()
                    Log.d(TAG, "⏰ 객체 감지 주기 도달 - 다음 프레임 처리 허용")

                    // 프레임 처리 허용
                    isProcessingFrame = false

                } catch (e: CancellationException) {
                    Log.d(TAG, "🔍 객체 감지 스레드 취소됨")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "💥 객체 감지 스레드 오류", e)
                    delay(1000) // 에러 시 1초 대기
                }
            }

            Log.d(TAG, "🔍 비동기 객체 감지 스레드 종료")
        }

        // TTS 시작 안내
        try {
            textToSpeech.speak(
                "3초마다 객체 감지를 시작합니다",
                TextToSpeech.QUEUE_ADD,
                null,
                "detection_start"
            )
        } catch (e: Exception) {
            Log.e(TAG, "TTS 오류", e)
        }
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()

        // 감지 주기 제어
        if (isProcessingFrame || (currentTime - lastDetectionTime < DETECTION_INTERVAL_MS)) {
            imageProxy.close()
            return
        }

        try {
            val bitmap = imageProxyToBitmap(imageProxy)

            if (bitmap != null) {
                isProcessingFrame = true
                lastDetectionTime = currentTime

                Log.d(TAG, "🔍 객체 감지 실행 중... (${DETECTION_INTERVAL_MS/1000}초 간격)")

                // 별도 코루틴에서 객체 감지 실행
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val detections = objectDetectionManager?.detectObjects(bitmap)

                        detections?.let { detectionList ->
                            val busDetections = detectionList.filter { it.label == "bus" }
                            if (busDetections.isNotEmpty()) {
                                Log.d(TAG, "🚌 버스 감지 완료 - ${busDetections.size}대")
                            } else {
                                Log.d(TAG, "🔍 객체 감지 완료 - 버스 없음 (일반 객체: ${detectionList.size}개)")
                            }
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "💥 객체 감지 실행 실패", e)
                    } finally {
                        Log.d(TAG, "✅ 객체 감지 완료 - 다음 감지까지 ${DETECTION_INTERVAL_MS/1000}초 대기")
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "💥 프레임 분석 실패", e)
            isProcessingFrame = false
        } finally {
            imageProxy.close()
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val yBuffer = imageProxy.planes[0].buffer
            val vuBuffer = imageProxy.planes[2].buffer

            val ySize = yBuffer.remaining()
            val vuSize = vuBuffer.remaining()

            val nv21 = ByteArray(ySize + vuSize)

            yBuffer.get(nv21, 0, ySize)
            vuBuffer.get(nv21, ySize, vuSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 75, out)
            val imageBytes = out.toByteArray()

            android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

        } catch (e: Exception) {
            Log.e(TAG, "💥 Bitmap 변환 실패", e)

            // 대안: 간단한 RGB 변환
            try {
                val bitmap = Bitmap.createBitmap(
                    imageProxy.width,
                    imageProxy.height,
                    Bitmap.Config.ARGB_8888
                )

                val yBuffer = imageProxy.planes[0].buffer
                val yPixelStride = imageProxy.planes[0].pixelStride
                val yRowStride = imageProxy.planes[0].rowStride

                val pixels = IntArray(imageProxy.width * imageProxy.height)
                var pixelIndex = 0

                for (y in 0 until imageProxy.height) {
                    for (x in 0 until imageProxy.width) {
                        val yIndex = y * yRowStride + x * yPixelStride
                        if (yIndex < yBuffer.capacity()) {
                            val yValue = yBuffer.get(yIndex).toInt() and 0xFF
                            val grayValue = yValue
                            val rgb = (0xFF shl 24) or (grayValue shl 16) or (grayValue shl 8) or grayValue
                            pixels[pixelIndex++] = rgb
                        }
                    }
                }

                bitmap.setPixels(pixels, 0, imageProxy.width, 0, 0, imageProxy.width, imageProxy.height)
                bitmap

            } catch (e2: Exception) {
                Log.e(TAG, "💥 대안 Bitmap 변환도 실패", e2)
                null
            }
        }
    }

    fun stopCamera() {
        isDetectionRunning = false
        cameraProvider?.unbindAll()
        Log.d(TAG, "📷 카메라 중지")
    }

    fun pauseDetection() {
        isDetectionRunning = false
        Log.d(TAG, "⏸️ 객체 감지 일시정지")
    }

    fun resumeDetection() {
        if (!isDetectionRunning) {
            startAsyncObjectDetection()
            Log.d(TAG, "▶️ 객체 감지 재시작")
        }
    }

    fun cleanup() {
        isDetectionRunning = false
        detectionScope.cancel()
        cameraExecutor.shutdown()
        objectDetectionManager?.cleanup()
        cameraProvider?.unbindAll()
        Log.d(TAG, "🧹 CameraManager 정리 완료")
    }
}