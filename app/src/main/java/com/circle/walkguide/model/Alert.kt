package com.circle.walkguide.model

import com.circle.walkguide.model.enums.AlertType
import com.circle.walkguide.model.enums.RiskLevel

data class Alert(
    val trackId: Int,           // 어떤 객체에 대한 알림인지 for avoid duplicated alarm
    val label: String,          // 뭔지 (계단, 맨홀 등) for tts
    val riskLevel: RiskLevel,   // 얼마나 위험한지 for DE
    val alertType: AlertType,   // 어떤 종류의 알림인지
    val distance: Float?,       // 얼마나 멀리 (없으면 null)
    val timestamp: Long,        // 언제 만들어진 알림인지 for avoid old alarm
    val message: String         // TTS가 읽을 텍스트
)