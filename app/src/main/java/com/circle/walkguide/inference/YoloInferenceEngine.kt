package com.circle.walkguide.inference

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageProxy
import com.circle.walkguide.AppConfig
import com.circle.walkguide.model.BBox
import com.circle.walkguide.model.Detection
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.DataType

class YoloInferenceEngine(private val context: Context) {

    // init
    private var interpreter: Interpreter? = null // 모델을 실행하는 엔진
    private var isModelLoaded = false
    private var currentHardware = AppConfig.USE_CPU // 실제로 사용 중인 하드웨어 => default: cpu
    var lastInferenceMs: Long = 0L // 외부에서 stats 읽을 수 있게

    private val tracker = IouTracker() // deepSort

    // init => 인스턴스 생성시 자동 실행
    // AppConfig.INFERENCE_HARDWARE 값으로 하드웨어 지정
    // 실패하면 CPU fallback
    init {
        loadSpecific(AppConfig.INFERENCE_HARDWARE)
    }

    fun getCurrentHardwareName(): String {
        return when (currentHardware) {
            AppConfig.USE_GPU -> "GPU"
            AppConfig.USE_NPU -> "NPU"
            else -> "CPU"
        }
    }

    // AppConfig에서 지정한 하드웨어로만 시도
    // 실패하면 CPU fallback
    private fun loadSpecific(hardwareMode: Int) {
        if (tryLoad(hardwareMode)) return
        if (hardwareMode != AppConfig.USE_CPU) {
            Log.d("INFERENCE", "지정 하드웨어 실패 → CPU fallback")
            tryLoad(AppConfig.USE_CPU)
        }
        if (!isModelLoaded) Log.d("INFERENCE", "모델 로드 실패")
    }

    // 특정 하드웨어로 로드 시도, 성공 여부 반환
    private fun tryLoad(hardwareMode: Int): Boolean {
        return try {
            // 하드웨어에 맞는 모델 파일 선택
            val modelFile = when (hardwareMode) {
                AppConfig.USE_NPU -> AppConfig.MODEL_FILE_NPU
                AppConfig.USE_GPU -> AppConfig.MODEL_FILE_GPU
                else -> AppConfig.MODEL_FILE_CPU
            }

            val model = FileUtil.loadMappedFile(context, modelFile)
            val options = Interpreter.Options()

            when (hardwareMode) {
                AppConfig.USE_NPU -> {
                    options.addDelegate(NnApiDelegate())
                    Log.d("INFERENCE", "NPU 시도 중...")
                }
                AppConfig.USE_GPU -> {
                    options.addDelegate(GpuDelegate())
                    Log.d("INFERENCE", "GPU 시도 중...")
                }
                else -> {
                    options.numThreads = 4
                    Log.d("INFERENCE", "CPU 시도 중...")
                }
            }

            interpreter = Interpreter(model, options)
            isModelLoaded = true
            currentHardware = hardwareMode
            Log.d("INFERENCE", "모델 로드 성공: ${getCurrentHardwareName()}")
            true

        } catch (e: Exception) {
            Log.d("INFERENCE", "하드웨어 $hardwareMode 실패: ${e::class.simpleName} / ${e.message} / ${e.cause}")
            false
        }
    }

    // Real Detect || Fake Detect
    fun detect(imageProxy: ImageProxy): List<Detection> {
        return if (isModelLoaded && !AppConfig.USE_FAKE_DETECT) {
            realDetect(imageProxy)
        } else {
            fakeDetect()
        }
    }

