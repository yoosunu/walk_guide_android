package com.circle.walkguide

import android.hardware.SensorManager

object AppConfig {

    // debug section
    const val USE_FAKE_DETECT = false  // true면 가짜 데이터, false면 실제 모델
    const val USE_FAKE_MODE = false  // true면 센서 무시, 버튼으로만 모드 전환
    const val USE_DEPTH = false  // true면 Depth 추론, false면 비활성화
    const val DEBUG_LOG = false  // true면 전체 감지 로그, false면 알림 로그만

    // 추론 설정
    const val INFERENCE_INTERVAL_WALKING = 1
    const val INFERENCE_INTERVAL_STATIONARY = 5

    // 위험도 임계값 (bbox 면적 기반)
    const val RISK_AREA_IGNORE   = 0.005f
//    const val RISK_AREA_CAUTION  = 0.03f  // 0.04 → 0.03
//    const val RISK_AREA_WARNING  = 0.08f  // 0.12 → 0.08
//    const val RISK_AREA_CRITICAL = 0.15f  // 0.20 → 0.15

    // tracking threshold
    const val IOT_THRESHOLD = 0.1f
    const val TRACKER_MAX_MISSED = 10

    // bbox 하단 y좌표 기반 위험도 임계값
    const val RISK_BOTTOM_CAUTION  = 0.30f  // 약 5m
    const val RISK_BOTTOM_WARNING  = 0.45f  // 약 3m
    const val RISK_BOTTOM_CRITICAL = 0.60f  // 약 2m

    // 위험도 임계값 (depth 기반)
    const val RISK_DEPTH_CRITICAL = 1.0f
    const val RISK_DEPTH_WARNING = 2.0f
    const val RISK_DEPTH_CAUTION = 3.0f

    // cooldown section
    const val ALERT_COOLDOWN_WALKING = 5000L
    const val ALERT_COOLDOWN_STATIONARY = 10000L
    const val CLASS_ALERT_COOLDOWN = 2500L

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
        "자전거", "볼라드", "차", "턱", "구멍", "킥보드", "사람", "기둥", "계단"
        // 순서가 중요: 모델 학습 순서와 일치 해야 함.
    )

    val STATIC_OBJECTS = setOf("계단", "턱", "구멍", "볼라드", "기둥")
    val DYNAMIC_OBJECTS = setOf("사람", "킥보드", "자전거", "차")

    // class threshold
    val STATIC_CLASS_THRESHOLD = mapOf(
        "계단"   to 0.70f,
        "턱"   to 0.40f,
        "구멍"   to 0.50f,
        "기둥"   to 0.70f,
        "볼라드" to 0.70f,
    )

    val DYNAMIC_CLASS_THRESHOLD = mapOf(
        "킥보드" to 0.70f,
        "사람"   to 0.30f,
        "자전거" to 0.70f,
        "차"     to 0.70f,
    )

    // row version for debugging
//    val STATIC_CLASS_THRESHOLD = mapOf(
//        "계단"   to 0.50f,
//        "턱"   to 0.2f,
//        "구멍"   to 0.2f,
//        "기둥"   to 0.2f,
//        "볼라드" to 0.2f,
//    )
//
//    val DYNAMIC_CLASS_THRESHOLD = mapOf(
//        "킥보드" to 0.2f,
//        "사람"   to 0.2f,
//        "자전거" to 0.5f,
//        "차"     to 0.2f,
//    )

    const val CONFIDENCE_THRESHOLD = 0.5f
    const val INPUT_SIZE = 640

    // 추적 설정
    const val STABLE_FRAME_THRESHOLD = 3
    const val APPROACHING_THRESHOLD = 0.05f
    const val DEPTH_TRIGGER_MIN_AREA = 0.02f
    const val DEPTH_TRIGGER_CENTER_DISTANCE = 0.2f

    // 모드 전환
    const val STATIONARY_TIMEOUT_MS = 5000L // 2초

    // 가속도 센서 threshold
    const val MOTION_THRESHOLD = 0.7f
    // sensor delay
    const val SENSOR_DELAY = SensorManager.SENSOR_DELAY_GAME


}