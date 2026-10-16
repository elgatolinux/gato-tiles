package com.gatotiles.analyzer.rhythm

import com.gatotiles.analyzer.dsp.interp
import com.gatotiles.analyzer.dsp.median
import com.gatotiles.analyzer.dsp.parabolicOffset
import com.gatotiles.analyzer.dsp.stddev
import com.gatotiles.analyzer.features.Features
import com.gatotiles.analyzer.features.SalienceCalculator
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Beat grid of a song: beat times covering the whole duration, the bar phase
 * (beat i is a downbeat when i % 4 == downbeatPhase) and the subdivision feel
 * (4 = straight sixteenths, 3 = triplets/swing).
 */
class BeatGrid(
    val beats: DoubleArray,
    val bpm: Double,
    val downbeatPhase: Int,
    val subdivision: Int,
    /** Index range of beats that come from tracking (not extrapolation). */
    val trackedFrom: Int,
    val trackedTo: Int,
) {
    fun isDownbeat(i: Int): Boolean = Math.floorMod(i - downbeatPhase, 4) == 0

    /** Beat interval around time t. */
    fun beatLengthAt(t: Double): Double {
        if (beats.size < 2) return 60.0 / max(1.0, bpm)
        val i = indexAt(t).coerceIn(0, beats.size - 2)
        return beats[i + 1] - beats[i]
    }

    /** Index of the last beat <= t (or 0). */
    fun indexAt(t: Double): Int {
        var lo = 0
        var hi = beats.size - 1
        if (hi < 0 || t < beats[0]) return 0
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (beats[mid] <= t) lo = mid else hi = mid - 1
        }
        return lo
    }
}

object BeatTracker {

    fun track(f: Features, env: OnsetEnvelopes, onsets: List<Onset>): BeatGrid {
        val fr = f.frameRate
        val e = env.total
        val duration = f.durationSec
        val period = estimatePeriod(e, fr)
        val bpm0 = 60.0 * fr / period
        var beatFrames = dpBeats(e, period)
        if (beatFrames.size < 4) {
            // Nothing rhythmic detected: fall back to a plain grid at the estimated tempo.
            val step = 60.0 / bpm0
            val beats = generateSequence(0.0) { it + step }.takeWhile { it < duration }.toList().toDoubleArray()
            return BeatGrid(beats, bpm0, 0, 4, 0, max(0, beats.size - 1))
        }
        val tracked = octaveCorrect(refine(beatFrames, e, fr), e, fr)
        val (beats, from, to) = extend(tracked, duration)
        val ibis = DoubleArray(max(0, to - from)) { beats[from + it + 1] - beats[from + it] }
        val bpm = if (ibis.isNotEmpty()) 60.0 / median(ibis) else bpm0
        val phase = downbeatPhase(f, env, beats, from, to)
        val sub = detectSubdivision(beats, onsets, from, to)
        return BeatGrid(beats, bpm, phase, sub, from, to)
    }

    /** Accent strength (envelope max within +-2 frames) at time t. */
    private fun strengthAt(env: FloatArray, fr: Double, t: Double): Double {
        val c = (t * fr).roundToInt()
        var m = 0f
        for (k in c - 2..c + 2) if (k in env.indices) m = max(m, env[k])
        return m.toDouble()
    }

    /** Even-vs-odd beat accent ratio (>= 1); high values mean every other beat is the real one. */
    fun alternationRatio(beats: DoubleArray, env: FloatArray, fr: Double): Double {
        var even = 0.0
        var odd = 0.0
        for (i in beats.indices) {
            val s = strengthAt(env, fr, beats[i])
            if (i % 2 == 0) even += s else odd += s
        }
        even /= max(1, (beats.size + 1) / 2)
        odd /= max(1, beats.size / 2)
        return max(even, odd) / max(1e-9, min(even, odd))
    }

