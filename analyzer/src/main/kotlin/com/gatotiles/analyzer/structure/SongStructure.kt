package com.gatotiles.analyzer.structure

import com.gatotiles.analyzer.features.EnergyProfile
import com.gatotiles.analyzer.features.Features
import com.gatotiles.analyzer.features.SalienceCalculator
import com.gatotiles.analyzer.model.Section
import com.gatotiles.analyzer.rhythm.BeatGrid
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Splits a song into parts (verse, chorus, bridge...) and gives parts that sound alike the
 * same label, so the game can change colors where the music changes.
 *
 * Beat-synchronous chroma + timbre vectors build a self-similarity matrix; a Gaussian
 * checkerboard kernel slid along its diagonal gives a novelty curve (Foote), whose peaks
 * snapped to bar lines are the boundaries. Parts are then grouped by how well they match
 * along the diagonal (same chords in the same order) or as a whole (same sound).
 */
object SongStructure {
    /** Shortest part, in seconds. */
    private const val MIN_SECTION_SEC = 7.5
    /** Half width of the novelty kernel, in seconds. */
    private const val KERNEL_SEC = 7.0
    /** Two parts with at least this similarity get the same label. */
    private const val SAME_LABEL = 0.55f
    private const val MAX_LABELS = 8

    class Result(
        val sections: List<Section>,
        /** Novelty per beat (0..1), for debugging. */
        val novelty: FloatArray,
        val beatTimes: DoubleArray,
        /** Similarity between parts (for debugging). */
        val partSim: Array<FloatArray> = emptyArray(),
    )

    fun detect(f: Features, grid: BeatGrid, energy: EnergyProfile): Result {
        val dur = f.durationSec
        val whole = Result(listOf(Section(0, 0, (energy.mean(0.0, dur) * 100).roundToInt())), FloatArray(0), DoubleArray(0))
        // Beat intervals inside the audio; very fast grids are merged in pairs.
        var beats = grid.beats.filter { it >= 0 && it < dur }.toDoubleArray()
        if (beats.size < 2) return whole
        if ((beats.last() - beats.first()) / (beats.size - 1) < 0.3) beats = beats.filterIndexed { i, _ -> i % 2 == 0 }.toDoubleArray()
        val n = beats.size - 1
        val beatLen = (beats.last() - beats.first()) / n
        if (n < 24 || dur < 40) return whole

        val vec = beatVectors(f, energy, beats)
        val sim = similarity(vec)
        val half = (KERNEL_SEC / beatLen).roundToInt().coerceIn(6, 32)
        val nov = novelty(sim, n, half)

        // Peaks of the novelty curve, strongest first, kept apart by the minimum length.
        val minGap = max(4, (MIN_SECTION_SEC / beatLen).roundToInt())
        val sorted = nov.copyOf().sorted()
        val med = sorted[n / 2]
        val hi = sorted[(n * 0.95).toInt().coerceAtMost(n - 1)]
        val thr = med + 0.35f * (hi - med)
        val peaks = ArrayList<Int>()
        for (i in 1 until n - 1) {
            if (nov[i] < thr) continue
            var isMax = true
            for (k in max(0, i - half / 2)..min(n - 1, i + half / 2)) if (nov[k] > nov[i]) isMax = false
            if (isMax) peaks.add(i)
        }
        peaks.sortByDescending { nov[it] }
        val maxSections = max(2, (dur / 14).toInt())
        val chosen = ArrayList<Int>()
        for (p in peaks) {
            val b = snapToBar(p, nov, grid, beats)
            if (b < minGap || n - b < minGap) continue
            if (chosen.any { kotlin.math.abs(it - b) < minGap }) continue
            chosen.add(b)
            if (chosen.size >= maxSections - 1) break
        }
        chosen.sort()
        val bounds = listOf(0) + chosen + listOf(n)

        val m = bounds.size - 1
        val partSim = Array(m) { a -> FloatArray(m) { b -> if (a == b) 1f else partSimilarity(bounds[a], bounds[a + 1], bounds[b], bounds[b + 1], sim) } }
        val labels = label(partSim)
        val sections = ArrayList<Section>()
        for (s in 0 until bounds.size - 1) {
            val t0 = if (s == 0) 0.0 else beats[bounds[s]]
            val t1 = if (s == bounds.size - 2) dur else beats[bounds[s + 1]]
            val e = (energy.mean(t0, t1) * 100).roundToInt().coerceIn(0, 100)
            sections.add(Section((t0 * 1000).roundToInt(), labels[s], e))
        }
        return Result(sections, nov, beats, partSim)
    }

