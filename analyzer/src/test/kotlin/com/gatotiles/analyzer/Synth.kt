package com.gatotiles.analyzer

import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Tiny synthesizer for test signals with exactly known event times. */
class Synth(val sampleRate: Int, durationSec: Double, seed: Long = 1) {
    val buf = FloatArray((durationSec * sampleRate).toInt())
    private val rnd = Random(seed)

    private fun idx(t: Double) = (t * sampleRate).toInt()

    fun kick(t: Double, amp: Float = 0.9f) {
        val start = idx(t)
        val len = (0.35 * sampleRate).toInt()
        var phase = 0.0
        for (i in 0 until len) {
            val k = start + i
            if (k !in buf.indices) break
            val tt = i.toDouble() / sampleRate
            val f = 50 + 70 * exp(-tt / 0.03)
            phase += 2 * PI * f / sampleRate
            buf[k] += (amp * sin(phase) * exp(-tt / 0.12)).toFloat()
        }
    }

    fun snare(t: Double, amp: Float = 0.5f) {
        val start = idx(t)
        val len = (0.25 * sampleRate).toInt()
        for (i in 0 until len) {
            val k = start + i
            if (k !in buf.indices) break
            val tt = i.toDouble() / sampleRate
            val noise = rnd.nextGaussian() * 0.5 * exp(-tt / 0.07)
            val tone = sin(2 * PI * 190 * tt) * 0.6 * exp(-tt / 0.05)
            buf[k] += (amp * (noise + tone)).toFloat()
        }
    }

    fun hat(t: Double, amp: Float = 0.18f) {
        val start = idx(t)
        val len = (0.08 * sampleRate).toInt()
        var prev = 0.0
        for (i in 0 until len) {
            val k = start + i
            if (k !in buf.indices) break
            val tt = i.toDouble() / sampleRate
            val n = rnd.nextGaussian()
            val hp = n - prev
            prev = n
            buf[k] += (amp * hp * 0.5 * exp(-tt / 0.025)).toFloat()
        }
    }

    /** Harmonic tone (saw-like) with a short attack. */
    fun tone(t: Double, dur: Double, midi: Int, amp: Float = 0.25f) {
        val f0 = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val start = idx(t)
        val len = (dur * sampleRate).toInt()
        for (i in 0 until len) {
            val k = start + i
            if (k !in buf.indices) break
            val tt = i.toDouble() / sampleRate
            val env = minOf(1.0, tt / 0.008) * minOf(1.0, (dur - tt) / 0.03) * (0.7 + 0.3 * exp(-tt / 0.2))
            var s = 0.0
            for (h in 1..6) {
                val fh = f0 * h
                if (fh > sampleRate / 2.2) break
                s += sin(2 * PI * fh * tt) / h
            }
            buf[k] += (amp * env * s).toFloat()
        }
    }

    fun click(t: Double, amp: Float = 0.8f) {
        val start = idx(t)
        val len = (0.03 * sampleRate).toInt()
        for (i in 0 until len) {
            val k = start + i
            if (k !in buf.indices) break
            val tt = i.toDouble() / sampleRate
            buf[k] += (amp * sin(2 * PI * 2000 * tt) * exp(-tt / 0.006)).toFloat()
        }
    }

    /** Runs the whole analyzer, feeding stereo in chunks like a decoder would. */
    fun analyze(): AnalysisResult {
        val a = SongAnalyzer(sampleRate, 2)
        val chunk = 4096
        val inter = FloatArray(chunk * 2)
        var pos = 0
        while (pos < buf.size) {
            val n = minOf(chunk, buf.size - pos)
            for (i in 0 until n) {
                inter[2 * i] = buf[pos + i]
                inter[2 * i + 1] = buf[pos + i]
            }
            a.pushInterleaved(inter, n)
            pos += n
        }
        return a.finish()
    }
}
