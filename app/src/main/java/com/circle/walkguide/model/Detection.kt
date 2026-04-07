package com.circle.walkguide.model

data class Detection(
    val label: String,          // "person", "stair" 등
    val confidence: Float,      // 0.0 ~ 1.0
    val bbox: BBox,             // 위치
    val timestamp: Long,        // 프레임 시간
    val trackId: Int = -1,      // 기본값 -1, 나중에 deepSort
    val depth: Float? = null    // 거리 (없으면 null)
)