    // 실제 추론 (모델 붙으면 여기 구현)
    @Synchronized
    private fun realDetect(imageProxy: ImageProxy): List<Detection> {
        val interpreter = interpreter ?: return emptyList() // 모델 로드 안 됐으면 추론 x

        // for logging
        val totalStart = System.nanoTime()

        // 입력 준비 (전처리) 프레임 → 640x640 → 0~1 정규화 → ByteBuffer
        // NPU면 int8, CPU/GPU면 float32 자동 전환
        val preprocessStart = System.nanoTime()
        val input = ImagePreprocessor.process(imageProxy, currentHardware)
        val preprocessEnd = System.nanoTime()

        // 출력 버퍼 준비
        // 이 모델의 출력 shape: [1, 300, 6]
        // 300: 감지 후보 수
        // 6: x1, y1, x2, y2, confidence, class_id
        val outputPrepareStart = System.nanoTime()

        val outputTensor = interpreter.getOutputTensor(0)
        val outputShape = outputTensor.shape()
        val outputType = outputTensor.dataType()
        val outputQuant = outputTensor.quantizationParams()

        val numDetections = outputShape[1]
        val numValues = outputShape[2]

        val outputPrepareEnd = System.nanoTime()

        // NPU(int8) 모델은 출력도 int8, CPU/GPU는 float32
        val result: List<Detection>

        if (currentHardware == AppConfig.USE_NPU && outputType == DataType.INT8) {
            val output = Array(1) {
                Array(numDetections) {
                    ByteArray(numValues)
                }
            }

            val inferenceStart = System.nanoTime()
            interpreter.run(input, output)
            val inferenceEnd = System.nanoTime()

            val postprocessStart = System.nanoTime()
            result = parseOutputInt8NPU(
                output,
                numDetections,
                numValues,
                outputQuant.scale,
                outputQuant.zeroPoint
            )
            val postprocessEnd = System.nanoTime()

//            lastInferenceMs = (inferenceEnd - inferenceStart) / 1_000_000
            lastInferenceMs = (postprocessEnd - totalStart) / 1_000_000


            Log.d(
                "PERF",
                "[${getCurrentHardwareName()}] " +
                        "preprocess=${(preprocessEnd - preprocessStart) / 1_000_000}ms, " +
                        "outputPrepare=${(outputPrepareEnd - outputPrepareStart) / 1_000_000}ms, " +
                        "inference=${(inferenceEnd - inferenceStart) / 1_000_000}ms, " +
                        "postprocess=${(postprocessEnd - postprocessStart) / 1_000_000}ms, " +
                        "total=${(postprocessEnd - totalStart) / 1_000_000}ms"
            )
            Log.d("INFERENCE", "[${getCurrentHardwareName()}] 추론시간: ${lastInferenceMs}ms")

        } else {
            val output = Array(1) {
                Array(numDetections) {
                    FloatArray(numValues)
                }
            }

            val inferenceStart = System.nanoTime()
            interpreter.run(input, output)
            val inferenceEnd = System.nanoTime()

            val postprocessStart = System.nanoTime()
            result = parseOutput(output, numDetections)
            val postprocessEnd = System.nanoTime()

//            lastInferenceMs = (inferenceEnd - inferenceStart) / 1_000_000
            lastInferenceMs = (postprocessEnd - totalStart) / 1_000_000

            Log.d(
                "PERF",
                "[${getCurrentHardwareName()}] " +
                        "preprocess=${(preprocessEnd - preprocessStart) / 1_000_000}ms, " +
                        "outputPrepare=${(outputPrepareEnd - outputPrepareStart) / 1_000_000}ms, " +
                        "inference=${(inferenceEnd - inferenceStart) / 1_000_000}ms, " +
                        "postprocess=${(postprocessEnd - postprocessStart) / 1_000_000}ms, " +
                        "total=${(postprocessEnd - totalStart) / 1_000_000}ms"
            )
            Log.d("INFERENCE", "[${getCurrentHardwareName()}] 추론시간: ${lastInferenceMs}ms")
        }

        return tracker.update(result).also { tracked ->
            if (tracked.isNotEmpty()) {
                Log.d("DETECTION", "감지: ${tracked.size}개 / ${tracked.map { "${it.label}(${String.format("%.2f", it.confidence)}) id=${it.trackId}" }}")
            }
        }
    }

