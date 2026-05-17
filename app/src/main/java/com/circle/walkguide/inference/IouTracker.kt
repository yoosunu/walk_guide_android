package com.circle.walkguide.inference

import android.util.Log
import com.circle.walkguide.AppConfig
import com.circle.walkguide.model.BBox
import com.circle.walkguide.model.Detection

class IouTracker {

    private var nextId = 0
    private val tracks = mutableMapOf<Int, TrackInfo>()

    data class KalmanState(
        var x: Float, var y: Float, var w: Float, var h: Float,
        var vx: Float = 0f, var vy: Float = 0f,
        var vw: Float = 0f, var vh: Float = 0f
    ) {
        fun predict(): KalmanState {
            return KalmanState(
                x = x + vx, y = y + vy,
                w = w + vw, h = h + vh,
                vx = vx, vy = vy, vw = vw, vh = vh
            )
        }

        fun update(measX: Float, measY: Float, measW: Float, measH: Float) {
            val gain = 0.5f
            vx = (measX - x) * gain
            vy = (measY - y) * gain
            vw = (measW - w) * gain
            vh = (measH - h) * gain
            x = x + (measX - x) * gain
            y = y + (measY - y) * gain
            w = w + (measW - w) * gain
            h = h + (measH - h) * gain
        }

        fun toBBox(): BBox = BBox(
            x = x.coerceIn(0f, 1f),
            y = y.coerceIn(0f, 1f),
            width = w.coerceIn(0f, 1f),
            height = h.coerceIn(0f, 1f)
        )
    }

    data class TrackInfo(
        val id: Int,
        val label: String,
        var bbox: BBox,
        var originalBbox: BBox,
        var kalman: KalmanState,
        var predictedBbox: BBox,
        var frameCount: Int = 1,
        var missed: Int = 0
    )

    fun update(detections: List<Detection>): List<Detection> {
        for (track in tracks.values) {
            val predicted = track.kalman.predict()
            track.predictedBbox = predicted.toBBox()
        }

        if (detections.isEmpty()) {
            tracks.values.forEach { it.missed++ }
            val toRemove = tracks.keys.filter { tracks[it]!!.missed > AppConfig.TRACKER_MAX_MISSED }
            toRemove.forEach { tracks.remove(it) }
            return detections
        }

        val matched = mutableSetOf<Int>()
        val result = mutableListOf<Detection>()

        for (det in detections) {
            var bestIou = AppConfig.IOT_THRESHOLD
            var bestId = -1

            for ((id, track) in tracks) {
                if (track.label != det.label) continue
                val iou = computeIou(det.originalBbox, track.predictedBbox)
                if (iou > bestIou) {
                    bestIou = iou
                    bestId = id
                }
            }

            if (bestId != -1 && bestId !in matched) {
                val track = tracks[bestId]!!
                track.kalman.update(
                    det.originalBbox.x,
                    det.originalBbox.y,
                    det.originalBbox.width,
                    det.originalBbox.height
                )
                track.bbox = det.bbox
                track.originalBbox = det.originalBbox
                track.frameCount++
                track.missed = 0
                matched.add(bestId)
                result.add(det.copy(trackId = bestId))
            } else {
                val newId = nextId++
                val kalman = KalmanState(
                    x = det.originalBbox.x,
                    y = det.originalBbox.y,
                    w = det.originalBbox.width,
                    h = det.originalBbox.height
                )
                tracks[newId] = TrackInfo(
                    id = newId,
                    label = det.label,
                    bbox = det.bbox,
                    originalBbox = det.originalBbox,
                    kalman = kalman,
                    predictedBbox = det.originalBbox
                )
                Log.d("TRACKER", "새 track 생성: id=$newId label=${det.label}")
                result.add(det.copy(trackId = newId))
            }
        }

        for ((id, track) in tracks) {
            if (id !in matched) track.missed++
        }
        val toRemove = tracks.keys.filter { tracks[it]!!.missed > AppConfig.TRACKER_MAX_MISSED }
        toRemove.forEach { tracks.remove(it) }

        return result
    }

    private fun computeIou(a: BBox, b: BBox): Float {
        val ax1 = a.x; val ay1 = a.y
        val ax2 = a.x + a.width; val ay2 = a.y + a.height
        val bx1 = b.x; val by1 = b.y
        val bx2 = b.x + b.width; val by2 = b.y + b.height

        val interX1 = maxOf(ax1, bx1); val interY1 = maxOf(ay1, by1)
        val interX2 = minOf(ax2, bx2); val interY2 = minOf(ay2, by2)

        val interArea = maxOf(0f, interX2 - interX1) * maxOf(0f, interY2 - interY1)
        val aArea = a.width * a.height
        val bArea = b.width * b.height
        val unionArea = aArea + bArea - interArea

        return if (unionArea <= 0f) 0f else interArea / unionArea
    }
}