package com.gatotiles.analyzer.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Growable float array without boxing. */
class FloatList(initialCapacity: Int = 1024) {
    private var data = FloatArray(maxOf(4, initialCapacity))
    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    operator fun get(i: Int): Float = data[i]

    fun clear() {
        size = 0
    }

    fun toArray(): FloatArray = data.copyOf(size)

    /** Direct access to the backing array; only indices < size are valid. */
    fun raw(): FloatArray = data
}

object Windows {
    /** Periodic Hann window. */
    fun hann(n: Int): FloatArray = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / n)).toFloat() }
}

fun nextPow2(x: Double): Int {
    var p = 1
    while (p < x) p = p shl 1
    return p
}

fun nearestPow2(x: Double): Int {
    val up = nextPow2(x)
    val down = up shr 1
    return if (down > 0 && x / down < up / x) down else up
}

/** Linear-interpolated percentile (q in 0..1) of the values; returns 0 for empty input. */
fun percentile(values: FloatArray, q: Double, count: Int = values.size): Float {
    if (count <= 0) return 0f
    val sorted = values.copyOf(count)
    sorted.sort()
    val pos = q.coerceIn(0.0, 1.0) * (count - 1)
    val i = floor(pos).toInt()
    val j = min(i + 1, count - 1)
    val f = (pos - i).toFloat()
    return sorted[i] * (1 - f) + sorted[j] * f
}

fun median(values: DoubleArray): Double {
    if (values.isEmpty()) return 0.0
    val s = values.sortedArray()
    val m = s.size / 2
    return if (s.size % 2 == 1) s[m] else 0.5 * (s[m - 1] + s[m])
}

/** Centered moving average with a window of [w] samples (clamped at the edges). */
fun movingAverage(x: FloatArray, w: Int): FloatArray {
    val n = x.size
    if (n == 0) return x
    val half = max(0, w / 2)
    val prefix = DoubleArray(n + 1)
    for (i in 0 until n) prefix[i + 1] = prefix[i] + x[i]
    return FloatArray(n) { i ->
        val a = max(0, i - half)
        val b = min(n, i + half + 1)
        ((prefix[b] - prefix[a]) / (b - a)).toFloat()
    }
}

/** Linear interpolation of x at fractional index [pos] (clamped). */
fun interp(x: FloatArray, pos: Double): Float {
    if (x.isEmpty()) return 0f
    if (pos <= 0) return x[0]
    val last = x.size - 1
    if (pos >= last) return x[last]
    val i = pos.toInt()
    val f = (pos - i).toFloat()
    return x[i] * (1 - f) + x[i + 1] * f
}

fun mean(x: FloatArray): Double {
    if (x.isEmpty()) return 0.0
    var s = 0.0
    for (v in x) s += v
    return s / x.size
}

fun stddev(x: FloatArray): Double {
    if (x.isEmpty()) return 0.0
    val m = mean(x)
    var s = 0.0
    for (v in x) s += (v - m) * (v - m)
    return sqrt(s / x.size)
}

fun toDb(x: Float): Float = (20.0 * ln(max(x, 1e-9f).toDouble()) / ln(10.0)).toFloat()

/** Sub-sample peak offset in -0.5..0.5 from three neighbouring values (parabolic fit). */
fun parabolicOffset(a: Float, b: Float, c: Float): Float {
    val den = a - 2 * b + c
    if (abs(den) < 1e-12f) return 0f
    return (0.5f * (a - c) / den).coerceIn(-0.5f, 0.5f)
}

fun Double.roundToIntSafe(): Int = if (isNaN()) 0 else roundToInt()
