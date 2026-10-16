package com.gatotiles.analyzer.features

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Pitch salience by harmonic summation over spectral peaks (a light version of the
 * Melodia salience function). Output bins cover MIDI [MIN_MIDI, MAX_MIDI] at
 * BINS_PER_SEMITONE resolution.
 */
class SalienceCalculator(sampleRate: Int, private val nFft: Int) {
    companion object {
        const val MIN_MIDI = 40f // E2, 82 Hz
        const val MAX_MIDI = 88f // E6, 1319 Hz
        const val BINS_PER_SEMITONE = 3
        val N_BINS = ((MAX_MIDI - MIN_MIDI) * BINS_PER_SEMITONE).toInt() + 1
        private const val MAX_PEAKS = 32
        private const val HARMONICS = 8
        private const val HARMONIC_DECAY = 0.8f
        private const val COS_TABLE = 64

        fun midiToHz(m: Float): Float = (440.0 * Math.pow(2.0, (m - 69.0) / 12.0)).toFloat()
        fun binToMidi(b: Float): Float = MIN_MIDI + b / BINS_PER_SEMITONE
    }

    private val binHz = sampleRate.toFloat() / nFft
    private val kMin = max(2, (60f / binHz).toInt())
    private val kMax = min(nFft / 2 - 2, (5000f / binHz).toInt())
    private val fLow = midiToHz(MIN_MIDI) * 0.97f
    private val fHigh = midiToHz(MAX_MIDI) * 1.03f
    private val peakK = IntArray(kMax + 2)
    private val peakM = FloatArray(kMax + 2)
    private val harmonicWeight = FloatArray(HARMONICS) { Math.pow(HARMONIC_DECAY.toDouble(), it.toDouble()).toFloat() }
    private val cos2 = FloatArray(COS_TABLE + 1) {
        val d = it.toDouble() / COS_TABLE
        val c = cos(PI * d / 2)
        (c * c).toFloat()
    }
    // Melody-range prior: favour 165..784 Hz, damp bass and very high registers.
    private val binWeight = FloatArray(N_BINS) { b ->
        val m = binToMidi(b.toFloat())
        when {
            m < 52f -> 0.5f + 0.5f * (m - MIN_MIDI) / (52f - MIN_MIDI)
            m <= 79f -> 1f
            else -> 1f - 0.3f * (m - 79f) / (MAX_MIDI - 79f)
        }
    }
    private val log2Inv = (1.0 / ln(2.0)).toFloat()

    fun compute(mag: FloatArray): FloatArray {
        val out = FloatArray(N_BINS)
        var mx = 0f
        for (k in kMin..kMax) if (mag[k] > mx) mx = mag[k]
        if (mx < 1e-3f) return out
        val thr = mx * 0.01f
        var n = 0
        for (k in kMin..kMax) {
            val m = mag[k]
            if (m > thr && m > mag[k - 1] && m >= mag[k + 1]) {
                peakK[n] = k
                peakM[n] = m
                n++
            }
        }
        if (n > MAX_PEAKS) {
            // Partial selection sort for the strongest peaks.
            for (i in 0 until MAX_PEAKS) {
                var best = i
                for (j in i + 1 until n) if (peakM[j] > peakM[best]) best = j
                if (best != i) {
                    val tk = peakK[i]; peakK[i] = peakK[best]; peakK[best] = tk
                    val tm = peakM[i]; peakM[i] = peakM[best]; peakM[best] = tm
                }
            }
            n = MAX_PEAKS
        }
        val span = BINS_PER_SEMITONE.toFloat() // +-1 semitone
        for (p in 0 until n) {
            val k = peakK[p]
            val a = ln(max(mag[k - 1], 1e-9f))
            val b = ln(max(mag[k], 1e-9f))
            val c = ln(max(mag[k + 1], 1e-9f))
            val den = a - 2 * b + c
            val off = if (den != 0f) (0.5f * (a - c) / den).coerceIn(-0.5f, 0.5f) else 0f
            val freq = (k + off) * binHz
            var amp = Math.exp((b - 0.25f * (a - c) * off).toDouble()).toFloat()
            if (freq < 150f) amp *= 0.5f + 0.5f * ((freq - 60f) / 90f).coerceIn(0f, 1f)
            for (h in 1..HARMONICS) {
                val f0 = freq / h
                if (f0 < fLow) break
                if (f0 > fHigh) continue
                val midi = 69f + 12f * ln(f0 / 440f) * log2Inv
                val pos = (midi - MIN_MIDI) * BINS_PER_SEMITONE
                val lo = max(0, kotlin.math.ceil(pos - span).toInt())
                val hi = min(N_BINS - 1, kotlin.math.floor(pos + span).toInt())
                val w = amp * harmonicWeight[h - 1]
                for (bin in lo..hi) {
                    val d = kotlin.math.abs(bin - pos) / span
                    out[bin] += w * cos2[(d * COS_TABLE).toInt().coerceIn(0, COS_TABLE)]
                }
            }
        }
        for (bin in 0 until N_BINS) out[bin] *= binWeight[bin]
        return out
    }
}
