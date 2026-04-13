package com.circle.walkguide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.circle.walkguide.utils.FeedbackManager
import androidx.compose.ui.platform.LocalContext
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.circle.walkguide.camera.CameraManager
import com.circle.walkguide.engine.DecisionEngine
import com.circle.walkguide.inference.YoloInferenceEngine
import com.circle.walkguide.model.BBox
import com.circle.walkguide.model.Detection

import com.circle.walkguide.ui.theme.WalkGuideTheme
import com.circle.walkguide.utils.PermissionHandler

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        FeedbackManager.initTts(this)

        if (!PermissionHandler.hasCameraPermission(this)) {
            PermissionHandler.requestCameraPermission(
                activity = this,
                onGranted = { /* 카메라 시작 */ },
                onDenied = { /* 권한 거부 안내 */ }
            )
        }

        setContent {
            WalkGuideTheme {
                WalkGuideApp()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        FeedbackManager.shutdown()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalkGuideApp() {
    // for testing => temporary instance
    val decisionEngine = remember { DecisionEngine() } // 재생성x, 유지 by remember
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val inferenceEngine = remember { YoloInferenceEngine(context) }
    val cameraManager = remember {
        CameraManager(
            context = context,
            lifecycleOwner = lifecycleOwner,
            inferenceEngine = inferenceEngine,
            onDetectionResult = { detections ->
                val alerts = decisionEngine.process(detections)
                alerts.forEach { alert ->
                    FeedbackManager.speak(alert.message)
                    Log.d("DE_TEST", "Alert: ${alert.message} / ${alert.riskLevel}")
                }
            }
        )
    }

    var currentMode by remember { mutableStateOf("WALKING") }
    var currentRisk by remember { mutableStateOf("IGNORE") }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Walk Guide") },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                )
            )
        }
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(20.dp)
                    )
                    .background(Color.DarkGray),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(  // CameraPreview 대신 이걸로
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        cameraManager.start(previewView)
                        previewView
                    }
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Mode: $currentMode",
                        color = Color.Red
                    )
                    Text(
                        text = "Risk: $currentRisk",
                        color = Color.Red
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DebugButton(
                    text = "TTS",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val fakeDetections = listOf(
                            Detection(
                                label = "kickboard",
                                confidence = 0.91f,
                                bbox = BBox(0.3f, 0.5f, 0.4f, 0.4f),
                                timestamp = System.currentTimeMillis(),
                                trackId = 1
                            )
                        )
                        val alerts = decisionEngine.process(fakeDetections)
                        alerts.forEach { alert ->
                            FeedbackManager.speak(alert.message)
                            Log.d("DE_TEST", "Alert: ${alert.message} / ${alert.riskLevel}")
                        }
                    }
                )
                DebugButton(
                    text = "Haptic",
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    onClick = {
                        Log.d("DEBUG", "wow")
                        FeedbackManager.triggerHaptic(context)
                    }
                )
                DebugButton(
                    text = "Mode",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        currentMode =
                            if (currentMode == "WALKING") "STATIONARY" else "WALKING"
                    }
                )
                DebugButton(
                    text = "Risk",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        currentRisk =
                            if (currentRisk == "IGNORE") "WARNING" else "IGNORE"
                    }
                )
            }
        }
    }
}

@Composable
fun DebugButton(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 14.sp,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        Text(text = text, fontSize = fontSize)
    }
}