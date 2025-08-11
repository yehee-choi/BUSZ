// LocationManager.kt

package com.example.cv2_project1

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.google.android.gms.tasks.Task

class LocationManager(
    private val context: Context
) {

    private val TAG = "LocationManager"

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private var locationCallback: LocationCallback? = null

    // 현재 위치 정보 저장
    var currentLatitude: Double = 0.0
        private set
    var currentLongitude: Double = 0.0
        private set
    var isLocationAvailable: Boolean = false
        private set

    // 위치 업데이트 콜백
    private var onLocationUpdate: ((Double, Double) -> Unit)? = null

    init {
        initializeLocationClient()
    }

    private fun initializeLocationClient() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

        // 위치 요청 설정 (성능 최적화)
        locationRequest = LocationRequest.create().apply {
            interval = 60000 // 1분마다 (이전: 10초)
            fastestInterval = 30000 // 최소 30초 간격 (이전: 5초)
            priority = LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY // 배터리 절약
        }
    }

    fun startLocationUpdates(onUpdate: (Double, Double) -> Unit = { _, _ -> }) {
        onLocationUpdate = onUpdate

        if (!hasLocationPermission()) {
            Log.w(TAG, "위치 권한이 없습니다")
            return
        }

        // 위치 콜백 설정
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    updateLocation(location)
                }
            }
        }

        try {
            // 마지막 알려진 위치 먼저 가져오기
            getLastKnownLocation()

            // 실시간 위치 업데이트 시작 (구버전 호환)
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                null // Looper
            ).addOnSuccessListener {
                Log.d(TAG, "위치 업데이트 요청 성공")
            }.addOnFailureListener { e ->
                Log.e(TAG, "위치 업데이트 요청 실패", e)
            }

            Log.d(TAG, "위치 업데이트 시작됨")

        } catch (e: SecurityException) {
            Log.e(TAG, "위치 권한 오류", e)
        }
    }

    private fun getLastKnownLocation() {
        if (!hasLocationPermission()) return

        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                location?.let {
                    updateLocation(it)
                    Log.d(TAG, "마지막 알려진 위치 획득")
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "마지막 위치 가져오기 실패", e)
        }
    }

    private fun updateLocation(location: Location) {
        currentLatitude = location.latitude
        currentLongitude = location.longitude
        isLocationAvailable = true

        Log.d(TAG, "위치 업데이트: 위도=${currentLatitude}, 경도=${currentLongitude}")

        // 콜백 호출
        onLocationUpdate?.invoke(currentLatitude, currentLongitude)
    }

    fun stopLocationUpdates() {
        locationCallback?.let { callback ->
            fusedLocationClient.removeLocationUpdates(callback).addOnSuccessListener {
                Log.d(TAG, "위치 업데이트 중지됨")
            }.addOnFailureListener { e ->
                Log.e(TAG, "위치 업데이트 중지 실패", e)
            }
        }
        locationCallback = null
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 서버 전송용 위치 정보 반환
     */
    fun getLocationForServer(): Pair<Double, Double>? {
        return if (isLocationAvailable) {
            Pair(currentLatitude, currentLongitude)
        } else {
            null
        }
    }

    /**
     * 위치 정보를 문자열로 반환
     */
    fun getLocationString(): String {
        return if (isLocationAvailable) {
            "위도: $currentLatitude, 경도: $currentLongitude"
        } else {
            "위치 정보 없음"
        }
    }

    fun cleanup() {
        stopLocationUpdates()
    }
}