    /** Moves a boundary to a nearby bar line when the novelty there is almost as high. */
    private fun snapToBar(i: Int, nov: FloatArray, grid: BeatGrid, beats: DoubleArray): Int {
        var best = i
        for (d in -2..2) {
            val k = i + d
            if (k < 0 || k >= nov.size) continue
            val gi = grid.indexAt(beats[k] + 1e-3)
            if (grid.isDownbeat(gi) && kotlin.math.abs(grid.beats[gi] - beats[k]) < 0.02 && nov[k] >= nov[i] * 0.8f) {
                if (best == i || kotlin.math.abs(d) < kotlin.math.abs(best - i)) best = k
            }
        }
        return best
    }

    /**
     * One vector per beat: 12-bin chroma (unit length) followed by z-scored timbre and
     * loudness, weighted so harmony and sound count about the same.
     */
    private fun beatVectors(f: Features, energy: EnergyProfile, beats: DoubleArray): Array<FloatArray> {
        val n = beats.size - 1
        val td = Features.TIMBRE_DIM
        val dim = 12 + td + 1
        val pcOf = IntArray(SalienceCalculator.N_BINS) { b ->
            Math.floorMod(SalienceCalculator.binToMidi(b.toFloat()).roundToInt(), 12)
        }
        val out = Array(n) { FloatArray(dim) }
        for (i in 0 until n) {
            val v = out[i]
            val fa = max(0, f.timeToFrame(beats[i]).toInt())
            val fb = min(f.nFrames, max(fa + 1, f.timeToFrame(beats[i + 1]).toInt()))
            // Chroma from pitch salience.
            val pa = fa / Features.PITCH_STRIDE
            val pb = min(f.salience.size, max(pa + 1, fb / Features.PITCH_STRIDE))
            for (p in pa until pb) {
                val s = f.salience[p]
                for (b in s.indices) v[pcOf[b]] += s[b]
            }
            var norm = 0f
            for (c in 0 until 12) norm += v[c] * v[c]
            norm = sqrt(norm)
            if (norm > 1e-6f) for (c in 0 until 12) v[c] /= norm
            // Timbre.
            val cnt = max(1, fb - fa)
            for (t in fa until fb) for (k in 0 until td) v[12 + k] += f.timbre[t * td + k]
            for (k in 0 until td) v[12 + k] /= cnt
            v[12 + td] = energy.dbAt((beats[i] + beats[i + 1]) / 2)
        }
        // z-score the non-chroma dimensions over the song.
        for (k in 12 until dim) {
            var m = 0.0
            for (i in 0 until n) m += out[i][k]
            m /= n
            var s = 0.0
            for (i in 0 until n) s += (out[i][k] - m) * (out[i][k] - m)
            val sd = sqrt(s / n).coerceAtLeast(1e-6)
            // Timbre gets 12 dims, chroma 12 unit-length dims: scale timbre down so neither dominates.
            val w = if (k < 12 + td) 0.22 else 0.45
            for (i in 0 until n) out[i][k] = ((out[i][k] - m) / sd * w).toFloat()
        }
        return out
    }

    /** Gaussian self-similarity, width set by the median distance between beats. */
    private fun similarity(v: Array<FloatArray>): Array<FloatArray> {
        val n = v.size
        val d2 = Array(n) { FloatArray(n) }
        for (i in 0 until n) for (j in i until n) {
            var s = 0f
            val a = v[i]
            val b = v[j]
            for (k in a.indices) {
                val d = a[k] - b[k]
                s += d * d
            }
            d2[i][j] = s
            d2[j][i] = s
        }
        val sample = FloatArray(min(n * n, 40_000))
        var c = 0
        val step = max(1, n * n / sample.size)
        var idx = 0
        while (idx < n * n && c < sample.size) {
            sample[c++] = d2[idx / n][idx % n]
            idx += step
        }
        val sorted = sample.copyOf(c).sorted()
        val sigma2 = max(1e-6f, sorted[c / 2])
        for (i in 0 until n) for (j in 0 until n) d2[i][j] = exp(-d2[i][j] / sigma2)
        return d2
    }

