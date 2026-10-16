package com.gatotiles.analyzer.melody

import com.gatotiles.analyzer.dsp.percentile
import com.gatotiles.analyzer.features.Features
import com.gatotiles.analyzer.features.SalienceCalculator
import com.gatotiles.analyzer.rhythm.Onset
import com.gatotiles.analyzer.rhythm.OnsetEnvelopes
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MelodyNote(val start: Double, val end: Double, val midi: Int, val confidence: Float) {
    val duration: Double get() = end - start
    override fun toString() = "Note(%.3f-%.3f midi=%d c=%.2f)".format(start, end, midi, confidence)
}

/**
 * Predominant-pitch tracking: Viterbi decoding over the salience frames (with an
 * unvoiced state), then segmentation of the pitch track into notes whose starts are
 * snapped to detected onsets.
 */
object MelodyTracker {
    private const val UNVOICED_EMISSION = 0.22f
    private const val SWITCH_COST = 0.35f
    private const val STEP_COST = 0.035f

    /** Long pitch windows detect note starts late; shifted before snapping to onsets. */
    var startCorrection = -0.03

    fun track(f: Features, env: OnsetEnvelopes, onsets: List<Onset>): List<MelodyNote> {
        val nP = f.salience.size
        if (nP < 8) return emptyList()
        val nB = SalienceCalculator.N_BINS
        val frameMax = FloatArray(nP)
        var cnt = 0
        val nonZero = FloatArray(nP)
        for (p in 0 until nP) {
            var m = 0f
            for (v in f.salience[p]) if (v > m) m = v
            frameMax[p] = m
            if (m > 0f) nonZero[cnt++] = m
        }
        val g = percentile(nonZero, 0.9, cnt)
        if (g <= 0f) return emptyList()
        val allowed = BooleanArray(nP) { p ->
            val t = p * Features.PITCH_STRIDE
            t < env.active.size && env.active[t]
        }

        val back = Array(nP) { ShortArray(nB + 1) }
        var prev = FloatArray(nB + 1)
        var cur = FloatArray(nB + 1)
        val left = FloatArray(nB)
        val leftArg = IntArray(nB)
        val right = FloatArray(nB)
        val rightArg = IntArray(nB)
        for (b in 0 until nB) prev[b] = if (allowed[0]) min(f.salience[0][b] / g, 1.5f) else 0f
        prev[nB] = UNVOICED_EMISSION
        for (p in 1 until nP) {
            left[0] = prev[0]
            leftArg[0] = 0
            for (b in 1 until nB) {
                val c = left[b - 1] - STEP_COST
                if (prev[b] >= c) {
                    left[b] = prev[b]
                    leftArg[b] = b
                } else {
                    left[b] = c
                    leftArg[b] = leftArg[b - 1]
                }
            }
            right[nB - 1] = prev[nB - 1]
            rightArg[nB - 1] = nB - 1
            for (b in nB - 2 downTo 0) {
                val c = right[b + 1] - STEP_COST
                if (prev[b] >= c) {
                    right[b] = prev[b]
                    rightArg[b] = b
                } else {
                    right[b] = c
                    rightArg[b] = rightArg[b + 1]
                }
            }
            val fromUnvoiced = prev[nB] - SWITCH_COST
            var maxV = Float.NEGATIVE_INFINITY
            var maxArg = 0
            for (b in 0 until nB) {
                if (prev[b] > maxV) {
                    maxV = prev[b]
                    maxArg = b
                }
            }
            val sal = f.salience[p]
            val ok = allowed[p]
            val bp = back[p]
            for (b in 0 until nB) {
                var best: Float
                var arg: Int
                if (left[b] >= right[b]) {
                    best = left[b]
                    arg = leftArg[b]
                } else {
                    best = right[b]
                    arg = rightArg[b]
                }
                if (fromUnvoiced > best) {
                    best = fromUnvoiced
                    arg = nB
                }
                cur[b] = best + (if (ok) min(sal[b] / g, 1.5f) else 0f)
                bp[b] = arg.toShort()
            }
            if (prev[nB] >= maxV - SWITCH_COST) {
                cur[nB] = prev[nB] + UNVOICED_EMISSION
                bp[nB] = nB.toShort()
            } else {
                cur[nB] = maxV - SWITCH_COST + UNVOICED_EMISSION
                bp[nB] = maxArg.toShort()
            }
            var mx = Float.NEGATIVE_INFINITY
            for (v in cur) if (v > mx) mx = v
            for (i in 0..nB) cur[i] -= mx
            val tmp = prev
            prev = cur
            cur = tmp
        }
        val path = IntArray(nP)
        var s = 0
        for (i in 1..nB) if (prev[i] > prev[s]) s = i
        path[nP - 1] = s
        for (p in nP - 1 downTo 1) path[p - 1] = back[p][path[p]].toInt()

        val midi = FloatArray(nP) { if (path[it] == nB) Float.NaN else SalienceCalculator.binToMidi(path[it].toFloat()) }
        val conf = FloatArray(nP) { if (path[it] == nB) 0f else min(1f, f.salience[it][path[it]] / g) }
        val smooth = FloatArray(nP)
        val tmp = FloatArray(5)
        for (p in 0 until nP) {
            if (midi[p].isNaN()) {
                smooth[p] = Float.NaN
                continue
            }
            var c = 0
            for (k in max(0, p - 2)..min(nP - 1, p + 2)) if (!midi[k].isNaN()) tmp[c++] = midi[k]
            java.util.Arrays.sort(tmp, 0, c)
            smooth[p] = tmp[c / 2]
        }

        val raw = segment(f, smooth, conf)
        val split = splitRepeated(raw, onsets)
        val snapped = split.map { snapStart(it, onsets) }.sortedBy { it.start }
        return merge(snapped, onsets).filter { it.duration >= 0.08 }
    }

