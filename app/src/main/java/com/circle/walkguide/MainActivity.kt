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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import com.circle.walkguide.inference.DepthInferenceEngine
import com.circle.walkguide.inference.YoloInferenceEngine
import com.circle.walkguide.model.BBox
import com.circle.walkguide.model.Detection

import com.circle.walkguide.ui.theme.WalkGuideTheme
import com.circle.walkguide.utils.PermissionHandler

// 화면에 표시할 추론 통계
data class InferenceStats(
    val hardwareName: String = "CPU",
    val inferenceMs: Long = 0L,
    val fps: Float = 0f
)

class MainActivity : ComponentActivity(), SensorEventListener // 다중 구현
{

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var lastMotionTime = 0L
    var appMode = mutableStateOf(AppMode.WALKING)

    override fun onSensorChanged(event: SensorEvent) {
        if (AppConfig.USE_FAKE_MODE) return  // 에뮬레이터 테스트 시 센서 무시

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
    var inferenceStats by remember { mutableStateOf(InferenceStats()) } // 화면 stats 표시용
    var lastInferenceTime = remember { 0L } // FPS 계산용
    var logMessages by remember { mutableStateOf<List<String>>(emptyList()) } // 화면 로그

    // for testing => temporary instance
    val decisionEngine = remember { DecisionEngine() } // 재생성x, 유지 by remember
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val inferenceEngine = remember { YoloInferenceEngine(context) }
    val depthEngine = remember {
        if (AppConfig.USE_DEPTH) DepthInferenceEngine(context) else null
    }

    val currentMode = if (appMode.value == AppMode.WALKING) "WALKING" else "STATIONARY"
    val currentRiskState = remember { mutableStateOf("IGNORE") }

    val cameraManager = remember {
        CameraManager(
            context = context,
            lifecycleOwner = lifecycleOwner,
            inferenceEngine = inferenceEngine,
            depthEngine = depthEngine,
            onDetectionResult = { newDetections, _ -> // _ : Bitmap (not using here)
                // FPS 계산
                val now = System.currentTimeMillis()
                val fps = if (lastInferenceTime > 0L) {
                    1000f / (now - lastInferenceTime)
                } else 0f
                lastInferenceTime = now

                android.os.Handler(android.os.Looper.getMainLooper()).post {
//                    if (newDetections.isNotEmpty()) detections = newDetections // bbox 유지하게 설정 => for debugging
                    detections = newDetections
                    inferenceStats = InferenceStats(
                        hardwareName = inferenceEngine.getCurrentHardwareName(),
                        inferenceMs = inferenceEngine.lastInferenceMs,
                        fps = fps
                    )
                    // 감지 로그 화면에 추가 (최대 20줄 유지)
                    if (newDetections.isNotEmpty()) {
                        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                            .format(java.util.Date())
                        val logEntry = "[$time] ${newDetections.map { "${it.label}(${"%.2f".format(it.confidence)}) #${it.trackId}" }}"
                        logMessages = (logMessages + listOf(logEntry)).takeLast(100)
                    }
                }
                val alerts = decisionEngine.process(newDetections)
                alerts.forEach { alert ->
                    FeedbackManager.speak(alert.message)
                    Log.d("DE_TEST", "Alert: ${alert.message} / ${alert.riskLevel}")
                }

                // UI 업데이트
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    currentRiskState.value = if (alerts.isNotEmpty()) {
                        alerts.maxByOrNull { it.riskLevel.ordinal }?.riskLevel?.name ?: "IGNORE"
                    } else {
                        "IGNORE"
                    }
                }
            }
        )
    }

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
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f)
                        )

                        // 라벨 텍스트
                        drawContext.canvas.nativeCanvas.drawText(
                            "${detection.label} ${"%.2f".format(detection.confidence)} #${detection.trackId}",
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
                // Mode, Risk 텍스트
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Mode: $currentMode",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    Text(
                        text = "Risk: ${currentRiskState.value}",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                // 추론 stats 오버레이 (우측 하단)
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                        .background(
                            Color.Black.copy(alpha = 0.5f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(8.dp)
                ) {
                    Text(text = "HW: ${inferenceStats.hardwareName}", color = Color.Yellow, fontSize = 12.sp)
                    Text(text = "${inferenceStats.inferenceMs}ms", color = Color.Yellow, fontSize = 12.sp)
                    Text(text = "${"%.1f".format(inferenceStats.fps)} fps", color = Color.Yellow, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 감지 로그 뷰 (컴퓨터 없이 디버깅용)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .background(Color.Black.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                items(logMessages) { log ->
                    Text(
                        text = log,
                        color = Color.Green,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            // foot
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
                                label = "킥보드",
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
                        currentRiskState.value =
                            if (currentRiskState.value == "IGNORE") "WARNING" else "IGNORE"
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