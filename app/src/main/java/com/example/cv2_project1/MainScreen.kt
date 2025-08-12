package com.example.cv2_project1

import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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

    var cameraManager: CameraManager? by remember { mutableStateOf(null) }

    // MainActivity에 콜백 등록
    LaunchedEffect(Unit) {
        onSpeechManagerReady(
            { result ->
                speechResult = result
                speechCompleted = true
            },
            { listening ->
                isListening = listening
                if (listening) {
                    speechResult = "듣고 있습니다..."
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 카메라 영역 (90%)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.9f)
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
        }

        // 하단 텍스트 영역 (10%)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.1f)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = speechResult,
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}