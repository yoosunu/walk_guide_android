package com.circle.walkguide.utils

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

object FeedbackManager {

    fun triggerHaptic(context: Context) {
        val vibrator = context.getSystemService(Vibrator::class.java)

        vibrator?.vibrate(
            VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
        )
    }
}