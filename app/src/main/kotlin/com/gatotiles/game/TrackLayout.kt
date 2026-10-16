package com.gatotiles.game

import android.graphics.Matrix
import com.gatotiles.data.TrackStyle

/**
 * Geometry of the track. Everything on the track is drawn in "plane" coordinates (x across
 * the lanes, y = screen-like distance where the hit line is at [hitY] and a tile [v] ms in
 * the future sits at hitY - v * pxPerMs). [matrix] maps the plane to the screen: a plain
 * translation for the flat style, a homography (true perspective) for the 3D style.
 */
class TrackLayout(
    val w: Int,
    val h: Int,
    style: TrackStyle,
    val approachMs: Int,
    val lanes: Int,
) {
    val flat = style == TrackStyle.FLAT
    val trackW: Float = w * (if (flat) 0.94f else 1f)
    val laneW: Float = trackW / lanes
    val hitY: Float = h * (if (flat) 0.80f else 0.82f)
    /** Screen y of the far end of the track. */
    val farY: Float = h * (if (flat) 0.235f else 0.25f)
    val pxPerMs: Float
    val tileLenMs: Float = (approachMs * 0.12f).coerceIn(95f, 175f)
    val tileH: Float get() = tileLenMs * pxPerMs
    val matrix = Matrix()
    val inverse = Matrix()
    val planeFar: Float
    val planeBottom: Float
    private val tmp = FloatArray(2)

    init {
        if (flat) {
            pxPerMs = (hitY - farY) / approachMs
            matrix.setTranslate((w - trackW) / 2f, 0f)
        } else {
            // Width ratio far/near; the plane is stretched so that scale ~1 at the hit line.
            val ratio = 0.40f
            val bottomW = w * 0.98f
            val topW = bottomW * ratio
            val screenLen = hitY - farY
            val planeLen = screenLen / ratio
            pxPerMs = planeLen / approachMs
            val cx = w / 2f
            val src = floatArrayOf(0f, hitY - planeLen, trackW, hitY - planeLen, trackW, hitY, 0f, hitY)
            val dst = floatArrayOf(
                cx - topW / 2, farY, cx + topW / 2, farY,
                cx + bottomW / 2, hitY, cx - bottomW / 2, hitY,
            )
            matrix.setPolyToPoly(src, 0, dst, 0, 4)
        }
        matrix.invert(inverse)
        planeFar = hitY - approachMs * pxPerMs
        tmp[0] = w / 2f
        tmp[1] = h.toFloat()
        inverse.mapPoints(tmp)
        planeBottom = tmp[1]
    }

    fun planeY(vMs: Double): Float = hitY - (vMs * pxPerMs).toFloat()

    fun vAt(planeY: Float): Double = ((hitY - planeY) / pxPerMs).toDouble()

    fun toScreen(x: Float, y: Float, out: FloatArray) {
        out[0] = x
        out[1] = y
        matrix.mapPoints(out)
    }

    fun toPlane(x: Float, y: Float, out: FloatArray) {
        out[0] = x
        out[1] = y
        inverse.mapPoints(out)
    }

    fun laneCenter(lane: Int): Float = (lane + 0.5f) * laneW

    /** How much one plane unit is scaled on screen at plane y (1 at the hit line). */
    fun scaleAt(y: Float): Float {
        tmp[0] = 0f
        tmp[1] = y
        matrix.mapPoints(tmp)
        val x0 = tmp[0]
        tmp[0] = trackW
        tmp[1] = y
        matrix.mapPoints(tmp)
        return (tmp[0] - x0) / trackW
    }
}