    /**
     * Fixes double/half tempo errors with the accent pattern: a fast grid whose every other
     * beat is clearly weaker is really an eighth-note grid of a slower song, and a very slow
     * grid with strong attacks halfway between beats is a half-tempo grid.
     */
    fun octaveCorrect(beats: DoubleArray, env: FloatArray, fr: Double): DoubleArray {
        if (beats.size < 16) return beats
        val ibi = median(DoubleArray(beats.size - 1) { beats[it + 1] - beats[it] })
        val bpm = 60.0 / ibi
        if (bpm > 140.0) {
            val ratio = alternationRatio(beats, env, fr)
            if ((bpm > 160.0 && ratio > 1.08) || ratio > 1.25) {
                var even = 0.0
                var odd = 0.0
                for (i in beats.indices) {
                    if (i % 2 == 0) even += strengthAt(env, fr, beats[i]) else odd += strengthAt(env, fr, beats[i])
                }
                val keepEven = even / max(1, (beats.size + 1) / 2) >= odd / max(1, beats.size / 2)
                return beats.filterIndexed { i, _ -> (i % 2 == 0) == keepEven }.toDoubleArray()
            }
        } else if (bpm < 70.0) {
            var onBeat = 0.0
            var mid = 0.0
            for (i in 0 until beats.size - 1) {
                onBeat += strengthAt(env, fr, beats[i])
                mid += strengthAt(env, fr, (beats[i] + beats[i + 1]) / 2)
            }
            if (mid / max(1e-9, onBeat) > 0.6) {
                val out = DoubleArray(beats.size * 2 - 1)
                for (i in beats.indices) {
                    out[2 * i] = beats[i]
                    if (i + 1 < beats.size) out[2 * i + 1] = (beats[i] + beats[i + 1]) / 2
                }
                return out
            }
        }
        return beats
    }

    /** Top tempo candidates (bpm, score) for diagnostics. */
    fun tempoCandidates(env: FloatArray, fr: Double, count: Int = 5): List<Pair<Double, Double>> {
        val score = tempoScores(env, fr)
        val peaks = ArrayList<Pair<Double, Double>>()
        for (lag in 2 until score.size - 1) {
            if (score[lag] > 0 && score[lag] >= score[lag - 1] && score[lag] >= score[lag + 1]) {
                peaks.add(60.0 * fr / lag to score[lag])
            }
        }
        return peaks.sortedByDescending { it.second }.take(count)
    }

    /** Beat period in frames from the onset envelope autocorrelation with a tempo prior. */
    fun estimatePeriod(env: FloatArray, fr: Double): Double {
        val score = tempoScores(env, fr)
        var best = -1
        for (lag in 1 until score.size - 1) if (score[lag] > 0 && (best < 0 || score[lag] > score[best])) best = lag
        if (best <= 0) return fr * 0.5 // 120 bpm
        val off = parabolicOffset(score[best - 1].toFloat(), score[best].toFloat(), score[best + 1].toFloat())
        return best + off.toDouble()
    }

    private fun tempoScores(env: FloatArray, fr: Double): DoubleArray {
        val n = env.size
        val w = max(1, fr.roundToInt())
        // High-pass: remove the local mean and keep the positive part.
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + env[i]
        val e = FloatArray(n) { i ->
            val a = max(0, i - w / 2)
            val b = min(n, i + w / 2 + 1)
            max(0f, env[i] - ((prefix[b] - prefix[a]) / (b - a)).toFloat())
        }
        val minLag = floor(fr * 60.0 / 220.0).toInt()
        val maxLag = (fr * 60.0 / 45.0).toInt() + 1
        val acc = DoubleArray(maxLag + 2)
        val win = min(n, (8 * fr).roundToInt())
        val hopW = max(1, (2 * fr).roundToInt())
        var start = 0
        while (start + win <= n) {
            var e0 = 0.0
            for (i in start until start + win) e0 += e[i].toDouble() * e[i]
            if (e0 > 1e-9) {
                for (lag in 0..maxLag + 1) {
                    var s = 0.0
                    for (i in start until start + win - lag) s += e[i].toDouble() * e[i + lag]
                    acc[lag] += s / e0
                }
            }
            if (start + win == n) break
            start = min(start + hopW, n - win)
        }
        val score = DoubleArray(maxLag + 2)
        for (lag in max(1, minLag)..maxLag) {
            val bpm = 60.0 * fr / lag
            val lg = ln(bpm / 120.0) / ln(2.0)
            val prior = exp(-0.5 * lg * lg / (0.9 * 0.9))
            score[lag] = acc[lag] * prior
        }
        return score
    }

