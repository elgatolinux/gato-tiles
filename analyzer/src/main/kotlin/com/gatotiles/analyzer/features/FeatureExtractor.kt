package com.gatotiles.analyzer.features

import com.gatotiles.analyzer.dsp.FloatList
import com.gatotiles.analyzer.dsp.MelFilterBank
import com.gatotiles.analyzer.dsp.RealFft
import com.gatotiles.analyzer.dsp.Windows
import com.gatotiles.analyzer.dsp.nearestPow2
import com.gatotiles.analyzer.dsp.nextPow2
import kotlin.math.abs
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Frame-level features of a whole song. Frame t is centered at t * hop samples. */
class Features(
    val sampleRate: Int,
    val hop: Int,
    val frameRate: Double,
    val fluxTotal: FloatArray,
    val fluxLow: FloatArray,
    val fluxMid: FloatArray,
    val fluxHigh: FloatArray,
    val rms: FloatArray,
    val peak: FloatArray,
    /** Summed log band energy per frame for the low / mid / high band groups. */
    val energyLow: FloatArray,
    val energyMid: FloatArray,
    val energyHigh: FloatArray,
    /** Pitch salience every [PITCH_STRIDE] frames, see [SalienceCalculator]. */
    val salience: List<FloatArray>,
    /** Cepstral timbre per frame (coefficients 1..[TIMBRE_DIM] of the log-mel spectrum), flattened. */
    val timbre: FloatArray,
    val durationSec: Double,
) {
    val nFrames: Int get() = fluxTotal.size
    fun frameTime(t: Double): Double = t / frameRate
    fun timeToFrame(sec: Double): Double = sec * frameRate
    fun pitchFrameTime(p: Int): Double = (p * PITCH_STRIDE) / frameRate

    companion object {
        const val PITCH_STRIDE = 2
        const val TIMBRE_DIM = 12
    }
}

/**
 * Streaming feature extraction: push mono samples at [sampleRate], then [finish].
 * Computes a SuperFlux onset envelope (total + low/mid/high bands), RMS/peak, and
 * pitch salience on a longer window.
 */
class FeatureExtractor(val sampleRate: Int) {
    val hop: Int = max(1, (sampleRate / 86.1328).roundToInt())
    val frameRate: Double = sampleRate.toDouble() / hop
    val nFft: Int = nearestPow2(sampleRate * 0.046)
    val nFftPitch: Int = nextPow2(sampleRate * 0.15)

    private val fft = RealFft(nFft)
    private val fftPitch = RealFft(nFftPitch)
    private val win = Windows.hann(nFft)
    private val winPitch = Windows.hann(nFftPitch)
    private val frame = FloatArray(nFft)
    private val framePitch = FloatArray(nFftPitch)
    private val mag = FloatArray(nFft / 2 + 1)
    private val magPitch = FloatArray(nFftPitch / 2 + 1)
    private val mel = MelFilterBank(sampleRate, nFft, N_BANDS, 30f, min(11000f, sampleRate / 2f - 100f))
    private val bands = FloatArray(N_BANDS)
    private var logPrev1 = FloatArray(N_BANDS)
    private var logPrev2 = FloatArray(N_BANDS)
    private var logCur = FloatArray(N_BANDS)
    private val bandGroup = IntArray(N_BANDS) {
        val c = mel.centerHz[it]
        when {
            c < 200f -> 0
            c < 2500f -> 1
            else -> 2
        }
    }
    private val salienceCalc = SalienceCalculator(sampleRate, nFftPitch)

    private val fluxTotal = FloatList(8192)
    private val fluxLow = FloatList(8192)
    private val fluxMid = FloatList(8192)
    private val fluxHigh = FloatList(8192)
    private val rms = FloatList(8192)
    private val peak = FloatList(8192)
    private val energyLow = FloatList(8192)
    private val energyMid = FloatList(8192)
    private val energyHigh = FloatList(8192)
    private val salience = ArrayList<FloatArray>(4096)
    private val timbre = FloatList(8192 * Features.TIMBRE_DIM)
    // DCT-II basis for coefficients 1..TIMBRE_DIM of the log-mel bands.
    private val dct = Array(Features.TIMBRE_DIM) { k ->
        FloatArray(N_BANDS) { b -> kotlin.math.cos(Math.PI * (k + 1) * (b + 0.5) / N_BANDS).toFloat() }
    }

    private var buf = FloatArray(1 shl 16)
    private var bufStart = 0L
    private var bufLen = 0
    private var total = 0L
    private var nextFrame = 0L
    private var finished = false

    val samplesPushed: Long get() = total

