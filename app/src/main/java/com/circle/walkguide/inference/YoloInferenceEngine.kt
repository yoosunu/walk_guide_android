package com.circle.walkguide.inference

import android.content.Context
import androidx.camera.core.ImageProxy
import com.circle.walkguide.AppConfig
import com.circle.walkguide.model.BBox
import com.circle.walkguide.model.Detection
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil

// 현재는 가짜 추론 결과를 뱉는 버전
// 추후에 모델 오면 교체
class YoloInferenceEngine(private val context: Context) {

    private var interpreter: Interpreter? = null // 모델을 실행하는 엔진
    private var isModelLoaded = false // 모델 붙으면 true로 변경

    // companion object == static
    companion object {
        // MODEL_FILE & CONFIDENCE_THRESHOLD is on the AppConfig
        val CLASS_NAMES = listOf(
            "stairs", "manhole", "curb", "hole", "person", "kickboard"
            // 순서 맞아야 됨.
        )
    }

    // init => 인스턴스 생성시 자동 실행
    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            val model = FileUtil.loadMappedFile(context, AppConfig.MODEL_FILE)
            interpreter = Interpreter(model)
            isModelLoaded = true
        } catch (e: Exception) {
            e.printStackTrace()
            isModelLoaded = false
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
    private fun realDetect(imageProxy: ImageProxy): List<Detection> {
        val interpreter = interpreter ?: return emptyList() // 모델 로드 안 됐으면 추론 x

        // 입력 준비 (전처리) 프레임 → 640x640 → 0~1 정규화 → ByteBuffer
        val input = ImagePreprocessor.process(imageProxy)

        // 출력 버퍼 준비
        // YOLOv11n 출력 shape: [1, 84, 8400]
        // 후에 [1, 10, 8400] 처럼 됨
        // 1: 배치 크기 (한 번에 이미지 1장 처리)
        // 84 = 4(bbox): x, y, w, h + 80(클래스) → 우리 클래스 수에 따라 바뀔 수 있음: 클래스 0일 확률 ~ 클래스 79일 확률
        // 8400: 감지 후보군
        val outputShape = interpreter.getOutputTensor(0).shape()
        val numAttributes = outputShape[1] // 84
        val numDetections = outputShape[2] // 8400
        val output = Array(1) { Array(numAttributes) { FloatArray(numDetections) } } // 모델 결과가 담기는 곳

        // 추론 실행
        interpreter.run(input, output)

        // 결과 파싱
        return parseOutput(output, numAttributes, numDetections)
    }

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