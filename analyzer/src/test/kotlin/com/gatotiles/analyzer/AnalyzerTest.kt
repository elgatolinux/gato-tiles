package com.gatotiles.analyzer

import com.gatotiles.analyzer.dsp.RealFft
import com.gatotiles.analyzer.model.Difficulty
import com.gatotiles.analyzer.model.LANE_OPTIONS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class AnalyzerTest {

    @Test
    fun fftMatchesNaiveDft() {
        val n = 64
        val x = FloatArray(n) { (sin(it * 0.3) + 0.5 * cos(it * 1.7) + it % 5 * 0.1).toFloat() }
        val fft = RealFft(n)
        val mag = FloatArray(n / 2 + 1)
        fft.magnitude(x, mag)
        for (k in 0..n / 2) {
            var re = 0.0
            var im = 0.0
            for (i in 0 until n) {
                re += x[i] * cos(2 * PI * k * i / n)
                im -= x[i] * sin(2 * PI * k * i / n)
            }
            assertEquals("bin $k", sqrt(re * re + im * im), mag[k].toDouble(), 1e-3)
        }
    }

    private fun drumTrack(bpm: Double, seconds: Double, sr: Int = 44100, offset: Double = 0.5): Pair<Synth, List<Double>> {
        val s = Synth(sr, seconds)
        val beat = 60.0 / bpm
        val beats = ArrayList<Double>()
        var t = offset
        var i = 0
        while (t < seconds - 0.5) {
            beats.add(t)
            if (i % 2 == 0) s.kick(t) else s.snare(t)
            s.hat(t)
            s.hat(t + beat / 2)
            t += beat
            i++
        }
        return s to beats
    }

    @Test
    fun onsetTimingIsAccurate() {
        val s = Synth(44100, 20.0)
        val truth = ArrayList<Double>()
        var t = 0.73
        while (t < 19.0) {
            s.click(t)
            truth.add(t)
            t += 0.61
        }
        val r = s.analyze()
        val errs = truth.map { tt -> r.onsets.minBy { abs(it.time - tt) }.time - tt }
        val med = errs.sorted()[errs.size / 2]
        println("click onset error median=%.4f max=%.4f n=%d detected=%d".format(med, errs.maxOf { abs(it) }, truth.size, r.onsets.size))
        assertTrue("median onset error ${med}s", abs(med) < 0.012)
        assertTrue(errs.all { abs(it) < 0.025 })
    }

    @Test
    fun tracksTempoAndBeats() {
        for (bpm in listOf(120.0, 97.0, 140.0, 174.0)) {
            val (s, beats) = drumTrack(bpm, 40.0)
            val r = s.analyze()
            val grid = r.grid
            val ratio = grid.bpm / bpm
            println("bpm=%.1f detected=%.2f sub=%d phase=%d".format(bpm, grid.bpm, grid.subdivision, grid.downbeatPhase))
            assertTrue("tempo $bpm vs ${grid.bpm}", abs(ratio - 1) < 0.01 || abs(ratio - 2) < 0.02 || abs(ratio - 0.5) < 0.01)
            // Grid beats must sit on true beats (or true beats on grid beats when the
            // tracker picked the double tempo), within 15 ms, inside the tracked range.
            val tracked = grid.beats.copyOfRange(grid.trackedFrom, grid.trackedTo + 1)
            val (probe, ref) = if (ratio < 0.75) tracked.toList() to beats else beats to tracked.toList()
            val errs = probe.filter { it > ref.first() - 0.01 && it < ref.last() + 0.01 }
                .map { b -> ref.minOf { abs(it - b) } }
            val worst = errs.maxOrNull() ?: 1.0
            println("   beat errors: n=%d mean=%.4f worst=%.4f".format(errs.size, errs.average(), worst))
            assertTrue("beats off by $worst at bpm $bpm", worst < 0.015)
            assertTrue("tracked most of the song", (tracked.last() - tracked.first()) > (beats.last() - beats.first()) * 0.8)
        }
    }

    @Test
    fun detectsMelodyNotes() {
        val (s, _) = drumTrack(110.0, 30.0)
        val beat = 60.0 / 110.0
        val pitches = listOf(60, 62, 64, 65, 67, 69, 67, 65, 64, 62, 60, 64, 67, 72, 67, 64)
        val truth = ArrayList<Triple<Double, Double, Int>>()
        var t = 0.5 + 4 * beat
        var i = 0
        while (t < 28.0) {
            val p = pitches[i % pitches.size]
            val dur = if (i % 4 == 3) beat * 1.8 else beat * 0.9
            s.tone(t, dur, p, amp = 0.35f)
            truth.add(Triple(t, dur, p))
            t += if (i % 4 == 3) beat * 2 else beat
            i++
        }
        val r = s.analyze()
        var pitchOk = 0
        var startErr = 0.0
        var matched = 0
        for ((st, _, p) in truth) {
            val m = r.melody.minByOrNull { abs(it.start - st) } ?: continue
            if (abs(m.start - st) < 0.06) {
                matched++
                startErr += abs(m.start - st)
                if (m.midi == p || m.midi == p + 12 || m.midi == p - 12) pitchOk++
            }
        }
        println("melody: truth=%d detected=%d matched=%d pitchOk=%d meanStartErr=%.4f".format(truth.size, r.melody.size, matched, pitchOk, startErr / maxOf(1, matched)))
        assertTrue("matched $matched/${truth.size}", matched >= truth.size * 0.8)
        assertTrue("pitch ok $pitchOk/$matched", pitchOk >= matched * 0.85)
    }

    @Test
    fun chartsArePlayable() {
        val (s, beats) = drumTrack(128.0, 60.0)
        val beat = 60.0 / 128.0
        var t = beats[8]
        var k = 0
        while (t < 58.0) {
            s.tone(t, beat * (if (k % 3 == 0) 1.9 else 0.45), 60 + (k * 5) % 12, 0.3f)
            t += if (k % 3 == 0) beat * 2 else beat * 0.5
            k++
        }
        val r = s.analyze()
        for (lanes in LANE_OPTIONS) {
            var lastLevel = 0
            var lastCount = 0
            for (d in Difficulty.entries) {
                val c = r.chart.chart(d, lanes)
                assertEquals(lanes, c.lanes)
                val notes = c.notes
                println("%d lanes %s level=%d notes=%d holds=%d doubles=%d".format(lanes, d, c.level, notes.size, notes.count { it.d > 0 }, notes.size - notes.map { it.t }.distinct().size))
                assertTrue("$d has notes", notes.size > 20)
                assertTrue(notes.zipWithNext().all { (a, b) -> a.t <= b.t })
                assertTrue(notes.all { it.lane in 0 until lanes && it.t >= 0 && it.d >= 0 })
                // No two tiles overlap in the same lane (including hold bodies).
                for (lane in 0 until lanes) {
                    val inLane = notes.filter { it.lane == lane }
                    for ((a, b) in inLane.zipWithNext()) {
                        assertTrue("$d lane $lane overlap at ${a.t}/${b.t}", b.t > a.t + a.d)
                    }
                }
                // At most two simultaneous tiles.
                assertTrue(notes.groupBy { it.t }.values.all { it.size <= 2 })
                assertTrue("level grows with difficulty", c.level >= lastLevel)
                assertTrue("more tiles with difficulty", notes.size >= lastCount)
                lastLevel = c.level
                lastCount = notes.size
            }
        }
    }

    /**
     * Swing / shuffle: off-beat attacks sit at 2/3 of the beat. Tiles must land on the real
     * attacks instead of being pulled to straight sixteenths, and hard charts must use them.
     */
    @Test
    fun swingKeepsRealTiming() {
        val bpm = 100.0
        val beat = 60.0 / bpm
        val s = Synth(44100, 40.0)
        val swung = ArrayList<Double>()
        var t = 0.5
        var i = 0
        while (t < 39.0) {
            if (i % 2 == 0) s.kick(t) else s.snare(t)
            s.hat(t)
            s.tone(t + beat * 2 / 3, beat * 0.25, 64 + (i % 3) * 3, 0.3f)
            swung.add(t + beat * 2 / 3)
            t += beat
            i++
        }
        val r = s.analyze()
        val notes = r.chart.chart(Difficulty.EXPERT).notes
        val times = notes.map { it.t / 1000.0 }.distinct()
        // Every tile sits on a real attack (beat or swung off-beat), within 20 ms.
        val beats = generateSequence(0.5) { it + beat }.takeWhile { it < 39.0 }.toList()
        val attacks = (beats + swung).sorted()
        val off = times.map { tt -> attacks.minOf { abs(it - tt) } }
        println("swing: tiles=%d worst=%.4f mean=%.4f".format(times.size, off.max(), off.average()))
        for ((k, tt) in times.withIndex()) if (off[k] > 0.015) {
            val ph = ((tt - 0.5) / beat) % 1.0
            val on = r.onsets.minByOrNull { abs(it.time - tt) }
            val mel = r.melody.minByOrNull { abs(it.start - tt) }
            println("   off tile %.3f phase %.3f off %.3f nearest onset %.3f (%.2f) melody %.3f".format(tt, ph, off[k], on?.time, on?.strength, mel?.start))
        }
        assertTrue("tiles off the real attacks: worst ${off.max()}", off.max() < 0.02)
        val inside = swung.filter { it > 3.0 && it < 37.0 }
        val covered = inside.count { sw -> times.any { abs(it - sw) < 0.02 } }
        assertTrue("swung notes covered $covered/${inside.size}", covered >= inside.size * 0.8)
        val hard = r.chart.chart(Difficulty.HARD).notes.map { it.t / 1000.0 }
        val hardCovered = inside.count { sw -> hard.any { abs(it - sw) < 0.02 } }
        assertTrue("hard swung notes $hardCovered/${inside.size}", hardCovered >= inside.size * 0.5)
    }

    /**
     * Fast repeated notes (Rush E style): every strong note is followed ~96 ms later by a
     * quieter re-strike of the same key. The re-strikes barely raise the energy, but they form
     * an even train, so Expert and Hard must play them; Normal keeps its own minimum gap.
     */
    @Test
    fun fastRepeatedNotesBecomeTiles() {
        val s = Synth(44100, 28.0, seed = 3)
        val strong = ArrayList<Double>()
        val weak = ArrayList<Double>()
        var t = 1.0
        var k = 0
        while (t < 26.0) {
            s.kick(t, 0.5f)
            s.tone(t, 0.19, 52 + (k % 4) * 2, 0.25f)
            s.tone(t, 0.09, 76, 0.22f)
            strong.add(t)
            s.tone(t + 0.096, 0.09, 76, 0.12f)
            weak.add(t + 0.096)
            t += 0.192
            k++
        }
        val r = s.analyze()
        val attacks = (strong + weak).sorted()
        val expert = r.chart.chart(Difficulty.EXPERT).notes.map { it.t / 1000.0 }.distinct()
        val inside = weak.filter { it > 3.0 && it < 24.0 }
        // Re-strikes the onset detector heard: the generator must turn (almost) all of them into tiles.
        val heard = inside.filter { w -> r.onsets.any { abs(it.time - w) < 0.025 } }
        val covered = heard.count { w -> expert.any { abs(it - w) < 0.025 } }
        val worst = expert.maxOf { tt -> attacks.minOf { abs(it - tt) } }
        println("fast notes: expert tiles=%d weak heard %d/%d covered %d worst off %.4f".format(expert.size, heard.size, inside.size, covered, worst))
        assertTrue("weak re-strikes heard ${heard.size}/${inside.size}", heard.size >= inside.size / 2)
        assertTrue("weak re-strikes covered $covered/${heard.size}", covered >= heard.size * 0.9)
        assertTrue("tiles off the real attacks: worst $worst", worst < 0.025)
        val hard = r.chart.chart(Difficulty.HARD).notes.map { it.t / 1000.0 }.distinct()
        val hardCovered = heard.count { w -> hard.any { abs(it - w) < 0.025 } }
        println("fast notes: hard tiles=%d covered %d/%d".format(hard.size, hardCovered, heard.size))
        assertTrue("hard re-strikes covered $hardCovered/${heard.size}", hardCovered >= heard.size * 0.8)
        assertTrue("hard keeps its train gap", hard.zipWithNext().all { (a, b) -> b - a >= 0.085 })
        val normal = r.chart.chart(Difficulty.NORMAL).notes.map { it.t / 1000.0 }.distinct()
        assertTrue("normal keeps its gap", normal.zipWithNext().all { (a, b) -> b - a >= 0.2 })
        assertTrue("hard is harder than normal", r.chart.chart(Difficulty.HARD).level > r.chart.chart(Difficulty.NORMAL).level)
    }

    /** The fastest trains (~80 ms, Rush E's climax) are note by note only on Expert. */
    @Test
    fun fastestTrainsAreExpertOnly() {
        val s = Synth(44100, 22.0, seed = 5)
        val attacks = ArrayList<Double>()
        var t = 1.0
        var k = 0
        while (t < 20.0) {
            for (j in 0 until 6) {
                val tt = t + j * 0.08
                if (j == 0) s.kick(tt, 0.5f)
                s.tone(tt, 0.07, listOf(72, 74, 76, 77, 79, 81)[(j + k) % 6], if (j == 0) 0.24f else 0.14f)
                attacks.add(tt)
            }
            t += 0.48
            k++
        }
        val r = s.analyze()
        val inside = attacks.filter { it > 3.0 && it < 18.0 }
        val heard = inside.filter { a -> r.onsets.any { abs(it.time - a) < 0.025 } }
        val expert = r.chart.chart(Difficulty.EXPERT).notes.map { it.t / 1000.0 }.distinct()
        val hard = r.chart.chart(Difficulty.HARD).notes.map { it.t / 1000.0 }.distinct()
        val covered = heard.count { a -> expert.any { abs(it - a) < 0.025 } }
        println("fastest trains: heard %d/%d expert %d covered %d hard %d".format(heard.size, inside.size, expert.size, covered, hard.size))
        assertTrue("runs heard ${heard.size}/${inside.size}", heard.size >= inside.size / 2)
        assertTrue("expert runs covered $covered/${heard.size}", covered >= heard.size * 0.8)
        assertTrue("hard keeps its train gap", hard.zipWithNext().all { (a, b) -> b - a >= 0.085 })
        assertTrue("expert is harder", r.chart.chart(Difficulty.EXPERT).level > r.chart.chart(Difficulty.HARD).level)
    }

    /**
     * Quiet start of Rush E: melody notes every ~0.39 s with a soft re-strike of the same key
     * halfway between them, long before a loud ending. The quiet part gets a small loudness
     * budget and the re-strikes look like noise, but the even stream makes them real notes.
     */
    @Test
    fun softStreamBeforeLoudEnding() {
        val s = Synth(44100, 40.0, seed = 7)
        val weak = ArrayList<Double>()
        var t = 1.0
        var k = 0
        while (t < 19.0) {
            s.tone(t, 0.18, 64, 0.1f)
            s.tone(t, 0.36, listOf(45, 52, 48, 50)[(k / 4) % 4], 0.08f)
            s.tone(t + 0.195, 0.16, 64, 0.1f)
            weak.add(t + 0.195)
            t += 0.39
            k++
        }
        t = 20.0
        while (t < 39.0) {
            s.kick(t, 0.9f)
            s.snare(t + 0.25, 0.6f)
            for (m in listOf(57, 64, 69, 72)) s.tone(t, 0.45, m, 0.2f)
            s.tone(t + 0.25, 0.2, 76, 0.25f)
            t += 0.5
        }
        val r = s.analyze()
        val inside = weak.filter { it > 3.0 && it < 18.0 }
        val heard = inside.filter { w -> r.onsets.any { abs(it.time - w) < 0.025 } }
        val hard = r.chart.chart(Difficulty.HARD).notes.map { it.t / 1000.0 }.distinct()
        val covered = heard.count { w -> hard.any { abs(it - w) < 0.025 } }
        println("soft stream: heard %d/%d hard covered %d".format(heard.size, inside.size, covered))
        assertTrue("re-strikes heard ${heard.size}/${inside.size}", heard.size >= inside.size / 2)
        assertTrue("hard re-strikes covered $covered/${heard.size}", covered >= heard.size * 0.8)
    }

    /** Verse / chorus alternation: boundaries on the part changes, repeated parts share a label. */
    @Test
    fun findsRepeatedParts() {
        val bpm = 120.0
        val beat = 60.0 / bpm
        val partBeats = 32 // 8 bars = 16 s
        val order = listOf(0, 1, 0, 1, 0)
        val s = Synth(44100, order.size * partBeats * beat + 1.0)
        // Verse: A minor, soft, kick + snare. Chorus: F / G, loud, hats and snare on every beat.
        val verseChord = listOf(57, 60, 64)
        val chorusChords = listOf(listOf(53, 57, 60, 65), listOf(55, 59, 62, 67))
        for ((p, part) in order.withIndex()) {
            for (b in 0 until partBeats) {
                val t = (p * partBeats + b) * beat + 0.02
                if (part == 0) {
                    if (b % 2 == 0) s.kick(t) else s.snare(t, 0.3f)
                    if (b % 4 == 0) for (m in verseChord) s.tone(t, beat * 4 - 0.05, m, 0.08f)
                    s.tone(t, beat * 0.9, listOf(69, 72, 71, 67)[b % 4], 0.12f)
                } else {
                    s.kick(t)
                    s.snare(t, 0.45f)
                    s.hat(t, 0.3f)
                    s.hat(t + beat / 2, 0.3f)
                    if (b % 4 == 0) for (m in chorusChords[(b / 8) % 2]) s.tone(t, beat * 4 - 0.05, m + 12, 0.12f)
                    s.tone(t, beat * 0.45, listOf(77, 76, 74, 72)[b % 4], 0.16f)
                }
            }
        }
        val r = s.analyze()
        val secs = r.chart.sections
        println("parts: " + secs.joinToString { "%.2f:%d(%d)".format(it.t / 1000.0, it.label, it.energy) })
        val truth = (1 until order.size).map { it * partBeats * beat + 0.02 }
        for (tb in truth) {
            assertTrue("boundary near $tb in $secs", secs.any { abs(it.t / 1000.0 - tb) < 1.1 })
        }
        // Label of the part playing in the middle of each true part.
        fun labelAt(t: Double) = secs.last { it.t / 1000.0 <= t }.label
        val mids = order.indices.map { labelAt((it + 0.5) * partBeats * beat) }
        assertEquals(mids[0], mids[2])
        assertEquals(mids[0], mids[4])
        assertEquals(mids[1], mids[3])
        assertTrue(mids[0] != mids[1])
    }
}
