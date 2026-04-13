package com.circle.walkguide.inference

import android.content.Context
import androidx.camera.core.ImageProxy
import com.circle.walkguide.model.BBox
import com.circle.walkguide.model.Detection

// 현재는 가짜 추론 결과를 뱉는 버전
// 추후에 모델 오면 교체
class YoloInferenceEngine(private val context: Context) {

    private val isModelLoaded = false // 모델 붙으면 true로 변경

    // companion object == static
    companion object {
        // MODEL_FILE & CONFIDENCE_THRESHOLD is on the AppConfig
        val CLASS_NAMES = listOf(
            "stairs", "manhole", "curb", "hole", "person", "kickboard"
        )
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
        val input = ImagePreprocessor.process(imageProxy)
        // TODO: TFLite 추론 실행
        return emptyList()
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