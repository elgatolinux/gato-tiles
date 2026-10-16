package com.gatotiles.game

import com.gatotiles.analyzer.model.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ScrollMapTest {
    @Test
    fun constantMapIsPlainTime() {
        val m = ScrollMap.CONSTANT
        for (t in listOf(-2400.0, 0.0, 1234.5, 200_000.0)) {
            assertEquals(t, m.pos(t), 0.0)
            assertEquals(t, m.timeAt(t), 1e-9)
            assertEquals(1.0, m.speedAt(t), 0.0)
        }
    }

    @Test
    fun speedFollowsThePartsWithSmoothRamps() {
        val m = ScrollMap.fromParts(listOf(0.0, 10_000.0, 20_000.0), doubleArrayOf(1.0, 1.4, 1.0))
        assertEquals(1.0, m.speedAt(-1000.0), 0.0)
        assertEquals(1.0, m.speedAt(10_000.0 - ScrollMap.RAMP_BEFORE_MS - 1), 0.0)
        assertEquals(1.4, m.speedAt(10_000.0 + ScrollMap.RAMP_AFTER_MS + 1), 0.0)
        assertEquals(1.4, m.speedAt(15_000.0), 0.0)
        assertEquals(1.0, m.speedAt(25_000.0), 0.0)
        // No jumps: the speed changes little from one frame to the next.
        var t = -2000.0
        var prev = m.speedAt(t)
        while (t < 30_000.0) {
            t += 16.0
            val s = m.speedAt(t)
            assertTrue("jump at $t", abs(s - prev) < 0.03)
            prev = s
        }
    }

    @Test
    fun positionIsTheIntegralOfTheSpeed() {
        val m = ScrollMap.fromParts(listOf(0.0, 8_000.0, 9_000.0, 30_000.0), doubleArrayOf(1.0, 1.3, 1.1, 1.35))
        val dt = 1.0
        var t = -3000.0
        var p = m.pos(t)
        while (t < 40_000.0) {
            val next = t + dt
            // Midpoint rule.
            p += m.speedAt(t + dt / 2) * dt
            t = next
            if (t.toInt() % 997 == 0) assertEquals("at $t", p, m.pos(t), 1e-3)
        }
    }

    @Test
    fun timeAtInvertsPos() {
        val m = ScrollMap.fromParts(listOf(0.0, 5_000.0, 6_000.0, 20_000.0), doubleArrayOf(1.2, 1.0, 1.4, 1.1))
        var t = -2500.0
        while (t < 30_000.0) {
            assertEquals("at $t", t, m.timeAt(m.pos(t)), 1e-6)
            t += 37.3
        }
    }

    @Test
    fun busyLoudPartsAreFaster() {
        // Quiet sparse intro, loud dense chorus, medium verse.
        val sections = listOf(Section(0, 0, 10), Section(20_000, 1, 90), Section(40_000, 2, 50))
        val notes = (0 until 20_000 step 800).toList() + (20_000 until 40_000 step 160).toList() + (40_000 until 60_000 step 330).toList()
        val speeds = ScrollMap.sectionSpeeds(sections, notes.toIntArray(), 60_000, 0.4)
        assertEquals(1.0, speeds[0], 1e-9)
        assertEquals(1.4, speeds[1], 1e-9)
        assertTrue("verse ${speeds[2]}", speeds[2] > 1.0 && speeds[2] < speeds[1])
        assertTrue(ScrollMap.sectionSpeeds(sections, notes.toIntArray(), 60_000, 0.0).all { it == 1.0 })
        // A song with a single part keeps one speed.
        assertTrue(ScrollMap.sectionSpeeds(sections.take(1), notes.toIntArray(), 60_000, 0.4).all { it == 1.0 })
    }

    @Test
    fun fastPartsStayReadableAtHighSpeed() {
        val sections = listOf(Section(0, 0, 10), Section(20_000, 1, 95))
        val notes = ((0 until 20_000 step 900) + (20_000 until 40_000 step 150)).toIntArray()
        val approach = 600
        val m = ScrollMap.forSong(sections, notes, 40_000, 0.4, approach)
        val top = m.speedAt(30_000.0)
        assertTrue("top $top", top > 1.0)
        assertTrue("tiles cross in ${approach / top} ms", approach / top >= ScrollMap.MIN_APPROACH_MS - 1e-6)
    }

    @Test
    fun shortPartsDoNotBreakTheMap() {
        val starts = (0 until 20).map { it * 700.0 }
        val speeds = DoubleArray(20) { if (it % 2 == 0) 1.0 else 1.4 }
        val m = ScrollMap.fromParts(starts, speeds)
        var t = -1000.0
        var p = m.pos(t)
        while (t < 16_000.0) {
            t += 5.0
            val q = m.pos(t)
            assertTrue("pos goes back at $t", q > p)
            val s = m.speedAt(t)
            assertTrue("speed $s at $t", s >= 1.0 - 1e-9 && s <= 1.4 + 1e-9)
            p = q
        }
    }
}
