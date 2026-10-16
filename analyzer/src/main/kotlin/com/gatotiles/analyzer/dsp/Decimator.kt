package com.gatotiles.analyzer.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Streaming low-pass filter + integer decimation for a mono signal.
 * The FIR is zero-phase (centered), so output sample j lines up exactly with input sample j*factor.
 */
class Decimator(val factor: Int) {
    private val taps: Int = if (factor <= 1) 1 else 16 * factor + 1
    private val half = taps / 2
    private val h = FloatArray(taps)
    // Only non-zero taps are evaluated (a cutoff at fs/(2*factor) zeroes every factor-th tap,
    // half of them for the common 2x case).
    private lateinit var nzIndex: IntArray
    private lateinit var nzCoef: FloatArray
    private var buf = FloatArray(maxOf(8192, taps * 4))
    private var bufStart = -half.toLong()
    private var bufLen = half
    private var nextOut = 0L
    private var totalIn = 0L

    init {
        if (factor > 1) {
            val fc = 0.5 / factor
            var sum = 0.0
            for (i in 0 until taps) {
                val m = i - half
                val sinc = if (m == 0) 2 * fc else sin(2 * PI * fc * m) / (PI * m)
                val w = 0.42 - 0.5 * cos(2 * PI * i / (taps - 1)) + 0.08 * cos(4 * PI * i / (taps - 1))
                h[i] = (sinc * w).toFloat()
                sum += h[i]
            }
            for (i in 0 until taps) h[i] = (h[i] / sum).toFloat()
        } else {
            h[0] = 1f
        }
        val idx = (0 until taps).filter { kotlin.math.abs(h[it]) > 1e-7f }
        nzIndex = idx.toIntArray()
        nzCoef = FloatArray(idx.size) { h[idx[it]] }
    }

    fun push(input: FloatArray, off: Int, len: Int, out: FloatList) {
        if (factor <= 1) {
            for (i in off until off + len) out.add(input[i])
            totalIn += len
            return
        }
        ensureCapacity(bufLen + len)
        System.arraycopy(input, off, buf, bufLen, len)
        bufLen += len
        totalIn += len
        drain(out, flush = false)
    }

    fun flush(out: FloatList) {
        if (factor <= 1) return
        ensureCapacity(bufLen + half + 1)
        java.util.Arrays.fill(buf, bufLen, bufLen + half + 1, 0f)
        bufLen += half + 1
        drain(out, flush = true)
    }

    private fun drain(out: FloatList, flush: Boolean) {
        val availEnd = bufStart + bufLen
        while (true) {
            val center = nextOut * factor
            if (flush && center >= totalIn) break
            if (center + half >= availEnd) break
            val base = (center - half - bufStart).toInt()
            var acc = 0f
            for (k in nzIndex.indices) acc += nzCoef[k] * buf[base + nzIndex[k]]
            out.add(acc)
            nextOut++
        }
        val keepFrom = nextOut * factor - half
        val drop = min((keepFrom - bufStart).toInt(), bufLen)
        if (drop > 0) {
            System.arraycopy(buf, drop, buf, 0, bufLen - drop)
            bufLen -= drop
            bufStart += drop
        }
    }

    private fun ensureCapacity(n: Int) {
        if (n > buf.size) buf = buf.copyOf(maxOf(n, buf.size * 2))
    }
}
