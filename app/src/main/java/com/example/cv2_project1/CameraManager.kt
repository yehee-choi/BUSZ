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

    // 감지 주기 제어
    private val detectionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isDetectionRunning = false
    private val DETECTION_INTERVAL_MS = 3000L // 3초마다 객체 감지

    // 프레임 처리 제어
    private var isProcessingFrame = false

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
        }
    }
    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
                startObjectDetection()
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

        // 이미지 분석 설정 - 한 번만 설정
        imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(640, 480)) // 해상도 지정
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
            // 기존 바인딩 해제
            cameraProvider.unbindAll()

            // 카메라 바인딩
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

    private fun startObjectDetection() {
        if (isDetectionRunning) return

        isDetectionRunning = true

        detectionScope.launch {
            while (isDetectionRunning) {
                try {
                    // 3초마다 객체 감지 허용
                    delay(DETECTION_INTERVAL_MS)
                    isProcessingFrame = false // 다음 프레임 처리 허용

                } catch (e: Exception) {
                    Log.e(TAG, "💥 객체 감지 중 오류", e)
                    delay(1000) // 에러 시 1초 대기
                }
            }
        }

        Log.d(TAG, "🔍 객체 감지 시작 (${DETECTION_INTERVAL_MS/1000}초 간격)")
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        // 프레임 처리 중이면 스킵
        if (isProcessingFrame) {
            imageProxy.close()
            return
        }

        try {
            // ImageProxy를 Bitmap으로 변환
            val bitmap = imageProxyToBitmap(imageProxy)

            if (bitmap != null) {
                isProcessingFrame = true // 프레임 처리 시작

                // 백그라운드 스레드에서 객체 감지 실행
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        objectDetectionManager?.detectObjects(bitmap)
                    } catch (e: Exception) {
                        Log.e(TAG, "💥 객체 감지 실패", e)
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "💥 프레임 분석 실패", e)
        } finally {
            imageProxy.close()
        }
    }

    // ✅ 수정된 ImageProxy to Bitmap 변환 함수
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val yBuffer = imageProxy.planes[0].buffer // Y
            val vuBuffer = imageProxy.planes[2].buffer // VU

            val ySize = yBuffer.remaining()
            val vuSize = vuBuffer.remaining()

            val nv21 = ByteArray(ySize + vuSize)

            yBuffer.get(nv21, 0, ySize)
            vuBuffer.get(nv21, ySize, vuSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 50, out)
            val imageBytes = out.toByteArray()

            android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

        } catch (e: Exception) {
            Log.e(TAG, "💥 Bitmap 변환 실패", e)

            // 대안: 간단한 RGB 변환 (품질은 낮지만 작동함)
            try {
                val bitmap = Bitmap.createBitmap(
                    imageProxy.width,
                    imageProxy.height,
                    Bitmap.Config.ARGB_8888
                )

                // YUV_420_888을 간단하게 RGB로 변환
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
                            // Y 값을 그레이스케일로 변환
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
            startObjectDetection()
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