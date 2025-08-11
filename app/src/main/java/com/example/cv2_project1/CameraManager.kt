// CameraManager.kt (OCR 완전 제거)

package com.example.cv2_project1

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.*

class CameraManager(
    private val context: Context,
    private val textureView: TextureView
) {

    private val TAG = "CameraManager"

    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var captureRequestBuilder: CaptureRequest.Builder? = null

    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
    private var cameraId: String = ""
    private var previewSize: Size = Size(1920, 1080)

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = false

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            // 단순 카메라 미리보기만 - OCR 제거로 성능 향상
        }
    }

    init {
        textureView.surfaceTextureListener = textureListener
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.d(TAG, "카메라 열림")
            cameraDevice = camera
            createCameraPreview()
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.d(TAG, "카메라 연결 해제")
            cameraDevice?.close()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "카메라 에러: $error")
            cameraDevice?.close()
            cameraDevice = null
        }
    }

    fun startCamera() {
        startBackgroundThread()
        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = textureListener
        }
    }

    fun stopCamera() {
        closeCamera()
        stopBackgroundThread()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        // 권한 확인
        if (!hasCameraPermission()) {
            Log.e(TAG, "카메라 권한이 없습니다")
            Toast.makeText(context, "카메라 권한이 필요합니다", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            cameraId = setupCamera()
            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "카메라 접근 에러", e)
            Toast.makeText(context, "카메라를 열 수 없습니다", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun setupCamera(): String {
        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)

            // 후면 카메라 선택
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {

                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                previewSize = chooseOptimalSize(
                    map?.getOutputSizes(SurfaceTexture::class.java),
                    textureView.width,
                    textureView.height
                )

                return cameraId
            }
        }
        return cameraManager.cameraIdList[0] // 기본값
    }

    private fun chooseOptimalSize(choices: Array<Size>?, textureViewWidth: Int, textureViewHeight: Int): Size {
        if (choices == null) return Size(1920, 1080)

        val bigEnough = mutableListOf<Size>()
        val notBigEnough = mutableListOf<Size>()

        for (option in choices) {
            if (option.width >= textureViewWidth && option.height >= textureViewHeight) {
                bigEnough.add(option)
            } else {
                notBigEnough.add(option)
            }
        }

        return when {
            bigEnough.isNotEmpty() -> Collections.min(bigEnough) { lhs, rhs ->
                (lhs.width * lhs.height).compareTo(rhs.width * rhs.height)
            }
            notBigEnough.isNotEmpty() -> Collections.max(notBigEnough) { lhs, rhs ->
                (lhs.width * lhs.height).compareTo(rhs.width * rhs.height)
            }
            else -> choices[0]
        }
    }

    private fun createCameraPreview() {
        try {
            val texture = textureView.surfaceTexture!!
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)

            val surface = Surface(texture)
            captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder!!.addTarget(surface)

            cameraDevice!!.createCaptureSession(
                Arrays.asList(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        if (cameraDevice == null) return

                        this@CameraManager.cameraCaptureSession = cameraCaptureSession
                        updatePreview()
                    }

                    override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                        Log.e(TAG, "카메라 세션 구성 실패")
                        Toast.makeText(context, "카메라 미리보기 설정 실패", Toast.LENGTH_SHORT).show()
                    }
                },
                null
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "카메라 미리보기 생성 에러", e)
        }
    }

    private fun updatePreview() {
        if (cameraDevice == null) return

        captureRequestBuilder!!.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        try {
            cameraCaptureSession!!.setRepeatingRequest(
                captureRequestBuilder!!.build(),
                null,
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "카메라 미리보기 업데이트 에러", e)
        }
    }

    private fun closeCamera() {
        cameraCaptureSession?.close()
        cameraCaptureSession = null

        cameraDevice?.close()
        cameraDevice = null
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground")
        backgroundThread.start()
        backgroundHandler = Handler(backgroundThread.looper)
    }

    private fun stopBackgroundThread() {
        if (::backgroundThread.isInitialized) {
            backgroundThread.quitSafely()
            try {
                backgroundThread.join()
            } catch (e: InterruptedException) {
                Log.e(TAG, "백그라운드 스레드 종료 에러", e)
            }
        }
    }

    fun cleanup() {
        closeCamera()
        stopBackgroundThread()
    }
}