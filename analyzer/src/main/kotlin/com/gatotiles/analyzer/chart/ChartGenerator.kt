package com.gatotiles.analyzer.chart

import com.gatotiles.analyzer.features.EnergyProfile
import com.gatotiles.analyzer.melody.MelodyNote
import com.gatotiles.analyzer.model.Chart
import com.gatotiles.analyzer.model.Difficulty
import com.gatotiles.analyzer.model.Note
import com.gatotiles.analyzer.rhythm.BeatGrid
import com.gatotiles.analyzer.rhythm.Onset
import java.util.TreeMap
import java.util.TreeSet
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

internal class DifficultyParams(
    /** Finest metrical level allowed: 1 = beats, 2 = half beats, 3 = sixteenths/triplets, 4 = everything. */
    val maxLevel: Int,
    /** Minimum time between two consecutive tiles (any lane), seconds. */
    val minGap: Double,
    /** Target tiles per second in the quietest / loudest bars. */
    val npsLow: Double,
    val npsHigh: Double,
    /** Minimum score relative to the local loudness of the attacks (so quiet passages count too). */
    val minRel: Double,
    /** Attacks that sit off the beat grid need at least this relative score. */
    val offGridRel: Double,
    val holdMinBeats: Double,
    val holdMinSec: Double,
    /** Whether other tiles may appear while a hold is being held. */
    val notesDuringHold: Boolean,
    /** Fraction of tiles that become doubles (on the strongest accents). */
    val doubleFraction: Double,
    val doubleMinGapBeats: Double,
    /** Minimum time between two tiles in the same lane, seconds. */
    val jackMinGap: Double,
    /** Upper bound for the share of tiles that are holds. */
    val maxHoldFraction: Double,
    /**
     * Share of the notes of fast even trains (repeated notes, runs, streams) that get room in
     * the bar's tile budget on top of the loudness-based one, so fast passages get their tiles
     * even when they are not louder than the rest of the song.
     */
    val trainShare: Double,
    /** Minimum time between two tiles that are both part of a fast train, seconds. */
    val trainGap: Double,
) {
    /** Whether this difficulty plays fast trains note by note (also between the grid lines). */
    val playsTrains: Boolean get() = trainGap < minGap

    companion object {
        fun of(d: Difficulty): DifficultyParams = when (d) {
            Difficulty.EASY -> DifficultyParams(2, 0.34, 0.8, 2.0, 0.45, 0.8, 1.0, 0.45, false, 0.0, 8.0, 0.60, 0.25, 0.0, 0.34)
            Difficulty.NORMAL -> DifficultyParams(3, 0.21, 1.3, 3.3, 0.3, 0.55, 1.0, 0.40, false, 0.03, 4.0, 0.40, 0.20, 0.15, 0.21)
            Difficulty.HARD -> DifficultyParams(3, 0.105, 2.3, 5.9, 0.15, 0.28, 0.75, 0.32, true, 0.07, 2.0, 0.26, 0.18, 0.8, 0.09)
            Difficulty.EXPERT -> DifficultyParams(4, 0.095, 2.8, 7.4, 0.1, 0.15, 0.5, 0.28, true, 0.11, 1.0, 0.18, 0.15, 1.0, 0.07)
        }
    }
}

/**
 * Builds playable charts from the analysis. Tiles are placed exactly where the song has
 * attacks or melody notes (the beat grid only decides how important each one is, so swing,
 * triplets and rubato keep their real timing), density follows the song's intensity and is
 * judged relative to the surrounding music, repeated bars get the same rhythm, holds follow
 * sustained notes, doubles mark the strongest accents, and lanes move with the melody contour
 * (higher pitch = further right).
 */
