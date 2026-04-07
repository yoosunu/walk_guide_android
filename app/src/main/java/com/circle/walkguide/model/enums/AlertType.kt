package com.circle.walkguide.model.enums

enum class AlertType {
    NONE,
    OBSTACLE,   // 정적 장애물 (계단, 맨홀, 턱)
    COLLISION,  // 동적 객체 충돌 위험 (사람, 킥보드)
    FALL        // 낙상 감지
}