    fun push(x: FloatArray, off: Int = 0, len: Int = x.size) {
        check(!finished) { "already finished" }
        if (len <= 0) return
        compact()
        if (bufLen + len > buf.size) buf = buf.copyOf(max(bufLen + len, buf.size * 2))
        System.arraycopy(x, off, buf, bufLen, len)
        bufLen += len
        total += len
        process(final = false)
    }

    fun finish(): Features {
        if (!finished) {
            process(final = true)
            finished = true
        }
        return Features(
            sampleRate = sampleRate,
            hop = hop,
            frameRate = frameRate,
            fluxTotal = fluxTotal.toArray(),
            fluxLow = fluxLow.toArray(),
            fluxMid = fluxMid.toArray(),
            fluxHigh = fluxHigh.toArray(),
            rms = rms.toArray(),
            peak = peak.toArray(),
            energyLow = energyLow.toArray(),
            energyMid = energyMid.toArray(),
            energyHigh = energyHigh.toArray(),
            salience = salience,
            timbre = timbre.toArray(),
            durationSec = total.toDouble() / sampleRate,
        )
    }

    private fun process(final: Boolean) {
        val right = nFftPitch / 2
        while (true) {
            val center = nextFrame * hop
            if (final) {
                if (center >= total) break
            } else if (center + right > total) {
                break
            }
            computeFrame(nextFrame, center)
            nextFrame++
        }
    }

    private fun compact() {
        val keepFrom = nextFrame * hop - nFftPitch / 2 - hop
        val drop = (keepFrom - bufStart).toInt()
        if (drop > 32768 && drop <= bufLen) {
            System.arraycopy(buf, drop, buf, 0, bufLen - drop)
            bufLen -= drop
            bufStart += drop
        }
    }

    private fun fillWindow(center: Long, n: Int, w: FloatArray, out: FloatArray) {
        val start = center - n / 2 - bufStart
        for (i in 0 until n) {
            val idx = start + i
            out[i] = if (idx < 0 || idx >= bufLen) 0f else buf[idx.toInt()] * w[i]
        }
    }

    private fun computeFrame(t: Long, center: Long) {
        fillWindow(center, nFft, win, frame)
        fft.magnitude(frame, mag)
        mel.apply(mag, bands)
        for (b in 0 until N_BANDS) logCur[b] = ln1p(bands[b].toDouble()).toFloat()
        var tot = 0f
        var lo = 0f
        var mid = 0f
        var hi = 0f
        var eLo = 0f
        var eMid = 0f
        var eHi = 0f
        for (b in 0 until N_BANDS) {
            when (bandGroup[b]) {
                0 -> eLo += logCur[b]
                1 -> eMid += logCur[b]
                else -> eHi += logCur[b]
            }
            // SuperFlux: compare against a frequency max-filtered frame two hops back.
            val l = if (b > 0) logPrev2[b - 1] else logPrev2[b]
            val r = if (b < N_BANDS - 1) logPrev2[b + 1] else logPrev2[b]
            val ref = max(logPrev2[b], max(l, r))
            val d = logCur[b] - ref
            if (d > 0f) {
                tot += d
                when (bandGroup[b]) {
                    0 -> lo += d
                    1 -> mid += d
                    else -> hi += d
                }
            }
        }
        for (k in 0 until Features.TIMBRE_DIM) {
            val basis = dct[k]
            var acc = 0f
            for (b in 0 until N_BANDS) acc += basis[b] * logCur[b]
            timbre.add(acc)
        }
        fluxTotal.add(tot)
        fluxLow.add(lo)
        fluxMid.add(mid)
        fluxHigh.add(hi)
        energyLow.add(eLo)
        energyMid.add(eMid)
        energyHigh.add(eHi)
        val tmp = logPrev2
        logPrev2 = logPrev1
        logPrev1 = logCur
        logCur = tmp

        var s = 0.0
        var pk = 0f
        val segStart = center - hop / 2 - bufStart
        for (i in 0 until hop) {
            val idx = segStart + i
            if (idx < 0 || idx >= bufLen) continue
            val v = buf[idx.toInt()]
            s += v * v
            val a = abs(v)
            if (a > pk) pk = a
        }
        rms.add(sqrt(s / hop).toFloat())
        peak.add(pk)

        if (t % Features.PITCH_STRIDE == 0L) {
            fillWindow(center, nFftPitch, winPitch, framePitch)
            fftPitch.magnitude(framePitch, magPitch)
            salience.add(salienceCalc.compute(magPitch))
        }
    }

    companion object {
        const val N_BANDS = 64
    }
}
