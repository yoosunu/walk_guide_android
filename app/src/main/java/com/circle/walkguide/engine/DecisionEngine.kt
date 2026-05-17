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

    private val trackedObjects = mutableMapOf<Int, TrackedObject>()
    private val lastAlertByLabel = mutableMapOf<String, Long>()

    fun process(detections: List<Detection>, frame: ImageProxy? = null): List<Alert> {
        for (detection in detections) {
            val existing = trackedObjects[detection.trackId]
            if (existing != null) {
                existing.update(detection)
            } else {
                trackedObjects[detection.trackId] = TrackedObject(
                    trackId = detection.trackId,
                    label = detection.label,
                    current = detection
                )
            }
        }

        val currentTrackIds = detections.map { it.trackId }.toSet()
        val toRemove = trackedObjects.keys.filter { it !in currentTrackIds }
        toRemove.forEach { trackedObjects.remove(it) }

        val alerts = mutableListOf<Alert>()

        for ((_, obj) in trackedObjects) {
            if (obj.shouldTriggerDepth() && frame != null) {
                // TODO: Depth 연동
            }

            val risk = assessRisk(obj)
            obj.currentRiskLevel = risk

            // 클래스별 쿨다운 체크
            if (shouldAlert(obj, risk)) {
                val lastLabelAlert = lastAlertByLabel[obj.label] ?: 0L
                if (System.currentTimeMillis() - lastLabelAlert < AppConfig.CLASS_ALERT_COOLDOWN) continue

                alerts.add(buildAlert(obj, risk))
                obj.lastAlertTimestamp = System.currentTimeMillis()
                obj.lastAlertLevel = risk
                lastAlertByLabel[obj.label] = System.currentTimeMillis()
            }
        }

        return alerts
            .sortedByDescending { it.riskLevel.ordinal }
            .distinctBy { it.label }
            .take(1)
    }

    private fun assessRisk(obj: TrackedObject): RiskLevel {
        val area = obj.current.originalBbox.width * obj.current.originalBbox.height

        if (area < AppConfig.RISK_AREA_IGNORE) return RiskLevel.IGNORE
        if (!obj.isStable()) return RiskLevel.IGNORE

        // 충돌 경로 판단: 중앙 50% 범위에 있을 때만 위험
        val centerX = obj.current.originalBbox.x + obj.current.originalBbox.width / 2
        if (centerX < 0.25f || centerX > 0.75f) return RiskLevel.IGNORE

        obj.current.depth?.let { d ->
            return when {
                d < AppConfig.RISK_DEPTH_CRITICAL -> RiskLevel.CRITICAL
                d < AppConfig.RISK_DEPTH_WARNING -> RiskLevel.WARNING
                d < AppConfig.RISK_DEPTH_CAUTION -> RiskLevel.CAUTION
                else -> RiskLevel.IGNORE
            }
        }

        // bbox 하단 y좌표 기반 판단
        val bottomY = obj.current.originalBbox.y + obj.current.originalBbox.height

        return when {
            bottomY > AppConfig.RISK_BOTTOM_CRITICAL -> RiskLevel.CRITICAL
            bottomY > AppConfig.RISK_BOTTOM_WARNING  -> RiskLevel.WARNING
            bottomY > AppConfig.RISK_BOTTOM_CAUTION  -> RiskLevel.CAUTION
            else -> RiskLevel.IGNORE
        }
    }

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
            message = buildMessage(obj.label, obj.current.depth, risk)
        )
    }

    private fun buildMessage(label: String, distance: Float?, risk: RiskLevel): String {
        // 받침 있으면 "이", 없으면 "가"
        val lastChar = label.last()
        val lastCode = lastChar.code - 0xAC00
        val josa = if (lastCode >= 0 && lastCode % 28 != 0) "이" else "가"

        val urgency = when (risk) {
            RiskLevel.CRITICAL -> "위험"
            RiskLevel.WARNING  -> "주의"
            else -> ""
        }
        return if (urgency.isEmpty()) {
            "정면에 ${label}${josa} 있습니다"
        } else {
            "정면 $label $urgency"
        }
    }

    private fun shouldAlert(obj: TrackedObject, risk: RiskLevel): Boolean {
        if (risk == RiskLevel.IGNORE) return false

        if (obj.lastAlertTimestamp == 0L) return true

        val lastLevel = obj.lastAlertLevel ?: return false
        return risk > lastLevel
    }
}