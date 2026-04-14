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
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.circle.walkguide.model.enums.AppMode
import kotlin.math.abs
import kotlin.math.sqrt

import com.circle.walkguide.camera.CameraManager
import com.circle.walkguide.engine.DecisionEngine
import com.circle.walkguide.inference.YoloInferenceEngine
import com.circle.walkguide.model.BBox
import com.circle.walkguide.model.Detection

import com.circle.walkguide.ui.theme.WalkGuideTheme
import com.circle.walkguide.utils.PermissionHandler

class MainActivity : ComponentActivity(), SensorEventListener // 다중 구현
{

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var lastMotionTime = 0L
    var appMode = mutableStateOf(AppMode.WALKING)

    override fun onSensorChanged(event: SensorEvent) {
        // magnitude: x, y, z 세 축 가속도 합쳐서 전체 움직임 크기 계산
        val magnitude = sqrt(
            event.values[0] * event.values[0] +
                    event.values[1] * event.values[1] +
                    event.values[2] * event.values[2]
        )
        val motion = abs(magnitude - 9.8f) // 중력은 항상 있으니 제거. 순수 움직임만 추출

        // threshold 이상 움직이면 모드 전환
        if (motion > AppConfig.MOTION_THRESHOLD) {
            appMode.value = AppMode.WALKING
            lastMotionTime = System.currentTimeMillis()
        } else if (System.currentTimeMillis() - lastMotionTime > AppConfig.STATIONARY_TIMEOUT_MS) {
            appMode.value = AppMode.STATIONARY
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        FeedbackManager.initTts(this)

        // 센서 초기화
        sensorManager = getSystemService(SensorManager::class.java)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let {
            sensorManager.registerListener(this, it, AppConfig.SENSOR_DELAY)
        }

        // permission
        if (!PermissionHandler.hasCameraPermission(this)) {
            PermissionHandler.requestCameraPermission(
                activity = this,
                onGranted = { /* 카메라 시작 */ },
                onDenied = { /* 권한 거부 안내 */ }
            )
        }

        setContent {
            WalkGuideTheme {
                WalkGuideApp(appMode)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        FeedbackManager.shutdown()
        sensorManager.unregisterListener(this)  // 가속도 센서 해제
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalkGuideApp(appMode: MutableState<AppMode>) {
    var detections by remember { mutableStateOf<List<Detection>>(emptyList()) } // for box overlay

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
            onDetectionResult = { newDetections, _ -> // _ : Bitmap (not using here)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    detections = newDetections
                }
                val alerts = decisionEngine.process(newDetections)
                alerts.forEach { alert ->
                    FeedbackManager.speak(alert.message)
                    Log.d("DE_TEST", "Alert: ${alert.message} / ${alert.riskLevel}")
                }
            }
        )
    }

    val currentMode = if (appMode.value == AppMode.WALKING) "WALKING" else "STATIONARY"
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
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        cameraManager.start(previewView)
                        previewView
                    }
                )
                // 바운딩박스 오버레이
                Canvas(modifier = Modifier.fillMaxSize()) {
                    detections.forEach { detection ->
                        val left = detection.bbox.x * size.width
                        val top = detection.bbox.y * size.height
                        val right = (detection.bbox.x + detection.bbox.width) * size.width
                        val bottom = (detection.bbox.y + detection.bbox.height) * size.height

                        // 박스 그리기
                        drawRect(
                            color = Color.Green,
                            topLeft = androidx.compose.ui.geometry.Offset(left, top),
                            size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
                        )

                        // 라벨 텍스트
                        drawContext.canvas.nativeCanvas.drawText(
                            "${detection.label} ${"%.2f".format(detection.confidence)}",
                            left,
                            if (top > 50f) top - 10f else bottom + 40f,
                            android.graphics.Paint().apply {
                                color = android.graphics.Color.GREEN
                                textSize = 40f
                                isFakeBoldText = true
                            }
                        )
                    }
                }
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
                        appMode.value = if (appMode.value == AppMode.WALKING)
                            AppMode.STATIONARY else AppMode.WALKING
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