package com.circle.walkguide.inference

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.nio.ByteOrder

// object == singleTon : 앱 전체에서 인스턴스 하나만 존재.
// ImageProxy   CameraX가 주는 프레임
// Bitmap       이미지 조작 가능한 형태
// 640x640      YOLO 입력 사이즈로 맞춤
// ByteBuffer   모델이 받을 수 있는 형태
// 픽셀값 0~255 → 0.0~1.0 정규화
object ImagePreprocessor {

    private const val INPUT_SIZE = 640
    private const val PIXEL_SIZE = 3 // RGB

    // preProcessing
    fun process(imageProxy: ImageProxy): ByteBuffer {
        // 1. ImageProxy → Bitmap 변환
        val bitmap = imageProxy.toBitmap()

        // 2. 640x640으로 리사이즈
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)

        // 3. ByteBuffer 준비 (float32 = 4바이트)
        val buffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * PIXEL_SIZE * 4)
        buffer.order(ByteOrder.nativeOrder())

        // 4. 픽셀값 0~255 → 0.0~1.0 정규화 후 버퍼에 넣기
        for (y in 0 until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                val pixel = resized.getPixel(x, y)
                buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R
                buffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)  // G
                buffer.putFloat((pixel and 0xFF) / 255.0f) // B
            }
        }

        buffer.rewind()
        return buffer
    }
}