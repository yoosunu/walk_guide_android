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

class YoloInferenceEngine(private val context: Context) {

    private var interpreter: Interpreter? = null // 모델을 실행하는 엔진
    private var isModelLoaded = false
    private var currentHardware = AppConfig.USE_CPU // 실제로 사용 중인 하드웨어
    var lastInferenceMs: Long = 0L // 외부에서 stats 읽을 수 있게

    // companion object == static
    companion object {
        // MODEL_FILE & CONFIDENCE_THRESHOLD is on the AppConfig
        val CLASS_NAMES = listOf(
            "stairs", "manhole", "curb", "hole", "person", "kickboard"
            // 순서 맞아야 됨.
        )
    }

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
        return if (isModelLoaded) {
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
        // YOLOv11n 출력 shape: [1, 84, 8400]
        // 후에 [1, 10, 8400] 처럼 됨
        // 1: 배치 크기 (한 번에 이미지 1장 처리)
        // 84 = 4(bbox): x, y, w, h + 80(클래스) → 우리 클래스 수에 따라 바뀔 수 있음
        // 8400: 감지 후보군
        val outputPrepareStart = System.nanoTime()
        val outputShape = interpreter.getOutputTensor(0).shape()
        val numAttributes = outputShape[1] // 84
        val numDetections = outputShape[2] // 8400
        val outputPrepareEnd = System.nanoTime()

        // NPU(int8) 모델은 출력도 int8, CPU/GPU는 float32
        val result: List<Detection>

        if (currentHardware == AppConfig.USE_NPU) {
            val output = Array(1) { Array(numAttributes) { ByteArray(numDetections) } }

            val inferenceStart = System.nanoTime()
            interpreter.run(input, output)
            val inferenceEnd = System.nanoTime()

            val postprocessStart = System.nanoTime()
            result = parseOutputInt8(output, numAttributes, numDetections)
            val postprocessEnd = System.nanoTime()

            lastInferenceMs = (inferenceEnd - inferenceStart) / 1_000_000

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
            val output = Array(1) { Array(numAttributes) { FloatArray(numDetections) } } // 모델 결과가 담기는 곳

            val inferenceStart = System.nanoTime()
            interpreter.run(input, output)
            val inferenceEnd = System.nanoTime()

            val postprocessStart = System.nanoTime()
            result = parseOutput(output, numAttributes, numDetections)
            val postprocessEnd = System.nanoTime()

            lastInferenceMs = (inferenceEnd - inferenceStart) / 1_000_000

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

        return result
    }

    // float32 모델 결과 파싱 (CPU / GPU)
    private fun parseOutput(
        output: Array<Array<FloatArray>>,
        numAttributes: Int,
        numDetections: Int
    ): List<Detection> {
        val detections = mutableListOf<Detection>()
        val numClasses = numAttributes - 4 // bbox 4개 제외

        for (i in 0 until numDetections) {
            val x = output[0][0][i]
            val y = output[0][1][i]
            val w = output[0][2][i]
            val h = output[0][3][i]

            // 클래스별 confidence 중 가장 높은 것
            var maxConf = 0f
            var maxIdx = 0
            for (c in 0 until numClasses) {
                val conf = output[0][4 + c][i]
                if (conf > maxConf) {
                    maxConf = conf
                    maxIdx = c
                }
            }

            // threshold 이하 필터링
            if (maxConf < AppConfig.CONFIDENCE_THRESHOLD) continue

            // 클래스 인덱스가 범위 벗어나면 무시
            val label = CLASS_NAMES.getOrNull(maxIdx) ?: continue

            detections.add(
                Detection(
                    label = label,
                    confidence = maxConf,
                    bbox = BBox(
                        x = (x - w / 2f).coerceIn(0f, 1f), // center → top-left 변환
                        y = (y - h / 2f).coerceIn(0f, 1f),
                        width = w.coerceIn(0f, 1f),
                        height = h.coerceIn(0f, 1f)
                    ),
                    timestamp = System.currentTimeMillis(),
                    trackId = -1 // DeepSORT 붙기 전까지 -1
                )
            )
        }
        return detections
    }

    // int8 모델 결과 파싱 (NPU)
    // int8 값을 float으로 역정규화해서 처리
    private fun parseOutputInt8(
        output: Array<Array<ByteArray>>,
        numAttributes: Int,
        numDetections: Int
    ): List<Detection> {
        val detections = mutableListOf<Detection>()
        val numClasses = numAttributes - 4 // bbox 4개 제외

        for (i in 0 until numDetections) {
            val x = output[0][0][i].toFloat() / 127f // int8 → float 역정규화
            val y = output[0][1][i].toFloat() / 127f
            val w = output[0][2][i].toFloat() / 127f
            val h = output[0][3][i].toFloat() / 127f

            // 클래스별 confidence 중 가장 높은 것
            var maxConf = 0f
            var maxIdx = 0
            for (c in 0 until numClasses) {
                val conf = output[0][4 + c][i].toFloat() / 127f
                if (conf > maxConf) {
                    maxConf = conf
                    maxIdx = c
                }
            }

            // threshold 이하 필터링
            if (maxConf < AppConfig.CONFIDENCE_THRESHOLD) continue

            // 클래스 인덱스가 범위 벗어나면 무시
            val label = CLASS_NAMES.getOrNull(maxIdx) ?: continue

            detections.add(
                Detection(
                    label = label,
                    confidence = maxConf,
                    bbox = BBox(
                        x = (x - w / 2f).coerceIn(0f, 1f), // center → top-left 변환
                        y = (y - h / 2f).coerceIn(0f, 1f),
                        width = w.coerceIn(0f, 1f),
                        height = h.coerceIn(0f, 1f)
                    ),
                    timestamp = System.currentTimeMillis(),
                    trackId = -1 // DeepSORT 붙기 전까지 -1
                )
            )
        }
        return detections
    }

    // 가짜 추론 (테스트 용)
    private fun fakeDetect(): List<Detection> {
        return listOf(
            Detection(
                label = "stairs",
                confidence = 0.91f,
                bbox = BBox(0.3f, 0.5f, 0.4f, 0.4f),
                timestamp = System.currentTimeMillis(),
                trackId = 1
            )
        )
    }
}