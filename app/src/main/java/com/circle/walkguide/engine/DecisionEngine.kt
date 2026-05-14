package com.circle.walkguide.engine
import android.util.Log
import androidx.camera.core.ImageProxy
import com.circle.walkguide.AppConfig
import com.circle.walkguide.model.Detection
import com.circle.walkguide.model.TrackedObject
import com.circle.walkguide.model.Alert
import com.circle.walkguide.model.BBox
import com.circle.walkguide.model.enums.AlertType
import com.circle.walkguide.model.enums.RiskLevel

class DecisionEngine {

    private val trackedObjects = mutableMapOf<Int, TrackedObject>() // Int: trackId

    // pre-processing
    // 지속적으로 trackedObjs를 추가, 업데이트, 제거하는 과정을 거치고
    // 각 trackingObj에 대하여 위험도를 판단하여 알림 줄 것만 필터링 해서 반환
    fun process(detections: List<Detection>, frame: ImageProxy? = null): List<Alert> {
        // 1단계: 이번 detection frame으로 trackedObjects 업데이트
        for (detection in detections) {
            val existing = trackedObjects[detection.trackId] // 지금 파악하고 있는 trackedObjs
            if (existing != null) { // 있는 거면 업데이트
                existing.update(detection)
            } else { // 없으면 인스턴스 생성 후 추가
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

            // Depth 추론 자리 (비전팀 모델 붙으면 구현)
            if (obj.shouldTriggerDepth() && frame != null) {
                // TODO: val depth = depthEngine.run(frame)
                // TODO: obj.current = obj.current.copy(depth = depth)
            }

            val risk = assessRisk(obj)
            obj.currentRiskLevel = risk
            if (shouldAlert(obj, risk)) {
                alerts.add(buildAlert(obj, risk))
                obj.lastAlertTimestamp = System.currentTimeMillis()
                obj.lastAlertLevel = risk
            }
        }

        // 4. 알림 줄 것만 필터링해서 반환
        return alerts
    }

    // 위험도 평가 로직 => 알림 과다 방지
    private fun assessRisk(obj: TrackedObject): RiskLevel {
        val area = obj.current.originalBbox.width * obj.current.originalBbox.height
        Log.d("RISK", "label=${obj.label} area=$area frameCount=${obj.frameCount} stable=${obj.isStable()}")

        // 너무 작으면(멀면) 무시
        if (area < AppConfig.RISK_AREA_IGNORE) {
            Log.d("RISK", "label=${obj.label} area=$area → IGNORE (too small)")
            return RiskLevel.IGNORE
        }

        // 안정적이지 않으면 무시 => 3프레임 미만이면 노이즈 => DeepSort
        if (!obj.isStable()) {
            Log.d("RISK", "label=${obj.label} frameCount=${obj.frameCount} → IGNORE (unstable)")
            return RiskLevel.IGNORE
        }

        // depth 있으면 거리 기반 판단 (for later)
        obj.current.depth?.let { d ->
            return when {
                d < AppConfig.RISK_DEPTH_CRITICAL -> RiskLevel.CRITICAL
                d < AppConfig.RISK_DEPTH_WARNING -> RiskLevel.WARNING
                d < AppConfig.RISK_DEPTH_CAUTION -> RiskLevel.CAUTION
                else -> RiskLevel.IGNORE
            }
        }

        // depth 없으면 bbox 크기 기반 판단
        val result = when {
            area > AppConfig.RISK_AREA_CRITICAL -> RiskLevel.CRITICAL
            area > AppConfig.RISK_AREA_WARNING -> RiskLevel.WARNING
            area > AppConfig.RISK_AREA_CAUTION -> RiskLevel.CAUTION
            else -> RiskLevel.IGNORE
        }
        Log.d("RISK", "label=${obj.label} area=$area frameCount=${obj.frameCount} stable=${obj.isStable()} → $result")
        return result
    }

    // feed_back으로 갈 Alert 인스턴스 생성 및 반환
    private fun buildAlert(obj: TrackedObject, risk: RiskLevel): Alert {
        return Alert(
            trackId = obj.trackId,
            label = obj.label,
            riskLevel = risk,
            alertType = when {
                AppConfig.STATIC_OBJECTS.contains(obj.label) -> AlertType.OBSTACLE
                AppConfig.DYNAMIC_OBJECTS.contains(obj.label) -> AlertType.COLLISION
                else -> AlertType.NONE
            },
            distance = obj.current.depth,
            timestamp = System.currentTimeMillis(),
            message = buildMessage(obj.label, obj.current.depth, obj.current.bbox, risk)
        )
    }

    // for making TTS sentence
    private fun buildMessage(label: String, distance: Float?, bbox: BBox, risk: RiskLevel): String {
        val direction = when {
            bbox.x + bbox.width / 2 < 0.33f -> "왼쪽"
            bbox.x + bbox.width / 2 > 0.66f -> "오른쪽"
            else -> "정면"
        }
        val urgency = when (risk) {
            RiskLevel.CRITICAL -> "위험"
            RiskLevel.WARNING  -> "주의"
            else -> ""
        }
        return if (distance != null) {
            "$direction ${distance.toInt()}미터 앞 $label $urgency".trim()
        } else {
            if (urgency.isEmpty()) "$direction $label"
            else "$direction $label $urgency"
        }
    }

    // cooldown 및 알람 조건 디테일(보완 많이 필요)
    private fun shouldAlert(obj: TrackedObject, risk: RiskLevel): Boolean {
        // IGNORE면 알림 없음
        if (risk == RiskLevel.IGNORE) return false

        val now = System.currentTimeMillis()

        // 마지막 알림으로부터 3초 이내면 스킵 => 나중에 조건 수정 // cooldown
        // 단, 위험도가 높아졌으면 즉시 알림
        if (now - obj.lastAlertTimestamp < AppConfig.ALERT_COOLDOWN_WALKING) {
            val lastLevel = obj.lastAlertLevel ?: return true
            return risk > lastLevel
        }
//        if (now - obj.lastAlertTimestamp < AppConfig.ALERT_COOLDOWN_WALKING) return false

        return true
    }


}