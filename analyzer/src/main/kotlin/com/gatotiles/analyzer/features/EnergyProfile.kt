package com.gatotiles.analyzer.features

import com.gatotiles.analyzer.dsp.interp
import com.gatotiles.analyzer.dsp.movingAverage
import com.gatotiles.analyzer.dsp.percentile
import com.gatotiles.analyzer.dsp.toDb
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Loudness-based intensity curve (0 = quietest parts of the song, 1 = loudest). */
class EnergyProfile(
    val intensity: FloatArray,
    val db: FloatArray,
    val frameRate: Double,
) {
    fun at(t: Double): Float = interp(intensity, t * frameRate)

    fun dbAt(t: Double): Float = interp(db, t * frameRate)

    fun mean(t0: Double, t1: Double): Float {
        val a = max(0, (t0 * frameRate).toInt())
        val b = min(intensity.size, max(a + 1, (t1 * frameRate).toInt()))
        if (a >= intensity.size) return 0f
        var s = 0f
        for (i in a until b) s += intensity[i]
        return s / (b - a)
    }

    /** Resample to [rate] values per second, as 0..100 integers. */
    fun resampled(rate: Double, durationSec: Double): List<Int> {
        val n = max(1, (durationSec * rate).roundToInt())
        return List(n) { (at(it / rate) * 100).roundToInt().coerceIn(0, 100) }
    }

    companion object {
        fun from(f: Features): EnergyProfile {
            val raw = FloatArray(f.nFrames) { toDb(f.rms[it]) }
            val smooth = movingAverage(raw, (1.5 * f.frameRate).roundToInt())
            val act = FloatArray(smooth.size)
            var c = 0
            for (v in smooth) if (v > -55f) act[c++] = v
            val lo = percentile(act, 0.10, c)
            val hi = max(lo + 3f, percentile(act, 0.95, c))
            val inten = FloatArray(smooth.size) { ((smooth[it] - lo) / (hi - lo)).coerceIn(0f, 1f) }
            return EnergyProfile(inten, smooth, f.frameRate)
        }
    }
}
