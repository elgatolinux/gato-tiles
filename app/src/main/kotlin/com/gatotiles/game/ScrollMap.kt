package com.gatotiles.game

import com.gatotiles.analyzer.model.Section
import kotlin.math.max
import kotlin.math.min

/**
 * Where tiles sit on the track when their speed changes during the song. A tile is drawn
 * pos(t) - pos(now) away from the hit line, measured in ms at the base speed, where pos is
 * the integral of the speed over song time. So every tile on screen moves at the current
 * speed, the gaps between upcoming tiles already show the speed they will have, and each
 * tile still reaches the hit line exactly on its time.
 *
 * The speed is set by knots: constant before the first and after the last one, and between
 * two knots either constant (same speed) or a smoothstep ramp from one speed to the other.
 */
class ScrollMap private constructor(
    private val knotMs: DoubleArray,
    private val speed: DoubleArray,
    private val posAt: DoubleArray,
) {
    /** Distance of song time [t] along the track, in ms at the base speed (only differences matter). */
    fun pos(t: Double): Double {
        val last = knotMs.size - 1
        if (t <= knotMs[0]) return posAt[0] + speed[0] * (t - knotMs[0])
        if (t >= knotMs[last]) return posAt[last] + speed[last] * (t - knotMs[last])
        val j = knotBefore(t)
        val a = knotMs[j]
        val m0 = speed[j]
        val m1 = speed[j + 1]
        if (m0 == m1) return posAt[j] + m0 * (t - a)
        val len = knotMs[j + 1] - a
        val u = (t - a) / len
        // Integral of m0 + (m1 - m0) * (3u² - 2u³).
        return posAt[j] + len * (m0 * u + (m1 - m0) * (u * u * u - u * u * u * u / 2))
    }

    /** Speed multiplier at song time [t]. */
    fun speedAt(t: Double): Double {
        val last = knotMs.size - 1
        if (t <= knotMs[0]) return speed[0]
        if (t >= knotMs[last]) return speed[last]
        val j = knotBefore(t)
        val u = (t - knotMs[j]) / (knotMs[j + 1] - knotMs[j])
        return speed[j] + (speed[j + 1] - speed[j]) * u * u * (3 - 2 * u)
    }

    /** Song time whose track distance is [p] (inverse of [pos]). */
    fun timeAt(p: Double): Double {
        val last = knotMs.size - 1
        if (p <= posAt[0]) return knotMs[0] + (p - posAt[0]) / speed[0]
        if (p >= posAt[last]) return knotMs[last] + (p - posAt[last]) / speed[last]
        var j = 0
        var hi = last
        while (j < hi - 1) {
            val mid = (j + hi) ushr 1
            if (posAt[mid] <= p) j = mid else hi = mid
        }
        if (speed[j] == speed[j + 1]) return knotMs[j] + (p - posAt[j]) / speed[j]
        var lo = knotMs[j]
        var up = knotMs[j + 1]
        repeat(40) {
            val mid = (lo + up) / 2
            if (pos(mid) < p) lo = mid else up = mid
        }
        return (lo + up) / 2
    }

    /** Index of the last knot at or before [t] (t strictly inside the knots). */
    private fun knotBefore(t: Double): Int {
        var lo = 0
        var hi = knotMs.size - 1
        while (lo < hi - 1) {
            val mid = (lo + hi) ushr 1
            if (knotMs[mid] <= t) lo = mid else hi = mid
        }
        return lo
    }

    companion object {
        /** A speed change starts this long before the new part and ends this long after. */
        const val RAMP_BEFORE_MS = 1200.0
        const val RAMP_AFTER_MS = 300.0
        /** Tiles never take less than this to cross the track, however fast the part. */
        const val MIN_APPROACH_MS = 450.0

        /** The same speed all song long: pos(t) = t. */
        val CONSTANT = ScrollMap(doubleArrayOf(0.0), doubleArrayOf(1.0), doubleArrayOf(0.0))

        /**
         * Speed of each part of the song: 1 in quiet, sparse parts, up to 1 + [boost] in the
         * loud parts with more tiles than usual for this chart (choruses, climaxes).
         */
        fun sectionSpeeds(sections: List<Section>, noteTimes: IntArray, durationMs: Int, boost: Double): DoubleArray {
            val n = sections.size
            if (n < 2 || boost <= 0.0 || noteTimes.size < 2) return DoubleArray(n) { 1.0 }
            val mean = noteTimes.size / max(1.0, (noteTimes.last() - noteTimes.first()) / 1000.0)
            return DoubleArray(n) { i ->
                val t0 = sections[i].t
                val t1 = if (i + 1 < n) sections[i + 1].t else max(durationMs, noteTimes.last() + 1)
                val rate = countIn(noteTimes, t0, t1) / max(1.0, (t1 - t0) / 1000.0)
                val loud = ((sections[i].energy / 100.0 - 0.25) / 0.5).coerceIn(0.0, 1.0)
                val busy = ((rate / mean - 0.7) / 0.6).coerceIn(0.0, 1.0)
                1.0 + boost * (0.5 * loud + 0.5 * busy)
            }
        }

        /** Speeds per part (see [sectionSpeeds]), capped so tiles stay readable at [approachMs]. */
        fun forSong(sections: List<Section>, noteTimes: IntArray, durationMs: Int, boost: Double, approachMs: Int): ScrollMap {
            val cap = max(1.0, approachMs / MIN_APPROACH_MS)
            val speeds = sectionSpeeds(sections, noteTimes, durationMs, boost)
            return fromParts(sections.map { it.t.toDouble() }, DoubleArray(speeds.size) { min(speeds[it], cap) })
        }

        /** Constant [speeds] per part starting at [startsMs], with a ramp around every change. */
        fun fromParts(startsMs: List<Double>, speeds: DoubleArray): ScrollMap {
            if (speeds.isEmpty()) return CONSTANT
            val t = ArrayList<Double>()
            val m = ArrayList<Double>()
            for (i in 1 until speeds.size) {
                if (speeds[i] == speeds[i - 1]) continue
                val b = startsMs[i]
                val next = if (i + 1 < startsMs.size) startsMs[i + 1] else Double.MAX_VALUE
                val prev = t.lastOrNull() ?: Double.NEGATIVE_INFINITY
                val from = max(b - RAMP_BEFORE_MS, prev)
                val to = max(from + 1.0, min(b + RAMP_AFTER_MS, next - RAMP_BEFORE_MS))
                // When the previous ramp ends right here, its last knot already has this speed.
                if (from > prev) {
                    t.add(from)
                    m.add(speeds[i - 1])
                }
                t.add(to)
                m.add(speeds[i])
            }
            if (t.isEmpty()) {
                t.add(0.0)
                m.add(speeds[0])
            }
            val knots = t.toDoubleArray()
            val sp = m.toDoubleArray()
            val pos = DoubleArray(knots.size)
            pos[0] = sp[0] * knots[0]
            // Both a constant stretch and a smoothstep ramp average the two end speeds.
            for (j in 1 until knots.size) pos[j] = pos[j - 1] + (knots[j] - knots[j - 1]) * (sp[j - 1] + sp[j]) / 2
            return ScrollMap(knots, sp, pos)
        }

        private fun countIn(sorted: IntArray, from: Int, to: Int): Int = lowerBound(sorted, to) - lowerBound(sorted, from)

        private fun lowerBound(a: IntArray, v: Int): Int {
            var lo = 0
            var hi = a.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (a[mid] < v) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }
}