    private fun segment(f: Features, smooth: FloatArray, conf: FloatArray): List<MelodyNote> {
        val notes = ArrayList<MelodyNote>()
        val pitches = ArrayList<Float>()
        var startP = -1
        var confSum = 0f
        fun close(endP: Int) {
            if (startP >= 0 && pitches.isNotEmpty()) {
                val st = f.pitchFrameTime(startP) + startCorrection
                val en = f.pitchFrameTime(endP)
                val sorted = pitches.sorted()
                val med = sorted[sorted.size / 2]
                if (en - st >= 0.06) notes.add(MelodyNote(max(0.0, st), en, med.roundToInt(), confSum / pitches.size))
            }
            startP = -1
            pitches.clear()
            confSum = 0f
        }
        val n = smooth.size
        for (p in 0 until n) {
            val m = smooth[p]
            if (m.isNaN()) {
                close(p)
                continue
            }
            if (startP < 0) {
                startP = p
                pitches.add(m)
                confSum += conf[p]
                continue
            }
            val k = min(6, pitches.size)
            var ref = 0f
            for (i in pitches.size - k until pitches.size) ref += pitches[i]
            ref /= k
            if (abs(m - ref) > 0.8f) {
                val nx = if (p + 1 < n) smooth[p + 1] else Float.NaN
                if (!nx.isNaN() && abs(nx - m) < 0.8f) {
                    close(p)
                    startP = p
                    pitches.add(m)
                    confSum += conf[p]
                }
            } else {
                pitches.add(m)
                confSum += conf[p]
            }
        }
        close(n)
        return notes
    }

    private fun splitRepeated(notes: List<MelodyNote>, onsets: List<Onset>): List<MelodyNote> {
        val out = ArrayList<MelodyNote>()
        var oi = 0
        for (note in notes) {
            var s = note.start
            while (oi < onsets.size && onsets[oi].time < note.start + 0.10) oi++
            var j = oi
            while (j < onsets.size && onsets[j].time < note.end - 0.08) {
                val o = onsets[j]
                if (o.strength >= 0.25f && o.mid >= 0.2f && o.time - s >= 0.10) {
                    out.add(MelodyNote(s, o.time, note.midi, note.confidence))
                    s = o.time
                }
                j++
            }
            out.add(MelodyNote(s, note.end, note.midi, note.confidence))
        }
        return out
    }

    private fun snapStart(note: MelodyNote, onsets: List<Onset>): MelodyNote {
        var lo = 0
        var hi = onsets.size
        val from = note.start - 0.08
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (onsets[mid].time < from) lo = mid + 1 else hi = mid
        }
        var best: Onset? = null
        var i = lo
        while (i < onsets.size && onsets[i].time <= note.start + 0.05) {
            val o = onsets[i]
            if (best == null || abs(o.time - note.start) < abs(best.time - note.start)) best = o
            i++
        }
        if (best == null || best.time >= note.end - 0.04) return note
        return MelodyNote(best.time, note.end, note.midi, note.confidence)
    }

    private fun merge(notes: List<MelodyNote>, onsets: List<Onset>): List<MelodyNote> {
        if (notes.isEmpty()) return notes
        val out = ArrayList<MelodyNote>()
        var cur = notes[0]
        for (k in 1 until notes.size) {
            val nx = notes[k]
            val gap = nx.start - cur.end
            val sameish = nx.midi == cur.midi && gap < 0.04
            val hasOnset = onsets.any { it.time >= cur.end - 0.02 && it.time <= nx.start + 0.02 && it.strength >= 0.25f }
            cur = if (sameish && !hasOnset) {
                MelodyNote(cur.start, max(cur.end, nx.end), cur.midi, (cur.confidence + nx.confidence) / 2)
            } else {
                out.add(cur)
                if (nx.start < cur.end) MelodyNote(cur.end, nx.end, nx.midi, nx.confidence) else nx
            }
        }
        out.add(cur)
        return out
    }
}
