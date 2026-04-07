package com.circle.walkguide.model

import com.circle.walkguide.model.enums.RiskLevel

data class TrackedObject(
    val trackId: Int,
    val label: String,

    var current: Detection, // current state
    var previous: Detection? = null, // previous state

    var frameCount: Int = 1, // 몇 번 유지된 frame 인지 (몇 번 같은 걸로 감지 됐는지)

    var lastAlertTimestamp: Long = 0L,
    var lastAlertLevel: RiskLevel? = null
) {

    fun update(newDetection: Detection) {
        previous = current
        current = newDetection
        frameCount++
    }

    fun isApproaching(threshold: Float = 0.05f): Boolean {
        val prev = previous ?: return false

        val prevArea = prev.bbox.width * prev.bbox.height
        val currArea = current.bbox.width * current.bbox.height
        val growthRate = (currArea - prevArea) / prevArea

        // 5% 이상 커졌을 때만 접근 중으로 판단
        if (growthRate > threshold) return true

        if (prev.depth != null && current.depth != null) {
            return current.depth!! < prev.depth!!
        }

        return false
    }

    fun isStable(threshold: Int = 3): Boolean {
        return frameCount >= threshold
    }

    // 조건부 추론 트리거
    fun shouldTriggerDepth(): Boolean {
        val area = current.bbox.width * current.bbox.height
        if (area < 0.02f) return false // 화면의 2% 미만이면 무시 => 너무 멀면 무시.
        if (!isStable()) return false // 3프레임 미만이면 무시. 잠깐 나타난 것은 노이즈일 수 있음.
        if (label !in setOf("person", "kickboard", "car")) return false // 동적 객체 아니면 무시. => depth 추론 불 필요.
        if (isApproaching()) return true // bbox 빠르게 커지며 접근 중이면 depth 추론 해야 됨.

        val centerX = current.bbox.x + current.bbox.width / 2
        val centerY = current.bbox.y + current.bbox.height / 2
        val distFromCenter = Math.abs(centerX - 0.5f) + Math.abs(centerY - 0.5f) // 중앙에서 얼마나 떨어져 있는지.
        if (distFromCenter < 0.2f) return true // 충돌 가능성 높으니 depth 필요.

        return false // 위조건 다 통과 못하면 depth 불필요
    }
}