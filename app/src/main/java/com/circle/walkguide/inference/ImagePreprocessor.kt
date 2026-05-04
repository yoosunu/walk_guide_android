package com.circle.walkguide.inference

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageProxy
import com.circle.walkguide.AppConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ImagePreprocessor {

    private const val INPUT_SIZE = AppConfig.INPUT_SIZE
    private const val PIXEL_SIZE = 3

    private val int8Buffer: ByteBuffer =
        ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * PIXEL_SIZE)
            .order(ByteOrder.nativeOrder())

    private val floatBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * PIXEL_SIZE * 4)
            .order(ByteOrder.nativeOrder())

    private val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)

    @Synchronized
    fun process(
        imageProxy: ImageProxy,
        hardwareMode: Int = AppConfig.INFERENCE_HARDWARE
    ): ByteBuffer {
        val bitmapStart = System.nanoTime()         // 추가
        val bitmap = imageProxy.toBitmap()
        val bitmapEnd = System.nanoTime()           // 추가
        Log.d("PERF", "toBitmap: ${(bitmapEnd - bitmapStart) / 1_000_000}ms")  // 추가

        val resized = Bitmap.createScaledBitmap(
            bitmap,
            INPUT_SIZE,
            INPUT_SIZE,
            false
        )

        return when (hardwareMode) {
            AppConfig.USE_NPU -> processInt8(resized)
            else -> processFloat32(resized)
        }
    }

    private fun processInt8(bitmap: Bitmap): ByteBuffer {
        int8Buffer.clear()

        bitmap.getPixels(
            pixels,
            0,
            INPUT_SIZE,
            0,
            0,
            INPUT_SIZE,
            INPUT_SIZE
        )

        for (pixel in pixels) {
            int8Buffer.put(((pixel shr 16) and 0xFF).toByte()) // R
            int8Buffer.put(((pixel shr 8) and 0xFF).toByte())  // G
            int8Buffer.put((pixel and 0xFF).toByte())          // B
        }

        int8Buffer.rewind()
        return int8Buffer
    }

    private fun processFloat32(bitmap: Bitmap): ByteBuffer {
        floatBuffer.clear()

        bitmap.getPixels(
            pixels,
            0,
            INPUT_SIZE,
            0,
            0,
            INPUT_SIZE,
            INPUT_SIZE
        )

        for (pixel in pixels) {
            floatBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            floatBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
            floatBuffer.putFloat((pixel and 0xFF) / 255.0f)
        }

        floatBuffer.rewind()
        return floatBuffer
    }
}