    /** Ellis (2007) dynamic-programming beat tracker, as in librosa. Returns beat frames. */
    fun dpBeats(env: FloatArray, period: Double, tightness: Double = 100.0): IntArray {
        val n = env.size
        if (n == 0) return IntArray(0)
        val std = max(stddev(env), 1e-9)
        val half = max(1, period.roundToInt())
        val gw = DoubleArray(2 * half + 1) {
            val x = (it - half) * 32.0 / period
            exp(-0.5 * x * x)
        }
        val local = DoubleArray(n)
        for (t in 0 until n) {
            var s = 0.0
            for (k in -half..half) {
                val i = t + k
                if (i in 0 until n) s += env[i] / std * gw[k + half]
            }
            local[t] = s
        }
        val minD = max(1, (period / 2).roundToInt())
        val maxD = max(minD + 1, (2 * period).roundToInt())
        val pen = DoubleArray(maxD + 1)
        for (d in minD..maxD) {
            val l = ln(d / period)
            pen[d] = -tightness * l * l
        }
        val cum = DoubleArray(n)
        val back = IntArray(n) { -1 }
        val localMax = local.maxOrNull() ?: 0.0
        var firstBeat = true
        for (t in 0 until n) {
            var best = Double.NEGATIVE_INFINITY
            var arg = -1
            for (d in minD..maxD) {
                val p = t - d
                val s = pen[d] + (if (p >= 0) cum[p] else 0.0)
                if (s > best) {
                    best = s
                    arg = p
                }
            }
            cum[t] = local[t] + best
            if (firstBeat && local[t] < 0.01 * localMax) {
                back[t] = -1
            } else {
                back[t] = if (arg >= 0) arg else -1
                firstBeat = false
            }
        }
        // Last beat: last local max of the cumulative score above half the median of local maxima.
        val maxima = ArrayList<Int>()
        for (t in 1 until n - 1) if (cum[t] > cum[t - 1] && cum[t] >= cum[t + 1]) maxima.add(t)
        if (maxima.isEmpty()) return IntArray(0)
        val med = median(DoubleArray(maxima.size) { cum[maxima[it]] })
        var last = maxima.last()
        for (i in maxima.indices.reversed()) {
            if (2 * cum[maxima[i]] > med) {
                last = maxima[i]
                break
            }
        }
        val beats = ArrayList<Int>()
        var b = last
        while (b >= 0) {
            beats.add(b)
            b = back[b]
        }
        beats.reverse()
        // Trim weak beats at both ends (silence before/after the music).
        val vals = DoubleArray(beats.size) { local[beats[it]] }
        val smooth = DoubleArray(vals.size) { i ->
            var s = 0.0
            var wsum = 0.0
            for (k in -2..2) {
                val j = i + k
                if (j in vals.indices) {
                    val w = 0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * (k + 3) / 6.0)
                    s += vals[j] * w
                    wsum += w
                }
            }
            s / max(wsum, 1e-9)
        }
        var rmsS = 0.0
        for (v in smooth) rmsS += v * v
        val thr = 0.5 * sqrt(rmsS / max(1, smooth.size))
        var a = 0
        var z = smooth.size - 1
        while (a < z && smooth[a] <= thr) a++
        while (z > a && smooth[z] <= thr) z--
        return IntArray(z - a + 1) { beats[a + it] }
    }

    /**
     * Turns raw beat frames into precise times: piecewise-linear fits remove the frame
     * jitter (constant-tempo music gets a perfectly regular grid), then each segment's
     * phase is fine-tuned against the onset envelope.
     */
    fun refine(beatFrames: IntArray, env: FloatArray, fr: Double): DoubleArray {
        val raw = DoubleArray(beatFrames.size) { beatFrames[it] / fr }
        val n = raw.size
        val out = raw.copyOf()
        var start = 0
        while (start < n - 1) {
            var end = min(n - 1, start + 7)
            var fit = fitLine(raw, start, end)
            if (maxResidual(raw, start, end, fit) > 0.03) {
                // Irregular region: smooth locally and move on.
                for (i in start..end) out[i] = localSmooth(raw, i)
                start = end
                continue
            }
            while (end < n - 1) {
                val f2 = fitLine(raw, start, end + 1)
                if (maxResidual(raw, start, end + 1, f2) > 0.035) break
                end++
                fit = f2
            }
            val phase = finePhase(env, fr, fit, start, end)
            for (i in start..end) out[i] = fit.first + phase + fit.second * (i - start)
            start = end
        }
        // Enforce monotonic order after independent segment fits.
        for (i in 1 until n) if (out[i] <= out[i - 1] + 0.05) out[i] = out[i - 1] + max(0.05, raw[i] - raw[i - 1])
        return out
    }

    private fun fitLine(t: DoubleArray, a: Int, b: Int): Pair<Double, Double> {
        val m = b - a + 1
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        for (i in a..b) {
            val x = (i - a).toDouble()
            sx += x
            sy += t[i]
            sxx += x * x
            sxy += x * t[i]
        }
        val den = m * sxx - sx * sx
        val slope = if (abs(den) < 1e-12) 0.0 else (m * sxy - sx * sy) / den
        val icpt = (sy - slope * sx) / m
        return icpt to slope
    }

    private fun maxResidual(t: DoubleArray, a: Int, b: Int, fit: Pair<Double, Double>): Double {
        var mx = 0.0
        for (i in a..b) mx = max(mx, abs(t[i] - (fit.first + fit.second * (i - a))))
        return mx
    }

    private fun localSmooth(t: DoubleArray, i: Int): Double {
        val a = max(0, i - 3)
        val b = min(t.size - 1, i + 3)
        if (b - a < 2) return t[i]
        val fit = fitLine(t, a, b)
        return fit.first + fit.second * (i - a)
    }

    private fun finePhase(env: FloatArray, fr: Double, fit: Pair<Double, Double>, a: Int, b: Int): Double {
        var best = 0.0
        var bestScore = Double.NEGATIVE_INFINITY
        var d = -0.025
        while (d <= 0.025 + 1e-9) {
            var s = 0.0
            for (i in a..b) s += interp(env, (fit.first + d + fit.second * (i - a)) * fr)
            // Small preference for not moving at all.
            s -= abs(d) * 0.5 * (b - a + 1)
            if (s > bestScore) {
                bestScore = s
                best = d
            }
            d += 0.001
        }
        return best
    }

    /** Extrapolates the tracked beats so the grid covers [0, duration]. */
    private fun extend(tracked: DoubleArray, duration: Double): Triple<DoubleArray, Int, Int> {
        val n = tracked.size
        val headStep = if (n >= 5) (tracked[4] - tracked[0]) / 4 else tracked[n - 1] - tracked[n - 2]
        val tailStep = if (n >= 5) (tracked[n - 1] - tracked[n - 5]) / 4 else tracked[n - 1] - tracked[n - 2]
        val head = ArrayList<Double>()
        var t = tracked[0] - headStep
        while (t >= -0.001 && headStep > 0.05) {
            head.add(t)
            t -= headStep
        }
        head.reverse()
        val tail = ArrayList<Double>()
        t = tracked[n - 1] + tailStep
        while (t < duration + tailStep && tailStep > 0.05) {
            tail.add(t)
            t += tailStep
        }
        val all = DoubleArray(head.size + n + tail.size)
        for (i in head.indices) all[i] = max(0.0, head[i])
        for (i in 0 until n) all[head.size + i] = tracked[i]
        for (i in tail.indices) all[head.size + n + i] = tail[i]
        return Triple(all, head.size, head.size + n - 1)
    }

    /** Bar phase from kick accents and harmonic changes (assumes 4/4). */
    private fun downbeatPhase(f: Features, env: OnsetEnvelopes, beats: DoubleArray, from: Int, to: Int): Int {
        if (to - from < 8) return Math.floorMod(from, 4)
        val fr = f.frameRate
        val nb = SalienceCalculator.N_BINS
        val pcOfBin = IntArray(nb) { Math.floorMod(SalienceCalculator.binToMidi(it.toFloat()).roundToInt(), 12) }
        val chroma = Array(to - from + 1) { DoubleArray(12) }
        for (k in from..to) {
            val t0 = beats[k]
            val t1 = if (k + 1 < beats.size) beats[k + 1] else t0 + 0.5
            val p0 = (t0 * fr / 2).toInt()
            val p1 = max(p0 + 1, (t1 * fr / 2).toInt())
            val c = chroma[k - from]
            for (p in p0 until min(p1, f.salience.size)) {
                val s = f.salience[p]
                for (b in 0 until nb) c[pcOfBin[b]] += s[b].toDouble()
            }
            var norm = 0.0
            for (v in c) norm += v * v
            norm = sqrt(norm)
            if (norm > 0) for (i in 0 until 12) c[i] /= norm
        }
        val score = DoubleArray(4)
        val count = IntArray(4)
        for (k in from..to) {
            val frame = (beats[k] * fr).roundToInt()
            var low = 0f
            for (q in max(0, frame - 2)..min(env.low.size - 1, frame + 2)) low = max(low, env.low[q])
            var nov = 0.0
            if (k > from) {
                var dot = 0.0
                val a = chroma[k - from]
                val b = chroma[k - from - 1]
                for (i in 0 until 12) dot += a[i] * b[i]
                nov = 1.0 - dot
            }
            val ph = Math.floorMod(k, 4)
            score[ph] += low + 1.5 * nov
            count[ph]++
        }
        var best = 0
        for (p in 1 until 4) if (score[p] / max(1, count[p]) > score[best] / max(1, count[best])) best = p
        return best
    }

    /** 4 for straight feel, 3 when off-beat onsets sit on triplet positions (swing/shuffle). */
    private fun detectSubdivision(beats: DoubleArray, onsets: List<Onset>, from: Int, to: Int): Int {
        if (to - from < 8) return 4
        var w2 = 0.0
        var w3 = 0.0
        var count = 0
        val grid2 = doubleArrayOf(0.0, 0.25, 0.5, 0.75, 1.0)
        val grid3 = doubleArrayOf(0.0, 1.0 / 3, 2.0 / 3, 1.0)
        var k = from
        for (o in onsets) {
            if (o.strength < 0.2f) continue
            if (o.time < beats[from] || o.time >= beats[to]) continue
            while (k < to - 1 && beats[k + 1] <= o.time) k++
            val len = beats[k + 1] - beats[k]
            val ph = (o.time - beats[k]) / len
            if (min(ph, 1 - ph) < 0.12) continue
            w2 += o.strength * grid2.minOf { abs(ph - it) }
            w3 += o.strength * grid3.minOf { abs(ph - it) }
            count++
        }
        return if (count >= 24 && w3 < 0.7 * w2) 3 else 4
    }
}
