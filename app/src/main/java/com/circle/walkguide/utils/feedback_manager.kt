package com.circle.walkguide.utils

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import java.util.Locale

// definition of FeedbackManager (TTS + Haptic)
object FeedbackManager {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    // init
    fun initTts(context: Context) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
                isTtsReady = true
            }
        }
    }

    // speak
    // QUEUE_FLUSH가 stop() 기능을 포함함. 따로 구현 필요 없음.
    fun speak(message: String) {
        if (!isTtsReady) return
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    // 메모리 누수 등 방지를 위해 앱 종료시 onDestory()에서 호출
    fun shutdown() {
        tts?.shutdown()
        tts = null
        isTtsReady = false
    }

    fun triggerHaptic(context: Context) {
        val vibrator = context.getSystemService(Vibrator::class.java)
        vibrator?.vibrate(
            VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
        )
    }
}