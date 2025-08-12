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
    onSpeechManagerReady: ((String) -> Unit, (Boolean) -> Unit) -> Unit
) {
    var speechResult by remember { mutableStateOf("버스 번호를 말씀해주세요") }
    var isListening by remember { mutableStateOf(false) }
    var speechCompleted by remember { mutableStateOf(false) }
    var isAsyncServicesActive by remember { mutableStateOf(false) }

    var cameraManager: CameraManager? by remember { mutableStateOf(null) }

    // MainActivity에 콜백 등록
    LaunchedEffect(Unit) {
        onSpeechManagerReady(
            { result ->
                speechResult = result
                speechCompleted = true
                isAsyncServicesActive = true
            },
            { listening -> isListening = listening }
        )
    }

    // 상태에 따른 UI 업데이트
    val statusText = when {
        isAsyncServicesActive -> "🚀 비동기 서비스 실행 중 - 카메라 감지 & 서버 모니터링"
        speechCompleted -> "위치 정보 전송 중..."
        isListening -> "버스 번호를 말씀해주세요..."
        else -> "음성 인식 준비 중..."
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 카메라 미리보기 영역 (상단 65%)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.65f)
        ) {
            AndroidView(
                factory = { context ->
                    PreviewView(context).apply {
                        if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED) {

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

            // 비동기 서비스 상태 오버레이
            if (isAsyncServicesActive) {
                Card(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xAA4CAF50) // 초록색 - 활성 상태
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "🚀 비동기 실행",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(12.dp)
                    )
                }

                // 카메라 감지 상태 (우상단)
                Card(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xAA9C27B0) // 보라색
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "🔍 3초 감지",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                }

                // 서버 모니터링 상태 (중앙 하단)
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xAAFF5722) // 주황색
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "📡 서버 모니터링 (30초)",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else if (speechCompleted) {
                // 객체 감지 대기 상태
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
                        text = "🔍 감지 준비",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }

        // 정보 영역 (하단 35%)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.35f)
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
                    containerColor = when {
                        isAsyncServicesActive -> Color(0xFF1976D2) // 파란색 - 비동기 실행
                        speechCompleted -> Color(0xFF4CAF50) // 초록색 - 완료
                        isListening -> Color(0xFFFF5722) // 주황색 - 듣는 중
                        else -> Color(0xFF404040) // 회색 - 대기
                    }
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
                            text = when {
                                isAsyncServicesActive -> "🚀"
                                speechCompleted -> "🚌"
                                isListening -> "🎤"
                                else -> "⏳"
                            },
                            fontSize = 24.sp,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        Column {
                            Text(
                                text = when {
                                    isAsyncServicesActive -> "비동기 서비스 실행"
                                    speechCompleted -> "버스 모니터링"
                                    isListening -> "음성 인식 중"
                                    else -> "준비 중"
                                },
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
                            speechCompleted -> Color(0xFF4CAF50) // 완료
                            isListening -> Color(0xFFFF5722) // 듣는 중
                            else -> Color(0xFF666666) // 대기
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
                                speechCompleted -> " 완료"
                                isListening -> "음성 듣는중"
                                else -> " 대기"
                            },
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // 카메라 감지 상태
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .padding(horizontal = 2.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isAsyncServicesActive) Color(0xFF9C27B0) else Color(0xFF666666)
                    ),
                    shape = RoundedCornerShape(25.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isAsyncServicesActive) " 감지중" else " 대기",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // 서버 모니터링 상태
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .padding(start = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isAsyncServicesActive) Color(0xFFFF5722) else Color(0xFF666666)
                    ),
                    shape = RoundedCornerShape(25.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isAsyncServicesActive) " 30초" else "️ 대기",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 비동기 서비스 상태 표시
            if (isAsyncServicesActive) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF424242)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "• 카메라: 3초마다 객체 감지\n• 서버: 30초마다 버스 도착 정보",
                            color = Color(0xFFB0BEC5),
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}