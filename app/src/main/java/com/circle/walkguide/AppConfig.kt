package com.circle.walkguide

import android.hardware.SensorManager

object AppConfig {

    // 추론 설정
    const val INFERENCE_INTERVAL_WALKING = 3
    const val INFERENCE_INTERVAL_STATIONARY = 5

    // 위험도 임계값 (bbox 면적 기반)
    const val RISK_AREA_CRITICAL = 0.3f
    const val RISK_AREA_WARNING = 0.15f
    const val RISK_AREA_CAUTION = 0.05f
    const val RISK_AREA_IGNORE = 0.02f

    // 위험도 임계값 (depth 기반)
    const val RISK_DEPTH_CRITICAL = 1.0f
    const val RISK_DEPTH_WARNING = 2.0f
    const val RISK_DEPTH_CAUTION = 3.0f

    // 알림 쿨다운
    const val ALERT_COOLDOWN_WALKING = 3000L
    const val ALERT_COOLDOWN_STATIONARY = 5000L

    // 모델 설정
    const val MODEL_FILE = "yolo.tflite"
    const val CONFIDENCE_THRESHOLD = 0.5f
    const val INPUT_SIZE = 640

    // 추적 설정
    const val STABLE_FRAME_THRESHOLD = 3
    const val APPROACHING_THRESHOLD = 0.05f
    const val DEPTH_TRIGGER_MIN_AREA = 0.02f
    const val DEPTH_TRIGGER_CENTER_DISTANCE = 0.2f

    // 모드 전환
    const val STATIONARY_TIMEOUT_MS = 3000L // 3초

    // 가속도 센서 threshold
    const val MOTION_THRESHOLD = 0.5f
    // sensor delay
    const val SENSOR_DELAY = SensorManager.SENSOR_DELAY_GAME
}