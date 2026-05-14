package com.circle.walkguide.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

class DepthInferenceEngine(private val context: Context) {

    private var ortSession: OrtSession? = null
    private val ortEnv = OrtEnvironment.getEnvironment()
    var lastInferenceMs: Long = 0L

    // ImageNet 정규화 값
    private val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val std  = floatArrayOf(0.229f, 0.224f, 0.225f)

    private val INPUT_SIZE = 256

    // 버퍼 재사용 (매 프레임 새로 할당하지 않음)
    private val floatBuffer = FloatBuffer.allocate(1 * 3 * INPUT_SIZE * INPUT_SIZE)
    private val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            val modelBytes = context.assets.open("depth.onnx").readBytes()
            val options = OrtSession.SessionOptions()
            ortSession = ortEnv.createSession(modelBytes, options)
            Log.d("DEPTH", "Depth 모델 로드 성공")
        } catch (e: Exception) {
            Log.d("DEPTH", "Depth 모델 로드 실패: ${e.message}")
        }
    }

    // bitmap을 받아서 깊이 맵 반환
    // 반환값: 2D float 배열 [H][W], 각 픽셀의 미터 단위 깊이값
    fun infer(bitmap: Bitmap): Array<FloatArray>? {
        val session = ortSession ?: return null

        val start = System.currentTimeMillis()

        // 전처리: 518×518 리사이즈 → ImageNet 정규화 → float 버퍼
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val inputTensor = preprocess(resized)

        // 추론
        val inputName = session.inputNames.iterator().next()
        val inputs = mapOf(inputName to inputTensor)
        val results = session.run(inputs)

        lastInferenceMs = System.currentTimeMillis() - start
        Log.d("DEPTH", "Depth 추론시간: ${lastInferenceMs}ms")

        // 출력: [1, 1, H, W] → [H][W] 2D 배열
        val outputTensor = results[0].value as Array<Array<FloatArray>>
        inputTensor.close()
        results.close()

        // 출력 확인용
        val sample = outputTensor[0]
        Log.d("DEPTH", "깊이 샘플 [중앙]: ${sample[INPUT_SIZE/2][INPUT_SIZE/2]}m")
        Log.d("DEPTH", "깊이 샘플 [좌상]: ${sample[0][0]}m")
        Log.d("DEPTH", "깊이 샘플 [우하]: ${sample[INPUT_SIZE-1][INPUT_SIZE-1]}m")

        return outputTensor[0]
    }

    // bbox 중심 하단 픽셀의 깊이값 샘플링
    // x1, y1, x2, y2는 정규화 좌표 (0~1)
    fun sampleDepth(depthMap: Array<FloatArray>, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val cx = ((x1 + x2) / 2 * INPUT_SIZE).toInt().coerceIn(0, INPUT_SIZE - 1)
        val cy = (y2 * 0.95f * INPUT_SIZE).toInt().coerceIn(0, INPUT_SIZE - 1)
        return depthMap[cy][cx]
    }

    // 전처리: 픽셀 한 번만 순회해서 CHW 포맷으로 채우기
    // CHW = Channel, Height, Width (ONNX 표준 포맷)
    private fun preprocess(bitmap: Bitmap): OnnxTensor {
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        floatBuffer.clear()
        val array = floatBuffer.array()
        val gOffset = INPUT_SIZE * INPUT_SIZE
        val bOffset = INPUT_SIZE * INPUT_SIZE * 2

        for (i in pixels.indices) {
            val pixel = pixels[i]
            array[i]           = (((pixel shr 16) and 0xFF) / 255.0f - mean[0]) / std[0] // R
            array[gOffset + i] = (((pixel shr 8)  and 0xFF) / 255.0f - mean[1]) / std[1] // G
            array[bOffset + i] = ((pixel and 0xFF)          / 255.0f - mean[2]) / std[2] // B
        }

        floatBuffer.rewind()

        val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        return OnnxTensor.createTensor(ortEnv, floatBuffer, shape)
    }
}