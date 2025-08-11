
package com.example.cv2_project1

import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun MainScreen(
    activity: ComponentActivity,
    onCameraManagerReady: (CameraManager) -> Unit,
    onSpeechManagerReady: ((String) -> Unit, (Boolean) -> Unit) -> Unit // 결과 콜백, 상태 콜백
) {
    var speechResult by remember { mutableStateOf("버스 번호를 말씀해주세요") }
    var isListening by remember { mutableStateOf(false) }
    var speechCompleted by remember { mutableStateOf(false) }

    var cameraManager: CameraManager? by remember { mutableStateOf(null) }

    // MainActivity에 콜백 등록
    LaunchedEffect(Unit) {
        onSpeechManagerReady(
            { result ->
                speechResult = result
                speechCompleted = true // 음성 인식 완료
            }, // 결과 콜백
            { listening -> isListening = listening } // 상태 콜백
        )
    }

    // 상태에 따른 UI 업데이트
    val statusText = when {
        speechCompleted -> "음성 인식 완료"
        isListening -> "음성을 듣고 있습니다..."
        else -> "음성 인식 준비 중..."
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 카메라 미리보기 영역 (상단 50%)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.5f)
        ) {
            AndroidView(
                factory = { context ->
                    TextureView(context).apply {
                        if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED) {

                            val manager = CameraManager(activity, this) // OCR 완전 제거
                            cameraManager = manager
                            onCameraManagerReady(manager)
                            manager.startCamera()
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 음성 인식 영역 (하단 50%)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.5f)
                .background(Color(0xFF2C2C2C))
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 상태 텍스트
            Text(
                text = statusText,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            // 음성 인식 결과 영역
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (speechCompleted) Color(0xFF4CAF50) else Color(0xFF404040)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (speechCompleted) "✓" else "🎤",
                            fontSize = 32.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Text(
                            text = speechResult,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // 상태 표시
            Spacer(modifier = Modifier.height(24.dp))

            if (isListening) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFF5722)),
                    shape = RoundedCornerShape(30.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "🎤 듣는 중...",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else if (speechCompleted) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF4CAF50)),
                    shape = RoundedCornerShape(30.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "✓ 완료",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF666666)),
                    shape = RoundedCornerShape(30.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "⏳ 준비 중...",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}