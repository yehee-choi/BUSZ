package com.example.cv2_project1

import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
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
import androidx.camera.view.PreviewView

@Composable
fun MainScreen(
    activity: ComponentActivity,
    onCameraManagerReady: (CameraManager) -> Unit,
    onSpeechManagerReady: ((String) -> Unit, (Boolean) -> Unit) -> Unit // 결과 콜백, 상태 콜백
) {
    var speechResult by remember { mutableStateOf("버스 번호를 말씀해주세요") }
    var isListening by remember { mutableStateOf(false) }
    var speechCompleted by remember { mutableStateOf(false) }
    var objectDetectionStatus by remember { mutableStateOf("객체 감지 대기 중") }

    var cameraManager: CameraManager? by remember { mutableStateOf(null) }

    // MainActivity에 콜백 등록
    LaunchedEffect(Unit) {
        onSpeechManagerReady(
            { result ->
                speechResult = result
                speechCompleted = true // 음성 인식 완료
                objectDetectionStatus = "객체 감지 시작됨"
            }, // 결과 콜백
            { listening -> isListening = listening } // 상태 콜백
        )
    }

    // 상태에 따른 UI 업데이트
    val statusText = when {
        speechCompleted -> "버스 모니터링 & 객체 감지 실행 중"
        isListening -> "버스 번호를 말씀해주세요..."
        else -> "음성 인식 준비 중..."
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 카메라 미리보기 영역 (상단 60%)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.6f)
        ) {
            AndroidView(
                factory = { context ->
                    PreviewView(context).apply {
                        if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED) {

                            // 향상된 CameraManager 생성 (객체 감지 포함)
                            val manager = CameraManager(
                                context = activity,
                                lifecycleOwner = activity,
                                previewView = this,
                                textToSpeech = (activity as MainActivity).textToSpeech
                            )
                            cameraManager = manager
                            onCameraManagerReady(manager)

                            // 음성 인식 완료 후에만 카메라 시작
                            if (speechCompleted) {
                                manager.startCamera()
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // 객체 감지 상태 오버레이
            if (speechCompleted) {
                Card(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xAA000000)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "🔍 객체 감지 중",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }

        // 정보 영역 (하단 40%)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.4f)
                .background(Color(0xFF2C2C2C))
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 전체 상태 텍스트
            Text(
                text = statusText,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // 버스 정보 영역
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (speechCompleted) Color(0xFF1976D2) else Color(0xFF404040)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (speechCompleted) "🚌" else "🎤",
                            fontSize = 24.sp,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        Column {
                            Text(
                                text = if (speechCompleted) "버스 모니터링" else "음성 인식",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = speechResult,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // 상태 표시 영역
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // 음성 인식 상태
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .padding(end = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            speechCompleted -> Color(0xFF4CAF50)
                            isListening -> Color(0xFFFF5722)
                            else -> Color(0xFF666666)
                        }
                    ),
                    shape = RoundedCornerShape(25.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = when {
                                speechCompleted -> "✅ 완료"
                                isListening -> "🎤 듣는중"
                                else -> "⏳ 대기"
                            },
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // 객체 감지 상태
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .padding(start = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (speechCompleted) Color(0xFF9C27B0) else Color(0xFF666666)
                    ),
                    shape = RoundedCornerShape(25.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (speechCompleted) "🔍 감지중" else "⏸️ 대기",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}