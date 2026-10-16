package com.gatotiles.analyzer.chart

import com.gatotiles.analyzer.rhythm.Onset
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Local rhythmic grid estimated from where the attacks actually fall, beat by beat:
 * straight sixteenths (4 positions per beat) or a triplet-based grid (6 per beat: triplets,
 * swing, shuffle, 12/8), the metrical level of every position, and whether the attacks follow
 * the beat grid at all (they do not in rubato passages or where the tracker lost the tempo).
 */
internal class LocalMeter private constructor(
    /** Positions per beat (4 or 6) for the beat interval starting at beat i. */
    val sub: IntArray,
    /** levels[i][s] = metrical level of position s (1 until sub[i]) of beat i; index 0 unused. */
    val levels: Array<IntArray>,
    /** False where the attacks do not line up with the beat grid. */
    val regular: BooleanArray,
) {
    fun subAt(beat: Int): Int = if (beat in sub.indices) sub[beat] else 4

    fun levelAt(beat: Int, s: Int): Int = if (beat in levels.indices && s in 1 until levels[beat].size) levels[beat][s] else 3

    fun regularAt(beat: Int): Boolean = if (beat in regular.indices) regular[beat] else true

    val tripletShare: Double get() = if (sub.isEmpty()) 0.0 else sub.count { it == 6 }.toDouble() / sub.size

    val irregularShare: Double get() = if (regular.isEmpty()) 0.0 else regular.count { !it }.toDouble() / regular.size

    companion object {
        private val FRACS = doubleArrayOf(1.0 / 6, 0.25, 1.0 / 3, 0.5, 2.0 / 3, 0.75, 5.0 / 6)
        private const val F16 = 0
        private const val F14 = 1
        private const val F13 = 2
        private const val F12 = 3
        private const val F23 = 4
        private const val F34 = 5
        private const val F56 = 6
        private const val HALF_WINDOW = 4

        fun analyze(beats: DoubleArray, onsets: List<Onset>): LocalMeter {
            val nb = max(0, beats.size - 1)
            val ev = Array(nb) { DoubleArray(FRACS.size) }
            // Onsets (phase, weight) per beat, for the regularity check.
            val phases = Array(nb) { ArrayList<Double>() }
            val weights = Array(nb) { ArrayList<Double>() }
            var k = 0
            for (o in onsets) {
                if (o.strength < 0.1f || nb == 0) continue
                if (o.time < beats[0] || o.time >= beats[nb]) continue
                while (k < nb - 1 && beats[k + 1] <= o.time) k++
                while (k > 0 && beats[k] > o.time) k--
                val len = beats[k + 1] - beats[k]
                if (len <= 0.05) continue
                val ph = (o.time - beats[k]) / len
                val sigma = max(0.022 / len, 0.025)
                for (j in FRACS.indices) {
                    val d = (ph - FRACS[j]) / sigma
                    ev[k][j] += o.strength * exp(-0.5 * d * d)
                }
                if (o.strength >= 0.15f) {
                    phases[k].add(ph)
                    weights[k].add(o.strength.toDouble())
                }
            }

            val rawSub = IntArray(nb)
            val m = DoubleArray(FRACS.size)
            val windowEv = Array(nb) { DoubleArray(FRACS.size) }
            for (i in 0 until nb) {
                m.fill(0.0)
                for (j in max(0, i - HALF_WINDOW) until min(nb, i + HALF_WINDOW)) {
                    for (f in FRACS.indices) m[f] += ev[j][f]
                }
                m.copyInto(windowEv[i])
                val s4 = (m[F14] + m[F34]) / 2
                val s6 = (m[F16] + m[F13] + m[F23] + m[F56]) / 4
                val tripletEvidence = max(m[F13] + m[F56], m[F13] + m[F23])
                rawSub[i] = if (s6 > 1.3 * s4 && tripletEvidence > 0.3) 6 else 4
            }
            // Majority over neighbouring beats so the grid does not flicker.
            val sub = IntArray(nb) { i ->
                var six = 0
                var all = 0
                for (j in max(0, i - 3)..min(nb - 1, i + 3)) {
                    all++
                    if (rawSub[j] == 6) six++
                }
                if (2 * six > all) 6 else 4
            }
            val levels = Array(nb) { i -> levelsFor(sub[i], windowEv[i]) }

            val regular = BooleanArray(nb) { i ->
                var hit = 0.0
                var tot = 0.0
                var chance = 0.0
                for (j in max(0, i - HALF_WINDOW) until min(nb, i + HALF_WINDOW)) {
                    val len = beats[j + 1] - beats[j]
                    val s = sub[j]
                    val tolPh = min(0.03, 0.3 * len / s) / len
                    val c = min(1.0, s * 2 * tolPh)
                    for (q in phases[j].indices) {
                        val ph = phases[j][q]
                        val w = weights[j][q]
                        val pos = ph * s
                        val d = abs(pos - Math.round(pos)) / s
                        if (d <= tolPh) hit += w
                        tot += w
                        chance += w * c
                    }
                }
                if (tot < 1.5) {
                    true
                } else {
                    val q = hit / tot
                    val c = chance / tot
                    c >= 0.999 || (q - c) / (1 - c) >= 0.25
                }
            }
            return LocalMeter(sub, levels, regular)
        }

        private fun levelsFor(sub: Int, m: DoubleArray): IntArray {
            if (sub == 4) return intArrayOf(0, 3, 2, 3)
            val mx = max(1e-6, maxOf(m[F16], m[F13], m[F12], m[F23], m[F56]))
            fun r(f: Int) = m[f] / mx
            val half = r(F12) >= 0.4
            fun triplet(f: Int) = when {
                !half && r(f) >= 0.5 -> 2
                r(f) >= 0.15 -> 3
                else -> 4
            }
            fun sixth(f: Int) = if (r(f) >= 0.2) 3 else 4
            return intArrayOf(0, sixth(F16), triplet(F13), if (half) 2 else 3, triplet(F23), sixth(F56))
        }
    }
}