class ChartGenerator(
    private val durationSec: Double,
    private val grid: BeatGrid,
    private val onsets: List<Onset>,
    private val melody: List<MelodyNote>,
    private val energy: EnergyProfile,
    private val seed: Long,
) {
    private class GridPoint(val time: Double, val level: Int, val beat: Int, val sub: Int, val barPos: Int)

    private class Candidate(
        val time: Double,
        val level: Int,
        val onset: Onset?,
        val onsetScore: Double,
        val bar: Int,
        /** Position inside the bar in twelfths of a beat (0..47), or -1 when off the grid. */
        val barPos: Int,
        val offGrid: Boolean,
        /** Steady-pulse tile on a beat without any detected attack. */
        val filler: Boolean,
        /** Part of a fast, evenly spaced train of attacks (see [trainMembers]). */
        val train: Boolean = false,
    ) {
        var melScore = 0.0
        var score = 0.0
        var rel = 0.0
    }

    private class Event(val time: Double, val level: Int, val onset: Onset?) {
        var holdEnd = -1.0
        var isDouble = false
        var pitch = Float.NaN
        val isHold: Boolean get() = holdEnd > 0
    }

    internal val meter = LocalMeter.analyze(grid.beats, onsets)
    private val onsetTimes = DoubleArray(onsets.size) { onsets[it].time }
    private val melodyStarts = DoubleArray(melody.size) { melody[it].start }
    private val gridPoints: List<GridPoint> = buildGrid()
    private val gridTimes = DoubleArray(gridPoints.size) { gridPoints[it].time }
    private val barBounds: DoubleArray = buildBarBounds()
    private val candidates: List<Candidate> = buildCandidates()
    private val candidatesByBar: Map<Int, List<Candidate>> = candidates.groupBy { it.bar }.toSortedMap()
    private val events = HashMap<Difficulty, List<Event>>()

    /** Chart for [d] with tiles spread over [lanes] lanes (the rhythm is the same for any lane count). */
    fun generate(d: Difficulty, lanes: Int = 4): Chart {
        require(lanes in 2..6) { "lanes must be 2..6" }
        val p = DifficultyParams.of(d)
        val ev = events.getOrPut(d) {
            val list = select(p).map { Event(it.time, it.level, it.onset) }
            for (e in list) e.pitch = pitchAt(e.time)
            makeHolds(list, p)
            makeDoubles(list, p)
            list
        }
        val rng = Random(seed * 31 + d.ordinal * 7 + lanes)
        val notes = assignLanes(ev, p, rng, lanes)
        return Chart(d, computeLevel(notes, lanes), notes, lanes)
    }

    /** Diagnostics: share of triplet-based and irregular beats. */
    fun meterSummary(): String =
        "tresillos %.0f%%, irregular %.0f%%, candidatos %d".format(meter.tripletShare * 100, meter.irregularShare * 100, candidates.size)

    /** Diagnostics: every candidate between [t0] and [t1] and whether [d] keeps it. */
    fun debugCandidates(d: Difficulty, t0: Double, t1: Double): List<String> {
        val p = DifficultyParams.of(d)
        val kept = select(p).map { it.time }.toHashSet()
        val final = generate(d).notes.map { it.t }.toHashSet()
        return candidates.filter { it.time in t0..t1 }.map { c ->
            val bar = c.bar
            val t0b = barBounds[bar]
            val t1b = min(barBounds[bar + 1], durationSec)
            val inten = energy.mean(t0b, t1b).toDouble()
            "%.3f lvl %d %s%s%s on %.2f mel %.2f score %.3f rel %.2f %s %s %s | bar %d int %.2f".format(
                c.time, c.level, if (c.offGrid) "off" else "   ", if (c.filler) "F" else " ", if (c.train) "T" else " ",
                c.onsetScore, c.melScore, c.score, c.rel,
                if (eligible(c, p)) "ok " else "-- ", if (c.time in kept) "SEL" else "   ", if (ms(c.time) in final) "NOTE" else "    ",
                bar, inten,
            )
        }
    }

    private fun buildGrid(): List<GridPoint> {
        val out = ArrayList<GridPoint>()
        val b = grid.beats
        for (i in 0 until b.size - 1) {
            val len = b[i + 1] - b[i]
            val sub = meter.subAt(i)
            val beatInBar = Math.floorMod(i - grid.downbeatPhase, 4)
            for (s in 0 until sub) {
                val t = b[i] + len * s / sub
                val level = if (s == 0) (if (grid.isDownbeat(i)) 0 else 1) else meter.levelAt(i, s)
                if (t >= 0.0 && t <= durationSec - 0.05) out.add(GridPoint(t, level, i, sub, beatInBar * 12 + s * 12 / sub))
            }
        }
        if (b.isNotEmpty() && b.last() <= durationSec - 0.05) {
            val i = b.size - 1
            out.add(GridPoint(b.last(), if (grid.isDownbeat(i)) 0 else 1, i, 4, Math.floorMod(i - grid.downbeatPhase, 4) * 12))
        }
        return out
    }

    private fun buildBarBounds(): DoubleArray {
        val starts = ArrayList<Double>()
        starts.add(0.0)
        for (g in gridPoints) if (g.level == 0 && g.barPos == 0 && g.time > starts.last() + 0.5) starts.add(g.time)
        starts.add(durationSec + 1.0)
        return starts.toDoubleArray()
    }

    private fun barIndexAt(t: Double): Int {
        var lo = 0
        var hi = barBounds.size - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (barBounds[mid] <= t) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun nearestIndex(a: DoubleArray, t: Double): Int {
        if (a.isEmpty()) return -1
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < t) lo = mid + 1 else hi = mid
        }
        if (lo == a.size) return a.size - 1
        if (lo > 0 && t - a[lo - 1] < a[lo] - t) return lo - 1
        return lo
    }

    /** Snap tolerance around grid point [g]: tight enough to tell straight from swung notes. */
    private fun snapTol(g: GridPoint): Double {
        val b = grid.beats
        val len = if (g.beat + 1 < b.size) b[g.beat + 1] - b[g.beat] else grid.beatLengthAt(g.time)
        return min(0.045, 0.42 * len / g.sub)
    }

    /** 80th percentile of [values] (sorted by [times]) within +-[half] seconds of each time. */
    private fun localReference(times: DoubleArray, values: DoubleArray, half: Double, floor: Double): DoubleArray {
        val out = DoubleArray(times.size)
        var a = 0
        var b = 0
        val buf = ArrayList<Double>()
        for (i in times.indices) {
            while (a < times.size && times[a] < times[i] - half) a++
            while (b < times.size && times[b] <= times[i] + half) b++
            buf.clear()
            for (k in a until b) buf.add(values[k])
            buf.sort()
            out[i] = if (buf.isEmpty()) floor else max(floor, buf[((buf.size - 1) * 0.8).toInt()])
        }
        return out
    }

    private fun levelByStrength(rel: Double): Int = when {
        rel >= 0.55 -> 1
        rel >= 0.3 -> 2
        rel >= 0.15 -> 3
        else -> 4
    }

    /**
     * Onsets that belong to a fast, evenly spaced train of attacks (repeated notes, trills,
     * runs, streams). A piano key struck again while the previous note still rings barely
     * raises the energy, so such notes look weak, but a run of equal gaps does not happen by
     * chance: vinyl crackle and other noise are irregular. Slower streams (soft repeated notes
     * between the melody notes, as in the quiet start of Rush E) come about by chance more
     * easily, so they need more notes and none missing.
     */
    private fun trainMembers(): BooleanArray {
        val out = BooleanArray(onsets.size)
        val idx = onsets.indices.filter { onsets[it].strength >= 0.04f }
        markTrains(idx, out, TRAIN_MIN_GAP, TRAIN_MAX_GAP, TRAIN_MIN_LENGTH, allowMissed = true)
        markTrains(idx, out, TRAIN_MAX_GAP, SLOW_TRAIN_MAX_GAP, SLOW_TRAIN_MIN_LENGTH, allowMissed = false)
        return out
    }

    /** Marks in [out] the onsets of [idx] that form trains whose first gap is in [minGap]..[maxGap]. */
    private fun markTrains(idx: List<Int>, out: BooleanArray, minGap: Double, maxGap: Double, minLength: Int, allowMissed: Boolean) {
        var a = 0
        while (a < idx.size - 1) {
            val first = onsetTimes[idx[a + 1]] - onsetTimes[idx[a]]
            if (first < minGap || first > maxGap) {
                a++
                continue
            }
            var b = a + 1
            var slots = 1
            var gap = first
            while (b + 1 < idx.size) {
                val d = onsetTimes[idx[b + 1]] - onsetTimes[idx[b]]
                val tol = max(0.012, 0.12 * gap)
                // A gap of two steps is one note the detector missed; the train goes on.
                slots += when {
                    abs(d - gap) <= tol -> 1
                    allowMissed && abs(d - 2 * gap) <= 1.5 * tol -> 2
                    else -> break
                }
                b++
                gap = (onsetTimes[idx[b]] - onsetTimes[idx[a]]) / slots
            }
            val steps = b - a
            // Too many missed notes and it is more likely chance than a real train.
            if (steps + 1 >= minLength && 2 * (slots - steps) <= steps) {
                for (k in a..b) out[idx[k]] = true
                a = b
            } else {
                a++
            }
        }
    }

    /** Onset strengths with the weak members of each train raised towards its strong ones. */
    private fun trainStrengths(inTrain: BooleanArray): FloatArray {
        val out = FloatArray(onsets.size) { onsets[it].strength }
        var a = 0
        while (a < onsets.size) {
            if (!inTrain[a]) {
                a++
                continue
            }
            var b = a
            while (b + 1 < onsets.size && (inTrain[b + 1] || onsets[b + 1].strength < 0.04f)) b++
            val members = (a..b).filter { inTrain[it] }
            val sorted = members.map { onsets[it].strength }.sorted()
            val strong = sorted[sorted.size * 3 / 4]
            for (k in members) out[k] = max(out[k], TRAIN_SHARE * strong)
            a = b + 1
        }
        return out
    }

    private fun buildCandidates(): List<Candidate> {
        val inTrain = trainMembers()
        val strength = trainStrengths(inTrain)
        val strengthRef = localReference(onsetTimes, DoubleArray(onsets.size) { strength[it].toDouble() }, 5.0, 0.25)
        // Nearest grid point of every onset; only the strongest onset near a grid point snaps to it.
        val nearest = IntArray(onsets.size) { -1 }
        val owner = HashMap<Int, Int>()
        for (i in onsets.indices) {
            val o = onsets[i]
            if (o.strength < 0.04f || o.time > durationSec - 0.05) continue
            val gi = nearestIndex(gridTimes, o.time)
            if (gi < 0 || abs(gridTimes[gi] - o.time) > snapTol(gridPoints[gi])) continue
            nearest[i] = gi
            val prev = owner[gi]
            if (prev == null || strength[prev] < strength[i]) owner[gi] = i
        }
        val out = ArrayList<Candidate>()
        var lastTime = -1.0
        for (i in onsets.indices) {
            val o = onsets[i]
            if (o.strength < 0.04f || o.time > durationSec - 0.05) continue
            val gi = nearest[i]
            val snapped = gi >= 0 && owner[gi] == i
            if (!snapped && o.time - lastTime < 0.06) continue
            val beat = grid.indexAt(o.time)
            val level = when {
                !meter.regularAt(beat) -> levelByStrength(strength[i] / strengthRef[i])
                snapped -> gridPoints[gi].level
                else -> 4
            }
            out.add(
                Candidate(
                    time = o.time,
                    level = level,
                    onset = o,
                    onsetScore = strength[i].toDouble(),
                    bar = barIndexAt(o.time),
                    barPos = if (snapped && meter.regularAt(beat)) gridPoints[gi].barPos else -1,
                    offGrid = !snapped && meter.regularAt(beat),
                    filler = false,
                    train = inTrain[i],
                ),
            )
            lastTime = o.time
        }
        // Melody notes: strengthen the attack they start on, or add their own candidate.
        val candTimes = DoubleArray(out.size) { out[it].time }
        val extra = ArrayList<Candidate>()
        for (m in melody) {
            if (m.start > durationSec - 0.05) continue
            val score = 0.8 * m.confidence
            // Pitch analysis uses long windows, so a note can be found a little before its attack.
            val ci = attackFor(candTimes, m.start)
            if (ci >= 0) {
                out[ci].melScore = max(out[ci].melScore, score)
                continue
            }
            val gi = nearestIndex(gridTimes, m.start)
            val beat = grid.indexAt(m.start)
            val onGrid = gi >= 0 && abs(gridTimes[gi] - m.start) <= snapTol(gridPoints[gi]) && meter.regularAt(beat)
            extra.add(
                Candidate(
                    time = m.start,
                    level = if (onGrid) gridPoints[gi].level else 4,
                    onset = null,
                    onsetScore = 0.0,
                    bar = barIndexAt(m.start),
                    barPos = if (onGrid) gridPoints[gi].barPos else -1,
                    offGrid = !onGrid,
                    filler = false,
                ).also { it.melScore = score },
            )
        }
        out.addAll(extra)
        out.sortBy { it.time }
        // A steady pulse on beats of loud passages that have no detected attack.
        val times = DoubleArray(out.size) { out[it].time }
        val fillers = ArrayList<Candidate>()
        for (g in gridPoints) {
            if (g.level > 1 || !meter.regularAt(g.beat) || energy.at(g.time) <= 0.3f) continue
            val ci = nearestIndex(times, g.time)
            if (ci >= 0 && abs(times[ci] - g.time) <= max(snapTol(g), 0.06)) continue
            fillers.add(Candidate(g.time, g.level, null, 0.0, barIndexAt(g.time), g.barPos, false, true))
        }
        out.addAll(fillers)
        out.sortBy { it.time }
        for (c in out) {
            val a = c.onsetScore
            val b = c.melScore
            c.score = if (c.filler) 0.06 else (max(a, b) + 0.25 * min(a, b)) * LEVEL_WEIGHT[c.level]
        }
        smoothAcrossBars(out)
        val ref = localReference(DoubleArray(out.size) { out[it].time }, DoubleArray(out.size) { out[it].score }, 5.0, 0.25)
        for (i in out.indices) out[i].rel = out[i].score / ref[i]
        return out
    }

    /** Index of the attack a melody note starting at [t] belongs to (up to 50 ms before, 90 ms after), or -1. */
    private fun attackFor(times: DoubleArray, t: Double): Int {
        var i = nearestIndex(times, t - 0.05)
        if (i < 0) return -1
        var best = -1
        var bestD = Double.MAX_VALUE
        while (i < times.size && times[i] <= t + 0.09) {
            val d = times[i] - t
            if (d >= -0.05) {
                // Attacks after the detected start are the usual case, so they count a bit closer.
                val cost = if (d >= 0) d * 0.6 else -d
                if (cost < bestD) {
                    bestD = cost
                    best = i
                }
            }
            i++
        }
        return best
    }

    /**
     * Repeated bars should get the same rhythm: each on-grid candidate's score is blended with
     * the scores at the same bar position in neighbouring bars of similar loudness.
     */
    private fun smoothAcrossBars(cands: List<Candidate>) {
        val nBars = barBounds.size - 1
        val barInt = FloatArray(nBars) { energy.mean(barBounds[it], min(barBounds[it + 1], durationSec)) }
        val byKey = HashMap<Long, Double>()
        for (c in cands) {
            if (c.barPos < 0 || c.filler) continue
            val k = c.bar.toLong() * 64 + c.barPos
            byKey[k] = max(byKey[k] ?: 0.0, c.score)
        }
        val smoothed = DoubleArray(cands.size)
        for ((i, c) in cands.withIndex()) {
            smoothed[i] = c.score
            if (c.barPos < 0 || c.filler) continue
            var sum = 0.0
            var n = 0
            for (db in NEIGHBOUR_BARS) {
                val b = c.bar + db
                if (b < 0 || b >= nBars || abs(barInt[b] - barInt[c.bar]) > 0.25f) continue
                sum += byKey[b.toLong() * 64 + c.barPos] ?: 0.0
                n++
            }
            if (n > 0) smoothed[i] = 0.65 * c.score + 0.35 * (sum / n)
        }
        for (i in cands.indices) cands[i].score = smoothed[i]
    }

    private fun eligible(c: Candidate, p: DifficultyParams): Boolean {
        if (c.filler) return c.level <= min(p.maxLevel, 1)
        if (c.score < MIN_SCORE || c.rel < p.minRel) return false
        // Train notes fall between the grid lines of fast passages but are real notes.
        if (c.offGrid || c.level > p.maxLevel) return (c.rel >= p.offGridRel || (c.train && p.playsTrains)) && c.level <= 4
        return true
    }

    private fun select(p: DifficultyParams): List<Candidate> {
        val chosen = TreeMap<Double, Candidate>()
        for ((bar, list) in candidatesByBar) {
            val t0 = barBounds[bar]
            val t1 = min(barBounds[bar + 1], durationSec)
            val barLen = max(0.1, t1 - t0)
            val inten = energy.mean(t0, t1).toDouble()
            val nps = p.npsLow + (p.npsHigh - p.npsLow) * inten.pow(0.8)
            val pool = list.filter { eligible(it, p) }
            val real = pool.count { !it.filler }
            val trains = pool.count { it.train }
            var budget = min((barLen * nps + p.trainShare * trains).roundToInt(), max(real, if (inten > 0.25) 1 else 0))
            for (c in pool.sortedByDescending { it.score }) {
                if (budget <= 0) break
                val lo = chosen.floorEntry(c.time)?.value
                val hi = chosen.ceilingEntry(c.time)?.value
                if (lo != null && c.time - lo.time < gapBetween(lo, c, p)) continue
                if (hi != null && hi.time - c.time < gapBetween(c, hi, p)) continue
                chosen[c.time] = c
                budget--
            }
        }
        return chosen.values.toList()
    }

    /** Fast trains may be closer together than other tiles: their notes really are that fast. */
    private fun gapBetween(a: Candidate, b: Candidate, p: DifficultyParams): Double =
        if (a.train && b.train && p.playsTrains) p.trainGap else p.minGap

    private fun pitchAt(t: Double): Float {
        if (melody.isEmpty()) return Float.NaN
        var lo = 0
        var hi = melody.size - 1
        if (melodyStarts[0] > t + 0.08) return Float.NaN
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (melodyStarts[mid] <= t + 0.08) lo = mid else hi = mid - 1
        }
        val m = melody[lo]
        return if (m.end > t - 0.02) m.midi.toFloat() else Float.NaN
    }

    private fun melodyNoteNear(t: Double, tol: Double): MelodyNote? {
        val i = nearestIndex(melodyStarts, t)
        if (i < 0) return null
        return melody[i].takeIf { abs(it.start - t) <= tol }
    }

    private fun snapToGrid(t: Double, maxLevel: Int, window: Double): Double {
        var best = t
        var bestD = window
        var i = max(0, nearestIndex(gridTimes, t - window))
        while (i < gridPoints.size) {
            val g = gridPoints[i]
            if (g.time > t + window) break
            if (g.level <= maxLevel && g.time >= t - window) {
                val d = abs(g.time - t)
                if (d < bestD) {
                    bestD = d
                    best = g.time
                }
            }
            i++
        }
        return best
    }

    private fun strongOnsetBetween(t0: Double, t1: Double): Boolean {
        var lo = 0
        var hi = onsetTimes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (onsetTimes[mid] <= t0) lo = mid + 1 else hi = mid
        }
        var i = lo
        while (i < onsets.size && onsetTimes[i] < t1) {
            if (onsets[i].strength > 0.35f) return true
            i++
        }
        return false
    }

    private fun makeHolds(events: List<Event>, p: DifficultyParams) {
        var activeHoldEnd = -1.0
        for (i in events.indices) {
            val e = events[i]
            if (e.time < activeHoldEnd) continue // never start a hold inside another hold
            val beatLen = grid.beatLengthAt(e.time)
            val minLen = max(p.holdMinSec, p.holdMinBeats * beatLen)
            val next = if (i + 1 < events.size) events[i + 1].time else durationSec
            var end = -1.0
            val m = melodyNoteNear(e.time, 0.07)
            if (m != null && m.duration >= minLen) end = m.end
            if (end < 0 && next - e.time >= 2 * beatLen && (e.onset?.strength ?: 0f) > 0.3f && energy.at(e.time) > 0.3f) {
                // A sound that rings on without new attacks (pads, sustained chords).
                val cand = next - beatLen
                val sustained = energy.dbAt(e.time + (cand - e.time) * 0.7) > energy.dbAt(e.time) - 6f
                if (sustained && cand - e.time >= minLen && !strongOnsetBetween(e.time + 0.08, cand)) end = cand
            }
            if (end < 0) continue
            end = min(end, e.time + 4 * beatLen)
            end = snapToGrid(end, 2, beatLen * 0.5)
            if (!p.notesDuringHold) end = min(end, next - max(p.minGap, beatLen * 0.5))
            end = min(end, durationSec - 0.05)
            if (end - e.time >= minLen * 0.9) {
                e.holdEnd = end
                activeHoldEnd = end
            }
        }
        // Keep holds special: only the longest ones survive when there are too many.
        val holds = events.filter { it.isHold }
        val maxHolds = (events.size * p.maxHoldFraction).roundToInt()
        if (holds.size > maxHolds) {
            holds.sortedByDescending { it.holdEnd - it.time }.drop(maxHolds).forEach { it.holdEnd = -1.0 }
        }
    }

    private fun insideHold(events: List<Event>, i: Int): Boolean {
        val t = events[i].time
        var j = i - 1
        while (j >= 0 && t - events[j].time < 8.0) {
            if (events[j].isHold && events[j].holdEnd > t - 0.02) return true
            j--
        }
        return false
    }

    private fun makeDoubles(events: List<Event>, p: DifficultyParams) {
        if (p.doubleFraction <= 0.0) return
        val accents = ArrayList<Pair<Int, Double>>()
        for (i in events.indices) {
            val e = events[i]
            if (e.isHold || e.level > 1) continue
            val o = e.onset ?: continue
            val prevEnd = if (i > 0) max(events[i - 1].time, events[i - 1].holdEnd) else -99.0
            val nextT = if (i + 1 < events.size) events[i + 1].time else 99999.0
            if (min(e.time - prevEnd, nextT - e.time) < 1.5 * p.minGap) continue
            if (insideHold(events, i)) continue
            val jump = energy.mean(e.time, e.time + 2.0) - energy.mean(e.time - 2.0, e.time)
            var s = o.strength * (1.0 + 0.5 * o.low) * (1.0 + 0.5 * o.high)
            if (jump > 0.2f) s *= 1.5
            if (e.level == 0) s *= 1.15
            accents.add(i to s)
        }
        val target = (events.size * p.doubleFraction).roundToInt()
        val times = TreeSet<Double>()
        var made = 0
        for ((i, _) in accents.sortedByDescending { it.second }) {
            if (made >= target) break
            val e = events[i]
            val minDist = p.doubleMinGapBeats * grid.beatLengthAt(e.time)
            val lo = times.floor(e.time)
            val hi = times.ceiling(e.time)
            if (lo != null && e.time - lo < minDist) continue
            if (hi != null && hi - e.time < minDist) continue
            e.isDouble = true
            times.add(e.time)
            made++
        }
    }

    private fun assignLanes(events: List<Event>, p: DifficultyParams, rng: Random, lanes: Int): List<Note> {
        val notes = ArrayList<Note>(events.size + events.size / 8)
        val laneFree = DoubleArray(lanes) { -1e9 }
        val holdUntil = DoubleArray(lanes) { -1e9 }
        // Fewer lanes means the same lane comes back sooner.
        val jackGap = p.jackMinGap * min(1.0, (lanes - 1) / 3.0 + 0.1)
        var lastLane = rng.nextInt(lanes)
        var lastTime = -1e9
        var lastPitch = Float.NaN
        var lastPitchTime = -1e9
        var lastDir = if (rng.nextBoolean()) 1 else -1
        val recent = ArrayDeque<Int>()
        val window = ArrayDeque<Pair<Double, Float>>()
        for (e in events) {
            val t = e.time
            val gap = t - lastTime
            val beatLen = grid.beatLengthAt(t)
            if (!e.pitch.isNaN()) window.addLast(t to e.pitch)
            while (window.isNotEmpty() && window.first().first < t - 6.0) window.removeFirst()
            var target = -1
            if (!e.pitch.isNaN() && window.size >= 3) {
                var lo = window.minOf { it.second }
                var hi = window.maxOf { it.second }
                if (hi - lo < 5f) {
                    val c = (hi + lo) / 2
                    lo = c - 2.5f
                    hi = c + 2.5f
                }
                target = (((e.pitch - lo) / (hi - lo)) * (lanes - 1)).roundToInt().coerceIn(0, lanes - 1)
            }
            var dir: Int
            var step = 1
            if (!e.pitch.isNaN() && !lastPitch.isNaN() && t - lastPitchTime < 2.5) {
                val d = e.pitch - lastPitch
                dir = if (d > 0.5f) 1 else if (d < -0.5f) -1 else 0
                if (abs(d) >= 5f && lanes >= 4) step = 2
            } else if (gap < beatLen * 1.05) {
                dir = if (rng.nextDouble() < 0.7) lastDir else -lastDir
            } else {
                dir = when (rng.nextInt(3)) {
                    0 -> -1
                    1 -> 1
                    else -> 0
                }
                if (rng.nextDouble() < 0.3 && lanes >= 4) step = 2
            }
            if (dir > 0 && lastLane == lanes - 1) dir = -1
            if (dir < 0 && lastLane == 0) dir = 1

            val lane = pickLane(t, dir, step, target, lastLane, gap, laneFree, holdUntil, recent, jackGap, rng)
            if (lane < 0) continue
            val tMs = ms(t)
            if (e.isHold) {
                notes.add(Note(tMs, lane, max(1, ms(e.holdEnd) - tMs)))
                holdUntil[lane] = e.holdEnd + 0.05
                laneFree[lane] = e.holdEnd + max(jackGap, 0.12)
            } else {
                notes.add(Note(tMs, lane, 0))
                laneFree[lane] = t + jackGap
            }
            if (e.isDouble) {
                val second = pickSecond(t, lane, laneFree, holdUntil, recent, rng)
                if (second >= 0) {
                    notes.add(Note(tMs, second, 0))
                    laneFree[second] = t + jackGap
                }
            }
            if (lane != lastLane) lastDir = if (lane > lastLane) 1 else -1
            recent.addLast(lane)
            if (recent.size > lanes - 1) recent.removeFirst()
            lastLane = lane
            lastTime = t
            if (!e.pitch.isNaN()) {
                lastPitch = e.pitch
                lastPitchTime = t
            }
        }
        notes.sortWith(compareBy<Note> { it.t }.thenBy { it.lane })
        return notes
    }

    private fun pickLane(
        t: Double,
        dir: Int,
        step: Int,
        target: Int,
        lastLane: Int,
        gap: Double,
        laneFree: DoubleArray,
        holdUntil: DoubleArray,
        recent: ArrayDeque<Int>,
        jackGap: Double,
        rng: Random,
    ): Int {
        var best = -1
        var bestScore = Double.NEGATIVE_INFINITY
        for (lane in laneFree.indices) {
            if (holdUntil[lane] > t - 0.02) continue
            var s = 0.0
            if (laneFree[lane] > t + 1e-6) s -= 100.0
            val delta = lane - lastLane
            s += when {
                dir > 0 -> if (delta > 0) 2.0 else if (delta == 0) -1.0 else -2.0
                dir < 0 -> if (delta < 0) 2.0 else if (delta == 0) -1.0 else -2.0
                else -> if (delta == 0) (if (gap >= jackGap * 1.5) 1.5 else -0.5) else if (abs(delta) == 1) 0.8 else 0.0
            }
            if (dir != 0) s -= 0.5 * abs(abs(delta) - step)
            if (target >= 0) s -= 0.6 * abs(lane - target)
            s -= 0.25 * recent.count { it == lane }
            s += rng.nextDouble() * 0.3
            if (s > bestScore) {
                bestScore = s
                best = lane
            }
        }
        return best
    }

    private fun pickSecond(
        t: Double,
        first: Int,
        laneFree: DoubleArray,
        holdUntil: DoubleArray,
        recent: ArrayDeque<Int>,
        rng: Random,
    ): Int {
        var best = -1
        var bestScore = Double.NEGATIVE_INFINITY
        for (lane in laneFree.indices) {
            if (lane == first || holdUntil[lane] > t - 0.02) continue
            var s = abs(lane - first).toDouble()
            if (laneFree[lane] > t + 1e-6) s -= 100.0
            if (lane !in recent) s += 0.5
            s += rng.nextDouble() * 0.2
            if (s > bestScore) {
                bestScore = s
                best = lane
            }
        }
        return best
    }

    private fun computeLevel(notes: List<Note>, lanes: Int): Int {
        if (notes.size < 2) return 1
        val distinct = notes.map { it.t }.distinct()
        val active = max(10.0, (distinct.last() - distinct.first()) / 1000.0)
        val nps = notes.size / active
        var peak = 0.0
        var j = 0
        for (i in notes.indices) {
            while (notes[i].t - notes[j].t > 5000) j++
            peak = max(peak, (i - j + 1) / 5.0)
        }
        // Short bursts and long fast streams are what make a chart hard, beyond its average.
        var burst = 0.0
        j = 0
        for (i in distinct.indices) {
            while (distinct[i] - distinct[j] > 2000) j++
            burst = max(burst, (i - j + 1) / 2.0)
        }
        var stream = 1
        var run = 1
        for (i in 1 until distinct.size) {
            run = if (distinct[i] - distinct[i - 1] <= 110) run + 1 else 1
            stream = max(stream, run)
        }
        val doubles = notes.size - distinct.size
        val holds = notes.count { it.d > 0 }
        val raw = 1.25 * nps + 0.45 * peak + 6.0 * doubles / notes.size + 2.0 * holds / notes.size +
            0.3 * max(0.0, burst - 8.0) + 0.03 * max(0, min(stream, 40) - 4)
        // Fewer lanes are easier to read and reach.
        val laneFactor = if (lanes <= 3) 0.9 else 1.0
        return (raw * 1.1 * laneFactor).roundToInt().coerceIn(1, 15)
    }

    private fun ms(t: Double): Int = (t * 1000.0).roundToInt()

    companion object {
        private val LEVEL_WEIGHT = doubleArrayOf(1.25, 1.12, 1.0, 0.85, 0.72)
        private val NEIGHBOUR_BARS = intArrayOf(-2, -1, 1, 2)
        private const val MIN_SCORE = 0.045
        /** Evenly spaced trains: gap range (seconds), minimum number of attacks and the strength weak members get. */
        private const val TRAIN_MIN_GAP = 0.075
        private const val TRAIN_MAX_GAP = 0.15
        private const val TRAIN_MIN_LENGTH = 5
        private const val SLOW_TRAIN_MAX_GAP = 0.25
        private const val SLOW_TRAIN_MIN_LENGTH = 8
        private const val TRAIN_SHARE = 0.6f
    }
}
