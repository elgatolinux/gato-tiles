package com.gatotiles.analyzer.dsp

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Triangular mel-spaced filter bank applied to a magnitude spectrum. */
class MelFilterBank(sampleRate: Int, nFft: Int, val nBands: Int, fMin: Float, fMax: Float) {
    val centerHz = FloatArray(nBands)
    private val startBin = IntArray(nBands)
    private val weights = arrayOfNulls<FloatArray>(nBands)

    init {
        val binHz = sampleRate.toFloat() / nFft
        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)
        val edges = FloatArray(nBands + 2) { melToHz(melMin + (melMax - melMin) * it / (nBands + 1)) }
        for (b in 0 until nBands) {
            val lo = edges[b]
            val c = edges[b + 1]
            val hi = edges[b + 2]
            centerHz[b] = c
            var k0 = ceil(lo / binHz).toInt()
            var k1 = floor(hi / binHz).toInt()
            k0 = max(1, k0)
            k1 = min(nFft / 2, k1)
            if (k1 < k0) {
                // Band narrower than one bin: use the nearest bin.
                val k = min(nFft / 2, max(1, Math.round(c / binHz)))
                k0 = k
                k1 = k
            }
            val w = FloatArray(k1 - k0 + 1)
            for (k in k0..k1) {
                val f = k * binHz
                val v = if (f <= c) (f - lo) / max(1e-6f, c - lo) else (hi - f) / max(1e-6f, hi - c)
                w[k - k0] = v.coerceIn(0f, 1f)
            }
            if (w.all { it <= 0f }) w.fill(1f)
            startBin[b] = k0
            weights[b] = w
        }
    }

    fun apply(mag: FloatArray, out: FloatArray) {
        for (b in 0 until nBands) {
            val w = weights[b]!!
            val k0 = startBin[b]
            var acc = 0f
            for (i in w.indices) acc += w[i] * mag[k0 + i]
            out[b] = acc
        }
    }

    companion object {
        fun hzToMel(f: Float): Float = (2595.0 * log10(1.0 + f / 700.0)).toFloat()
        fun melToHz(m: Float): Float = (700.0 * (10.0.pow(m / 2595.0) - 1.0)).toFloat()
    }
}
