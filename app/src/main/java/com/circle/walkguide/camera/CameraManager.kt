package com.circle.walkguide.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.circle.walkguide.AppConfig
import com.circle.walkguide.inference.YoloInferenceEngine
import com.circle.walkguide.model.Detection
import java.util.concurrent.Executors


class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner, // 앱 background, foreground 상태 연동
    private val inferenceEngine: YoloInferenceEngine, // 프레임 받으면 yolo 추론 엔진에 던짐
    private val onDetectionResult: (List<Detection>) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor() // camera frame 처리를 위한 별도 스레드
    private var frameCount = 0 // 3 프레임에 한 번. 현재 몇 프레임째인지 세는 변수

    fun start(previewView: PreviewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // 프리뷰 (UI camera 보여줌)
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            // 추론 => 프레임을 분석하는 파이프라인
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // STRATEGY_KEEP_ONLY_LATEST => 실시간성 유지. 추론이 느려서 프레임 밀리면 오래된 프레임 버리고 최신 프레임만 처리.
                .build()
                .also { analysis ->
                    // 매 프레임마다 실행되는 로직
                    analysis.setAnalyzer(executor) { imageProxy ->
                        frameCount++
                        if (frameCount % AppConfig.INFERENCE_INTERVAL_WALKING == 0) { // 3프레임에 1번 추론
                            val detections = inferenceEngine.detect(imageProxy) // 추론 실행
                            onDetectionResult(detections) // 결과를 콜백으로 넘김 => DE와의 연결점
                        }
                        imageProxy.close() // 안 닫으면 다음 프레임 못받음
                    }
                }

            try {
                // camera 생명 주기 바인딩
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                    // 화면에 preview 보여주면서 추론도 동시에 진행
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        executor.shutdown() // 앱 종료시 thread 정리
    }
}