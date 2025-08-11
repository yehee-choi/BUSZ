// PermissionUtils.kt

package com.example.cv2_project1
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object PermissionUtils {

    const val CAMERA_PERMISSION = Manifest.permission.CAMERA
    const val RECORD_AUDIO_PERMISSION = Manifest.permission.RECORD_AUDIO
    const val MODIFY_AUDIO_SETTINGS_PERMISSION = Manifest.permission.MODIFY_AUDIO_SETTINGS

    /**
     * 모든 필요한 권한이 허용되었는지 확인
     */
    fun hasAllPermissions(context: Context): Boolean {
        return hasCameraPermission(context) &&
                hasAudioPermissions(context)
    }

    /**
     * 카메라 권한 확인
     */
    fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            CAMERA_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 음성 인식 관련 권한 확인
     */
    fun hasAudioPermissions(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            RECORD_AUDIO_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context,
                    MODIFY_AUDIO_SETTINGS_PERMISSION
                ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 필요한 모든 권한 목록 반환
     */
    fun getRequiredPermissions(): Array<String> {
        return arrayOf(
            CAMERA_PERMISSION,
            RECORD_AUDIO_PERMISSION,
            MODIFY_AUDIO_SETTINGS_PERMISSION
        )
    }

    /**
     * 거부된 권한 목록 반환
     */
    fun getDeniedPermissions(context: Context): List<String> {
        return getRequiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
    }
}