    // float32 모델 출력 파싱 (CPU / GPU)
    // output shape: [1, 300, 6]
    // 각 detection: [x1, y1, x2, y2, confidence, class_id]
    // x1, y1, x2, y2는 픽셀 단위 → INPUT_SIZE로 나눠서 정규화
    private fun parseOutput(
        output: Array<Array<FloatArray>>,
        numDetections: Int
    ): List<Detection> {
        val detections = mutableListOf<Detection>()

        for (i in 0 until numDetections) {
            val x1      = output[0][i][0]
            val y1      = output[0][i][1]
            val x2      = output[0][i][2]
            val y2      = output[0][i][3]
            val conf    = output[0][i][4]
            val classId = output[0][i][5].toInt()

            if (classId < 0 || classId >= AppConfig.CLASS_NAMES.size) continue

            // 클래스 인덱스가 범위 벗어나면 무시
            val label = AppConfig.CLASS_NAMES.getOrNull(classId) ?: continue

            // 클래스별 threshold 적용
            val threshold = AppConfig.STATIC_CLASS_THRESHOLD[label]
                ?: AppConfig.DYNAMIC_CLASS_THRESHOLD[label]
                ?: AppConfig.CONFIDENCE_THRESHOLD
            if (conf < threshold) continue

            // for debug
            Log.d("BBOX", "x1=$x1, y1=$y1, x2=$x2, y2=$y2, conf=$conf, class=$classId")

            val isPixelCoord = x1 > 1.0f || x2 > 1.0f || y2 > 1.0f

            val originalBbox = if (isPixelCoord) {
                BBox(
                    x = (x1 / AppConfig.INPUT_SIZE).coerceIn(0f, 1f),
                    y = (y1 / AppConfig.INPUT_SIZE).coerceIn(0f, 1f),
                    width = ((x2 - x1) / AppConfig.INPUT_SIZE).coerceIn(0f, 1f),
                    height = ((y2 - y1) / AppConfig.INPUT_SIZE).coerceIn(0f, 1f)
                )
            } else {
                BBox(
                    x = x1.coerceIn(0f, 1f),
                    y = y1.coerceIn(0f, 1f),
                    width = (x2 - x1).coerceIn(0f, 1f),
                    height = (y2 - y1).coerceIn(0f, 1f)
                )
            }

            val correctedBbox = if (label == "턱") {
                val cx = if (isPixelCoord) (x1 + x2) / 2 / AppConfig.INPUT_SIZE
                else (x1 + x2) / 2
                val cy = if (isPixelCoord) (y1 + y2) / 2 / AppConfig.INPUT_SIZE
                else (y1 + y2) / 2
                val size = 0.15f
                BBox(
                    x = (cx - size / 2).coerceIn(0f, 1f),
                    y = (cy - size / 2).coerceIn(0f, 1f),
                    width = size,
                    height = size
                )
            } else originalBbox

            detections.add(
                Detection(
                    label = label,
                    confidence = conf,
                    bbox = correctedBbox,
                    originalBbox = originalBbox,
                    timestamp = System.currentTimeMillis(),
                    trackId = -1
                )
            )
        }

        return detections
    }

    // NPU int8 모델 출력 파싱
    // output shape: [1, 300, 6]
    // 각 detection: [x1, y1, x2, y2, confidence, class_id] (int8 → unsigned 변환)
    private fun parseOutputInt8NPU(
        output: Array<Array<ByteArray>>,
        numDetections: Int,
        numValues: Int,
        scale: Float,
        zeroPoint: Int
    ): List<Detection> {
        val detections = mutableListOf<Detection>()

        fun dequantize(value: Byte): Float {
            return (value.toInt() - zeroPoint) * scale
        }

        for (i in 0 until numDetections) {

            val x1 = dequantize(output[0][i][0])
            val y1 = dequantize(output[0][i][1])
            val x2 = dequantize(output[0][i][2])
            val y2 = dequantize(output[0][i][3])
            val conf = dequantize(output[0][i][4])
            val classId = dequantize(output[0][i][5]).toInt()

            if (i < 10) {
                Log.d(
                    "NPU_OUT",
                    "i=$i x1=$x1 y1=$y1 x2=$x2 y2=$y2 conf=$conf cls=$classId"
                )
            }

            if (classId < 0 || classId >= AppConfig.CLASS_NAMES.size) continue

            val label = AppConfig.CLASS_NAMES.getOrNull(classId) ?: continue

            val threshold = AppConfig.STATIC_CLASS_THRESHOLD[label]
                ?: AppConfig.DYNAMIC_CLASS_THRESHOLD[label]
                ?: AppConfig.CONFIDENCE_THRESHOLD

            if (conf < threshold) continue

            detections.add(
                Detection(
                    label = label,
                    confidence = conf,
                    bbox = BBox(
                        x = x1.coerceIn(0f, 1f),
                        y = y1.coerceIn(0f, 1f),
                        width = (x2 - x1).coerceIn(0f, 1f),
                        height = (y2 - y1).coerceIn(0f, 1f)
                    ),
                    timestamp = System.currentTimeMillis(),
                    trackId = -1
                )
            )
        }

        if (detections.isNotEmpty()) {
            Log.d(
                "DETECTION",
                "감지: ${detections.size}개 / ${
                    detections.map { "${it.label}(${String.format("%.2f", it.confidence)})" }
                }"
            )
        }

        return detections
    }

    // 가짜 추론 (테스트 용)
    private fun fakeDetect(): List<Detection> {
        return listOf(
            Detection(
                label = "계단",
                confidence = 0.91f,
                bbox = BBox(0.3f, 0.5f, 0.4f, 0.4f),
                timestamp = System.currentTimeMillis(),
                trackId = 1
            )
        )
    }
}