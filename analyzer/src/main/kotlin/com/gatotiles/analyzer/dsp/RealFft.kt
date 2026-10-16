package com.gatotiles.analyzer.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * FFT for real input of size [n] (power of two). Internally runs a complex FFT of size n/2
 * over the even/odd samples and splits the result, which halves the work.
 */
class RealFft(val n: Int) {
    init {
        require(n >= 4 && (n and (n - 1)) == 0) { "n must be a power of two >= 4" }
    }

    private val half = n / 2
    private val bitRev = IntArray(half)
    private val twCos = FloatArray(half / 2)
    private val twSin = FloatArray(half / 2)
    private val postCos = FloatArray(half + 1)
    private val postSin = FloatArray(half + 1)
    private val zr = FloatArray(half)
    private val zi = FloatArray(half)
    private val re = FloatArray(half + 1)
    private val im = FloatArray(half + 1)

    init {
        val bits = Integer.numberOfTrailingZeros(half)
        for (i in 0 until half) bitRev[i] = Integer.reverse(i) ushr (32 - bits)
        for (i in 0 until half / 2) {
            val a = -2.0 * PI * i / half
            twCos[i] = cos(a).toFloat()
            twSin[i] = sin(a).toFloat()
        }
        for (k in 0..half) {
            val a = 2.0 * PI * k / n
            postCos[k] = cos(a).toFloat()
            postSin[k] = sin(a).toFloat()
        }
    }

    /** Spectrum bins 0..n/2 of [input] (length n) into [outRe]/[outIm] (length n/2+1). */
    fun forward(input: FloatArray, outRe: FloatArray, outIm: FloatArray) {
        for (k in 0 until half) {
            zr[k] = input[2 * k]
            zi[k] = input[2 * k + 1]
        }
        complexFft()
        for (k in 0..half) {
            val k1 = if (k == half) 0 else k
            val k2 = if (k == 0) 0 else half - k
            val ar = zr[k1]
            val ai = zi[k1]
            val br = zr[k2]
            val bi = zi[k2]
            val er = 0.5f * (ar + br)
            val ei = 0.5f * (ai - bi)
            val orr = 0.5f * (ai + bi)
            val oi = -0.5f * (ar - br)
            val c = postCos[k]
            val s = postSin[k]
            outRe[k] = er + c * orr + s * oi
            outIm[k] = ei + c * oi - s * orr
        }
    }

    /** Magnitude spectrum (length n/2+1) of [input]. */
    fun magnitude(input: FloatArray, outMag: FloatArray) {
        forward(input, re, im)
        for (k in 0..half) {
            val r = re[k]
            val i = im[k]
            outMag[k] = sqrt(r * r + i * i)
        }
    }

    private fun complexFft() {
        for (i in 0 until half) {
            val j = bitRev[i]
            if (j > i) {
                var t = zr[i]; zr[i] = zr[j]; zr[j] = t
                t = zi[i]; zi[i] = zi[j]; zi[j] = t
            }
        }
        var size = 2
        while (size <= half) {
            val halfSize = size / 2
            val step = half / size
            var start = 0
            while (start < half) {
                var k = 0
                for (j in start until start + halfSize) {
                    val wr = twCos[k]
                    val wi = twSin[k]
                    val l = j + halfSize
                    val tr = zr[l] * wr - zi[l] * wi
                    val ti = zr[l] * wi + zi[l] * wr
                    zr[l] = zr[j] - tr
                    zi[l] = zi[j] - ti
                    zr[j] += tr
                    zi[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
    }
}
