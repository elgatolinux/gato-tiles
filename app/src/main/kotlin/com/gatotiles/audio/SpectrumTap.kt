package com.gatotiles.audio

import com.gatotiles.analyzer.dsp.RealFft
import com.gatotiles.analyzer.dsp.Windows
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Keeps the most recently decoded audio (mono) indexed by output frame, so the renderer
 * can compute the spectrum of exactly what is being heard right now.
 */
class SpectrumTap(capacityPow2: Int = 1 shl 16) {
    private val ring = FloatArray(capacityPow2)
    private val mask = capacityPow2 - 1
    private var writeFrame = 0L
    private val lock = Any()

    private val n = 1024
    private val fft = RealFft(n)
    private val window = Windows.hann(n)
    private val frame = FloatArray(n)
    private val mag = FloatArray(n / 2 + 1)
    private var sampleRate = 44100
    private var bandEdges = IntArray(0)

    fun reset(sampleRate: Int, bands: Int) {
        synchronized(lock) {
            writeFrame = 0
            ring.fill(0f)
        }
        this.sampleRate = sampleRate
        val fMin = 40.0
        val fMax = min(16000.0, sampleRate / 2.0 - 100)
        val binHz = sampleRate.toDouble() / n
        bandEdges = IntArray(bands + 1) { i ->
            val f = fMin * (fMax / fMin).pow(i.toDouble() / bands)
            max(1, (f / binHz).toInt())
        }
        for (i in 1..bands) if (bandEdges[i] <= bandEdges[i - 1]) bandEdges[i] = bandEdges[i - 1] + 1
    }

    /** Called by the decoder thread with mono samples starting at output frame [startFrame]. */
    fun push(mono: FloatArray, count: Int, startFrame: Long) {
        synchronized(lock) {
            for (i in 0 until count) ring[((startFrame + i) and mask.toLong()).toInt()] = mono[i]
            writeFrame = startFrame + count
        }
    }

    /**
     * Fills [out] (one value per band, ~0..1) with the spectrum centered on output frame
     * [centerFrame]. Returns false when that audio is not available.
     */
    fun bands(centerFrame: Long, out: FloatArray): Boolean {
        val start = centerFrame - n / 2
        synchronized(lock) {
            if (start < 0 || start + n > writeFrame || writeFrame - start > ring.size) {
                out.fill(0f)
                return false
            }
            for (i in 0 until n) frame[i] = ring[((start + i) and mask.toLong()).toInt()] * window[i]
        }
        fft.magnitude(frame, mag)
        val bands = min(out.size, bandEdges.size - 1)
        for (b in 0 until bands) {
            var s = 0f
            val k0 = bandEdges[b]
            val k1 = min(mag.size - 1, bandEdges[b + 1])
            for (k in k0 until max(k0 + 1, k1)) s = max(s, mag[k])
            // ~ -50..0 dB mapped to 0..1, with a tilt so the treble is not always flat.
            val db = 20f * log10(max(s, 1e-6f) / (n / 4f))
            val tilt = 3f * ln((b + 1f)) / ln(2f) * 0.5f
            out[b] = ((db + tilt + 50f) / 50f).coerceIn(0f, 1f)
        }
        return true
    }
}
