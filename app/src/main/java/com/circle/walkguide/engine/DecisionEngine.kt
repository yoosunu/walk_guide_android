package com.circle.walkguide.engine
import com.circle.walkguide.model.Detection
import com.circle.walkguide.model.TrackedObject
import com.circle.walkguide.model.Alert
import com.circle.walkguide.model.enums.AlertType
import com.circle.walkguide.model.enums.RiskLevel

class DecisionEngine {

    private val trackedObjects = mutableMapOf<Int, TrackedObject>() // Int: trackId

    // pre-processing
    fun process(detections: List<Detection>): List<Alert> {
        // 1단계: 이번 detection frame으로 trackedObjects 업데이트
        for (detection in detections) {
            val existing = trackedObjects[detection.trackId]
            if (existing != null) { // 있는 거면 업데이트
                existing.update(detection)
            } else { // 아니면 인스턴스 생성 후 추가
                trackedObjects[detection.trackId] = TrackedObject(
                    trackId = detection.trackId,
                    label = detection.label,
                    current = detection
                )
            }
        }

        // 2단계: 사라진 객체 제거
        val currentTrackIds = detections.map { it.trackId }.toSet()
        val toRemove = trackedObjects.keys.filter { it !in currentTrackIds }
        toRemove.forEach { trackedObjects.remove(it) }

        // 3단계: 위험도 판단 + Alert 생성 => to return
        val alerts = mutableListOf<Alert>()

        for ((_, obj) in trackedObjects) {
            val risk = assessRisk(obj)
            if (shouldAlert(obj, risk)) {
                alerts.add(buildAlert(obj, risk))
                obj.lastAlertTimestamp = System.currentTimeMillis()
                obj.lastAlertLevel = risk
            }
        }

        return alerts
        // 4. 알림 줄 것만 필터링해서 반환
    }

    // 위험도 평가 로직 => 알림 과다 방지
    private fun assessRisk(obj: TrackedObject): RiskLevel {
        val area = obj.current.bbox.width * obj.current.bbox.height

        // 너무 작으면(멀면) 무시
        if (area < 0.02f) return RiskLevel.IGNORE

        // 안정적이지 않으면 무시 => 3프레임 미만이면 노이즈
        if (!obj.isStable()) return RiskLevel.IGNORE

        // depth 있으면 거리 기반 판단 (for later)
        obj.current.depth?.let { d ->
            return when {
                d < 1.0f -> RiskLevel.CRITICAL
                d < 2.0f -> RiskLevel.WARNING
                d < 3.0f -> RiskLevel.CAUTION
                else -> RiskLevel.IGNORE
            }
        }

        // depth 없으면 bbox 크기 기반 판단
        return when {
            area > 0.3f -> RiskLevel.CRITICAL // 30% 이상 => 매우 가까움
            area > 0.15f -> RiskLevel.WARNING
            area > 0.05f -> RiskLevel.CAUTION
            else -> RiskLevel.IGNORE
        }
    }

    // feed_back으로 갈 Alert 인스턴스 생성 및 반환
    private fun buildAlert(obj: TrackedObject, risk: RiskLevel): Alert {
        return Alert(
            trackId = obj.trackId,
            label = obj.label,
            riskLevel = risk,
            alertType = when (obj.label) {
                "person", "kickboard", "car" -> AlertType.COLLISION
                "stairs", "manhole", "curb", "hole" -> AlertType.OBSTACLE
                else -> AlertType.NONE
            },
            distance = obj.current.depth,
            timestamp = System.currentTimeMillis(),
            message = buildMessage(obj.label, obj.current.depth)
        )
    }

    // for making TTS sentence
    private fun buildMessage(label: String, distance: Float?): String {
        val labelKo = when (label) {
            "stairs"  -> "계단"
            "manhole" -> "맨홀"
            "curb"    -> "턱"
            "hole"    -> "구멍"
            "person"  -> "사람"
            "kickboard" -> "킥보드"
            else -> label
        }

        return if (distance != null) {
            "${distance.toInt()}미터 앞 $labelKo"
        } else {
            "$labelKo 주의"
        }
    }

    // cooldown 및 알람 조건 디테일(보완 많이 필요)
    private fun shouldAlert(obj: TrackedObject, risk: RiskLevel): Boolean {
        // IGNORE면 알림 없음
        if (risk == RiskLevel.IGNORE) return false

        val now = System.currentTimeMillis()

        // 마지막 알림으로부터 3초 이내면 스킵 => 나중에 조건 수정 // cooldown
        // 단, 위험도가 높아졌으면 즉시 알림
        if (now - obj.lastAlertTimestamp < 3000L) {
            val lastLevel = obj.lastAlertLevel ?: return true
            return risk > lastLevel
        }

        return true
    }


}