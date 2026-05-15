package com.circle.walkguide

import android.hardware.SensorManager

object AppConfig {

    // debug section
    const val USE_FAKE_DETECT = false  // true면 가짜 데이터, false면 실제 모델
    const val USE_FAKE_MODE = false  // true면 센서 무시, 버튼으로만 모드 전환
    const val USE_DEPTH = false  // true면 Depth 추론, false면 비활성화

    // 추론 설정
    const val INFERENCE_INTERVAL_WALKING = 5
    const val INFERENCE_INTERVAL_STATIONARY = 5

    // 위험도 임계값 (bbox 면적 기반)
    const val RISK_AREA_CAUTION  = 0.04f  // 0.05 → 0.04 (더 멀리서 감지)
    const val RISK_AREA_WARNING  = 0.12f  // 0.15 → 0.12
    const val RISK_AREA_CRITICAL = 0.20f  // 유지
    const val RISK_AREA_IGNORE   = 0.02f  // 유지

    // 위험도 임계값 (depth 기반)
    const val RISK_DEPTH_CRITICAL = 1.0f
    const val RISK_DEPTH_WARNING = 2.0f
    const val RISK_DEPTH_CAUTION = 3.0f

    // 알림 쿨다운
    const val ALERT_COOLDOWN_WALKING = 3000L
    const val ALERT_COOLDOWN_STATIONARY = 5000L

    // model file format
    const val MODEL_FILE_NPU = "yolo_npu.tflite"
    const val MODEL_FILE_CPU = "yolo_cpu.tflite"
    const val MODEL_FILE_GPU = "yolo_gpu.tflite"

    // inference hw selection
    const val USE_CPU = 0
    const val USE_GPU = 1
    const val USE_NPU = 2
    const val INFERENCE_HARDWARE = USE_NPU
//    const val INFERENCE_HARDWARE = USE_CPU
//    const val INFERENCE_HARDWARE = USE_GPU

    // class
    val CLASS_NAMES = listOf(
        "자전거", "볼라드", "차", "턱", "구멍", "킥보드", "사람", "기둥", "계단", "나무"
        // 순서가 중요: 모델 학습 순서와 일치 해야 함.
    )

    val STATIC_OBJECTS = setOf("계단", "턱", "구멍", "볼라드", "기둥")
    val DYNAMIC_OBJECTS = setOf("사람", "킥보드", "자전거", "차")

    // class threshold
    val STATIC_CLASS_THRESHOLD = mapOf(
        "계단"   to 0.80f,
        "턱"   to 0.75f,
        "구멍"   to 0.50f,
        "기둥"   to 0.70f,
        "볼라드" to 0.70f,
        "나무"   to 0.99f
    )

    val DYNAMIC_CLASS_THRESHOLD = mapOf(
        "킥보드" to 0.65f,
        "사람"   to 0.70f,
        "자전거" to 0.70f,
        "차"     to 0.70f,
    )

    // row version for debugging
//    val STATIC_CLASS_THRESHOLD = mapOf(
//        "계단"   to 0.2f,
//        "턱"   to 0.2f,
//        "구멍"   to 0.2f,
//        "기둥"   to 0.2f,
//        "볼라드" to 0.2f,
//        "나무"   to 0.2f
//    )
//
//    val DYNAMIC_CLASS_THRESHOLD = mapOf(
//        "킥보드" to 0.2f,
//        "사람"   to 0.2f,
//        "자전거" to 0.2f,
//        "차"     to 0.2f,
//    )

    const val CONFIDENCE_THRESHOLD = 0.6f
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