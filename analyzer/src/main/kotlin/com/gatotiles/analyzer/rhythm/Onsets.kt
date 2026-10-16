package com.gatotiles.analyzer.rhythm

import com.gatotiles.analyzer.dsp.parabolicOffset
import com.gatotiles.analyzer.dsp.percentile
import com.gatotiles.analyzer.dsp.toDb
import com.gatotiles.analyzer.features.Features
import kotlin.math.max
import kotlin.math.min

/**
 * A detected note/drum attack. Strengths are normalized (~0..1.5); [strength] is already
 * weighted by [persistence] (how much the energy stays up after the attack), which keeps
 * clicks, vinyl crackle and other transients without musical content weak.
 */
class Onset(
    val time: Double,
    val strength: Float,
    val low: Float,
    val mid: Float,
    val high: Float,
    val persistence: Float,
)

/** Onset envelopes normalized so that loud songs and quiet songs behave the same. */
class OnsetEnvelopes(
    val total: FloatArray,
    val low: FloatArray,
    val mid: FloatArray,
    val high: FloatArray,
    /** True for frames that are not (near) digital silence. */
    val active: BooleanArray,
)

object OnsetDetector {
    /**
     * Systematic delay of the flux peak relative to the true attack, in seconds
     * (negative = the envelope peaks early). Calibrated with synthetic clicks in tests.
     */
    var timeCorrection = 0.0

    fun envelopes(f: Features): OnsetEnvelopes {
        val n = f.nFrames
        val loud = BooleanArray(n)
        for (t in 0 until n) loud[t] = toDb(f.rms[t]) > SILENCE_DB
        // Dilate by ~50 ms: the flux peak of an attack after silence can land on a frame
        // whose own hop is still silent.
        val active = BooleanArray(n)
        for (t in 0 until n) {
            if (!loud[t]) continue
            for (k in max(0, t - 4)..min(n - 1, t + 4)) active[k] = true
        }
        return OnsetEnvelopes(
            total = normalize(f.fluxTotal, active),
            low = normalize(f.fluxLow, active),
            mid = normalize(f.fluxMid, active),
            high = normalize(f.fluxHigh, active),
            active = active,
        )
    }

    private fun normalize(x: FloatArray, active: BooleanArray): FloatArray {
        val vals = FloatArray(x.size)
        var c = 0
        for (i in x.indices) if (active[i]) vals[c++] = x[i]
        val ref = max(percentile(vals, 0.99, c), 1e-6f)
        return FloatArray(x.size) { min(x[it] / ref, 1.5f) }
    }

    fun detect(f: Features, env: OnsetEnvelopes): List<Onset> {
        val e = env.total
        val n = e.size
        val preMax = 3
        val postMax = 3
        val preAvg = 9
        val postAvg = 6
        val delta = 0.06f
        val wait = 4
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + e[i]
        val out = ArrayList<Onset>()
        var last = -wait - 1
        for (t in 0 until n) {
            val v = e[t]
            if (v < 0.04f || !env.active[t]) continue
            var isMax = true
            for (k in max(0, t - preMax)..min(n - 1, t + postMax)) {
                if (e[k] > v || (e[k] == v && k < t)) {
                    isMax = false
                    break
                }
            }
            if (!isMax) continue
            val a = max(0, t - preAvg)
            val b = min(n, t + postAvg + 1)
            val avg = (prefix[b] - prefix[a]) / (b - a)
            if (v < avg + delta) continue
            if (t - last <= wait) continue
            last = t
            val off = if (t in 1 until n - 1) parabolicOffset(e[t - 1], v, e[t + 1]) else 0f
            val time = f.frameTime(t + off.toDouble()) + timeCorrection
            val pers = persistence(f, t)
            out.add(
                Onset(
                    time = max(0.0, time),
                    strength = v * (0.4f + 0.6f * pers),
                    low = localMax(env.low, t),
                    mid = localMax(env.mid, t),
                    high = localMax(env.high, t),
                    persistence = pers,
                ),
            )
        }
        return out
    }

    /**
     * 0..1: how much of the band energy rise at frame [t] is still there 45-95 ms later,
     * weighted by each band group's share of the flux.
     */
    fun persistence(f: Features, t: Int): Float {
        var num = 0f
        var den = 0f
        for (g in 0..2) {
            val e: FloatArray
            val fl: Float
            when (g) {
                0 -> {
                    e = f.energyLow
                    fl = f.fluxLow[t]
                }
                1 -> {
                    e = f.energyMid
                    fl = f.fluxMid[t]
                }
                else -> {
                    e = f.energyHigh
                    fl = f.fluxHigh[t]
                }
            }
            if (fl <= 1e-6f) continue
            val rise = avg(e, t + 4, t + 8) - avg(e, t - 6, t - 2)
            num += fl * (rise / fl).coerceIn(0f, 1f)
            den += fl
        }
        return if (den > 0f) num / den else 0f
    }

    private fun avg(x: FloatArray, a: Int, b: Int): Float {
        val lo = a.coerceIn(0, x.size - 1)
        val hi = b.coerceIn(0, x.size - 1)
        var s = 0f
        for (i in lo..hi) s += x[i]
        return s / (hi - lo + 1)
    }

    private fun localMax(x: FloatArray, t: Int): Float {
        var m = 0f
        for (k in max(0, t - 1)..min(x.size - 1, t + 1)) m = max(m, x[k])
        return m
    }

    const val SILENCE_DB = -55f
}