    private fun novelty(s: Array<FloatArray>, n: Int, half: Int): FloatArray {
        val g = FloatArray(half) { val x = (it + 0.5) / half; exp(-4.0 * x * x).toFloat() }
        val out = FloatArray(n)
        for (i in 0 until n) {
            var acc = 0f
            var wsum = 0f
            for (a in -half until half) {
                val ia = i + a
                if (ia < 0 || ia >= n) continue
                val ga = g[if (a < 0) -a - 1 else a]
                for (b in -half until half) {
                    val ib = i + b
                    if (ib < 0 || ib >= n) continue
                    val w = ga * g[if (b < 0) -b - 1 else b]
                    val sign = if ((a < 0) == (b < 0)) 1f else -1f
                    acc += sign * w * s[ia][ib]
                    wsum += w
                }
            }
            out[i] = if (wsum > 0f) max(0f, acc / wsum) else 0f
        }
        // Both halves must exist for a boundary: fade the edges.
        for (i in 0 until n) {
            val edge = min(i, n - 1 - i).toFloat() / half
            if (edge < 1f) out[i] *= edge
        }
        val m = out.maxOrNull() ?: 0f
        if (m > 0f) for (i in 0 until n) out[i] /= m
        return out
    }

    /**
     * Labels parts in order: each part takes the label of the most similar earlier part when
     * they match well enough, otherwise a new label.
     */
    private fun label(sim: Array<FloatArray>): IntArray {
        val m = sim.size
        // "Alike" is relative to the song: the closest fifth of the pairs, and never below a floor.
        val off = ArrayList<Float>()
        for (a in 0 until m) for (b in a + 1 until m) off.add(sim[a][b])
        off.sort()
        val thr = if (off.isEmpty()) 1f else max(SAME_LABEL, off[((off.size - 1) * 0.8).toInt()])
        val labels = IntArray(m) { -1 }
        var next = 0
        for (a in 0 until m) {
            var best = -1
            var bestSim = thr
            for (b in 0 until a) {
                if (sim[a][b] >= bestSim) {
                    bestSim = sim[a][b]
                    best = b
                }
            }
            labels[a] = if (best >= 0) labels[best] else next++
        }
        // Too many different labels: join the two labels with the most similar pair of parts.
        while (next > MAX_LABELS) {
            var bi = 0
            var bj = 1
            var bv = -1f
            for (a in 0 until m) for (b in 0 until m) {
                if (labels[a] < labels[b] && sim[a][b] > bv) {
                    bv = sim[a][b]
                    bi = labels[a]
                    bj = labels[b]
                }
            }
            for (a in 0 until m) {
                if (labels[a] == bj) labels[a] = bi else if (labels[a] > bj) labels[a]--
            }
            next--
        }
        return labels
    }

    /** Best of: alignment along the diagonal (same sequence) and the average of the block (same sound). */
    private fun partSimilarity(a0: Int, a1: Int, b0: Int, b1: Int, s: Array<FloatArray>): Float {
        val la = a1 - a0
        val lb = b1 - b0
        val len = min(la, lb)
        var diag = 0f
        if (len >= 4) {
            for (shift in -2..2) {
                var acc = 0f
                var cnt = 0
                for (t in 0 until len) {
                    val i = a0 + t
                    val j = b0 + t + shift
                    if (j < b0 || j >= b1) continue
                    acc += s[i][j]
                    cnt++
                }
                if (cnt > len / 2) diag = max(diag, acc / cnt)
            }
        }
        var block = 0f
        for (i in a0 until a1) for (j in b0 until b1) block += s[i][j]
        block /= max(1, la * lb)
        // A block average is diluted by variation inside each part; scale it up a little.
        return max(diag, min(1f, block * 1.15f))
    }
}
