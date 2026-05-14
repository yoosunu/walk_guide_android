package com.circle.walkguide.inference

import android.util.Log
import com.circle.walkguide.model.BBox
import com.circle.walkguide.model.Detection

// DeepSORT 대체 IoU 기반 간단한 tracker
// 이전 프레임 bbox와 현재 프레임 bbox의 겹침 비율로 동일 객체 판단
class IouTracker {

    private var nextId = 0
    private val tracks = mutableMapOf<Int, TrackInfo>()

    data class TrackInfo(
        val id: Int,
        val label: String,
        var bbox: BBox,         // 보정된 bbox
        var originalBbox: BBox, // IoU 계산용
        var frameCount: Int = 1,
        var missed: Int = 0
    )

    // Detection 리스트 받아서 trackId 부여
    fun update(detections: List<Detection>): List<Detection> {
        if (detections.isEmpty()) {
            // 감지 없으면 모든 track missed 증가 후 오래된 것 제거
            tracks.values.forEach { it.missed++ }
            tracks.entries.removeIf { it.value.missed > MAX_MISSED }
            return detections
        }

        val matched = mutableSetOf<Int>() // 매칭된 track id
        val result = mutableListOf<Detection>()

        for (det in detections) {
            var bestIou = IOU_THRESHOLD
            var bestId = -1

            // 같은 클래스의 기존 track과 IoU 계산
            for ((id, track) in tracks) {
                if (track.label != det.label) continue
                val iou = computeIou(det.originalBbox, track.originalBbox)
                if (iou > bestIou) {
                    bestIou = iou
                    bestId = id
                }
            }

            if (bestId != -1 && bestId !in matched) {
                // 기존 track 업데이트
                val track = tracks[bestId]!!
                track.bbox = det.bbox
                track.frameCount++
                track.missed = 0
                matched.add(bestId)
                result.add(det.copy(trackId = bestId))
            } else {
                // 새 track 생성
                val newId = nextId++
                tracks[newId] = TrackInfo(
                    id = newId,
                    label = det.label,
                    bbox = det.bbox,
                    originalBbox = det.originalBbox
                )
                Log.d("TRACKER", "새 track 생성: id=$newId label=${det.label}")  // 추가
                result.add(det.copy(trackId = newId))
            }
        }

        // 매칭 안 된 track missed 증가 후 오래된 것 제거
        for ((id, track) in tracks) {
            if (id !in matched) track.missed++
        }
        tracks.entries.removeIf { it.value.missed > MAX_MISSED }

        return result
    }

    fun getFrameCount(trackId: Int): Int {
        return tracks[trackId]?.frameCount ?: 0
    }

    // IoU 계산
    private fun computeIou(a: BBox, b: BBox): Float {
        val ax1 = a.x
        val ay1 = a.y
        val ax2 = a.x + a.width
        val ay2 = a.y + a.height

        val bx1 = b.x
        val by1 = b.y
        val bx2 = b.x + b.width
        val by2 = b.y + b.height

        val interX1 = maxOf(ax1, bx1)
        val interY1 = maxOf(ay1, by1)
        val interX2 = minOf(ax2, bx2)
        val interY2 = minOf(ay2, by2)

        val interArea = maxOf(0f, interX2 - interX1) * maxOf(0f, interY2 - interY1)
        val aArea = a.width * a.height
        val bArea = b.width * b.height
        val unionArea = aArea + bArea - interArea

        return if (unionArea <= 0f) 0f else interArea / unionArea
    }

    companion object {
        const val IOU_THRESHOLD = 0.1f  // 이 이상 겹치면 같은 객체
        const val MAX_MISSED = 10       // 5프레임 안 보이면 track 삭제
    }
}