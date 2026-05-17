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
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.circle.walkguide.model.Detection
import com.circle.walkguide.model.enums.RiskLevel

import com.circle.walkguide.ui.theme.WalkGuideTheme
import com.circle.walkguide.utils.PermissionHandler

data class InferenceStats(
    val hardwareName: String = "CPU",
    val inferenceMs: Long = 0L,
    val fps: Float = 0f
)

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var lastMotionTime = 0L
    var appMode = mutableStateOf(AppMode.WALKING)

    override fun onSensorChanged(event: SensorEvent) {
        if (AppConfig.USE_FAKE_MODE) return

        val magnitude = sqrt(
            event.values[0] * event.values[0] +
                    event.values[1] * event.values[1] +
                    event.values[2] * event.values[2]
        )
        val motion = abs(magnitude - 9.8f)

        if (motion > AppConfig.MOTION_THRESHOLD) {
            appMode.value = AppMode.WALKING
            lastMotionTime = System.currentTimeMillis()
        } else if (System.currentTimeMillis() - lastMotionTime > AppConfig.STATIONARY_TIMEOUT_MS) {
            appMode.value = AppMode.STATIONARY
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        FeedbackManager.initTts(this)

        sensorManager = getSystemService(SensorManager::class.java)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let {
            sensorManager.registerListener(this, it, AppConfig.SENSOR_DELAY)
        }

        if (!PermissionHandler.hasCameraPermission(this)) {
            PermissionHandler.requestCameraPermission(
                activity = this,
                onGranted = {},
                onDenied = {}
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
        sensorManager.unregisterListener(this)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalkGuideApp(appMode: MutableState<AppMode>) {
    var detections by remember { mutableStateOf<List<Detection>>(emptyList()) }
    var inferenceStats by remember { mutableStateOf(InferenceStats()) }
    var lastInferenceTime = remember { 0L }
    var logMessages by remember { mutableStateOf<List<String>>(emptyList()) }

    val decisionEngine = remember { DecisionEngine() }
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
            onDetectionResult = { newDetections, _ ->
                val now = System.currentTimeMillis()
                val fps = if (lastInferenceTime > 0L) 1000f / (now - lastInferenceTime) else 0f
                lastInferenceTime = now

                val alerts = decisionEngine.process(newDetections)

                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    detections = newDetections
                    inferenceStats = InferenceStats(
                        hardwareName = inferenceEngine.getCurrentHardwareName(),
                        inferenceMs = inferenceEngine.lastInferenceMs,
                        fps = fps
                    )
                    val logEntry = if (AppConfig.DEBUG_LOG) {
                        if (newDetections.isEmpty()) return@post
                        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                            .format(java.util.Date())
                        "[$time] ${newDetections.map { "${it.label}(${"%.2f".format(it.confidence)}) #${it.trackId}" }}"
                    } else {
                        if (alerts.isEmpty()) return@post
                        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                            .format(java.util.Date())
                        "[$time] ${alerts.map { it.message }}"
                    }
                    logMessages = (logMessages + listOf(logEntry)).takeLast(100)
                }

                alerts.forEach { alert ->
                    FeedbackManager.speak(alert.message)
                    // haptic feedback
//                    if (alert.riskLevel == RiskLevel.CRITICAL) {
//                        FeedbackManager.triggerHaptic(context)
//                    }
                    Log.d("DE_TEST", "Alert: ${alert.message} / ${alert.riskLevel}")
                }

                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    currentRiskState.value = if (alerts.isNotEmpty()) {
                        alerts.maxByOrNull { it.riskLevel.ordinal }?.riskLevel?.name ?: "IGNORE"
                    } else "IGNORE"
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

                        // 클래스별 기본 색상
                        val baseColor = when {
                            AppConfig.STATIC_OBJECTS.contains(detection.label) -> Color.Green
                            AppConfig.DYNAMIC_OBJECTS.contains(detection.label) -> Color.Yellow
                            else -> Color.White
                        }

                        // 위험도별 색상 오버라이드
                        val trackedObj = detection.trackId
                        val bboxColor = when (currentRiskState.value) {
                            "CRITICAL" -> Color.Red
                            "WARNING"  -> Color(0xFFFF6600) // 주황
                            else -> baseColor
                        }

                        drawRect(
                            color = bboxColor,
                            topLeft = androidx.compose.ui.geometry.Offset(left, top),
                            size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.5f)
                        )

                        drawContext.canvas.nativeCanvas.drawText(
                            "${detection.label} ${"%.2f".format(detection.confidence)}",
                            left,
                            if (top > 50f) top - 10f else bottom + 40f,
                            android.graphics.Paint().apply {
                                color = when {
                                    AppConfig.STATIC_OBJECTS.contains(detection.label) ->
                                        android.graphics.Color.GREEN
                                    AppConfig.DYNAMIC_OBJECTS.contains(detection.label) ->
                                        android.graphics.Color.YELLOW
                                    else -> android.graphics.Color.WHITE
                                }
                                textSize = 36f
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
                        color = when (currentRiskState.value) {
                            "CRITICAL" -> Color.Red
                            "WARNING"  -> Color(0xFFFF6600)
                            else -> Color.White
                        },
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
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Text(text = "HW: ${inferenceStats.hardwareName}", color = Color.Yellow, fontSize = 12.sp)
                    Text(text = "${inferenceStats.inferenceMs}ms", color = Color.Yellow, fontSize = 12.sp)
                    Text(text = "${"%.1f".format(inferenceStats.fps)} fps", color = Color.Yellow, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 감지 로그 뷰
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
        }
    }
}