package com.gatotiles.game

import com.gatotiles.analyzer.model.Section
import com.gatotiles.data.ColorFlow
import com.gatotiles.data.CoverPalette
import com.gatotiles.data.ResolvedColors
import com.gatotiles.data.SongColorConfig
import com.gatotiles.util.ArtColors
import com.gatotiles.util.ColorUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorTimelineTest {
    private val red = ArtColors.fromSeed(0xFFE53935.toInt())
    private val green = ArtColors.fromSeed(0xFF43A047.toInt())
    private val blue = ArtColors.fromSeed(0xFF1E88E5.toInt())

    private fun sections(vararg labels: Int) = labels.mapIndexed { i, l -> Section(i * 10_000, l, 50) }

    @Test
    fun repeatedPartsKeepTheirColor() {
        // Verse, chorus, verse, chorus, bridge, chorus.
        val colors = ColorTimeline.colorsForSections(sections(0, 1, 0, 1, 2, 1), 3)
        assertEquals(colors[0], colors[2])
        assertEquals(colors[1], colors[3])
        assertEquals(colors[1], colors[5])
        assertNotEquals(colors[0], colors[1])
        assertNotEquals(colors[4], colors[1])
    }

    @Test
    fun neighboursDifferWhenColorsRunOut() {
        // Three kinds of parts but only two colors: still a change at every new part.
        val colors = ColorTimeline.colorsForSections(sections(0, 1, 2), 2)
        assertNotEquals(colors[0], colors[1])
        assertNotEquals(colors[1], colors[2])
    }

    @Test
    fun sectionsSwitchColorOnTheBoundary() {
        val t = ColorTimeline(listOf(red, green), ColorFlow.SECTIONS, sections(0, 1, 0), 30_000)
        assertTrue(t.dynamic)
        val f = ColorFrame()
        t.sample(5_000.0, f)
        assertEquals(red.accent, f.accent)
        assertEquals(0f, f.mix)
        // Right after the boundary the new color has (almost) landed.
        t.sample(10_000.0 + ColorTimeline.FADE_AFTER_MS, f)
        assertEquals(green.accent, f.accent)
        t.sample(15_000.0, f)
        assertEquals(green.accent, f.accent)
        assertEquals(1, f.from)
        // Half way through the fade both sprite sets are drawn.
        t.sample(10_000.0 - ColorTimeline.FADE_BEFORE_MS / 2, f)
        assertEquals(0, f.from)
        assertEquals(1, f.to)
        assertTrue(f.mix in 0.05f..0.95f)
        t.sample(25_000.0, f)
        assertEquals(red.accent, f.accent)
        assertEquals(listOf(10_000, 20_000), t.cutTimes)
    }

    @Test
    fun gradualGoesThroughEveryColor() {
        val t = ColorTimeline(listOf(red, green, blue), ColorFlow.GRADUAL, emptyList(), 100_000)
        val f = ColorFrame()
        t.sample(-500.0, f)
        assertEquals(red.accent, f.accent)
        t.sample(50_000.0, f)
        assertEquals(green.accent, f.accent)
        t.sample(100_000.0, f)
        assertEquals(blue.accent, f.accent)
        // Midway between two colors: vivid (OKLCH), not grey.
        t.sample(25_000.0, f)
        assertTrue(ColorUtil.toOklch(f.accent).c > 0.1)
        assertTrue(t.cutTimes.isEmpty())
    }

    @Test
    fun fixedNeverChanges() {
        val t = ColorTimeline(listOf(red, green), ColorFlow.FIXED, sections(0, 1, 2), 30_000)
        assertFalse(t.dynamic)
        val f = ColorFrame()
        t.sample(25_000.0, f)
        assertEquals(red.accent, f.accent)
    }

    @Test
    fun resolveUsesChoiceOrCover() {
        val cover = ArtColors.fromSeed(0xFF8E24AA.toInt())
        val pal = CoverPalette(cover.seed, listOf(cover.seed, 0xFFFFC107.toInt(), 0xFF00ACC1.toInt()), CoverPalette.autoSequence(cover.seed, listOf(0xFFFFC107.toInt(), 0xFF00ACC1.toInt())), emptyList())
        // Nothing chosen, fixed: exactly the cover colors.
        val a = ResolvedColors.resolve(SongColorConfig(), ColorFlow.FIXED, pal, cover)
        assertEquals(listOf(cover), a.palettes)
        // Nothing chosen, by parts: several cover colors, the first one is the cover's.
        val b = ResolvedColors.resolve(SongColorConfig(), ColorFlow.SECTIONS, pal, cover)
        assertTrue(b.palettes.size >= 3)
        assertEquals(cover, b.palettes[0])
        // Chosen colors win; fixed mode uses only the first.
        val chosen = listOf(0xFFFFFFFF.toInt(), 0xFF1E88E5.toInt())
        val c = ResolvedColors.resolve(SongColorConfig(flow = ColorFlow.FIXED, colors = chosen), ColorFlow.SECTIONS, pal, cover)
        assertEquals(ColorFlow.FIXED, c.flow)
        assertEquals(1, c.palettes.size)
        // A chosen white stays white (no invented hue).
        assertTrue(ColorUtil.toOklch(c.main.accent).c < 0.03)
        val d = ResolvedColors.resolve(SongColorConfig(colors = chosen), ColorFlow.GRADUAL, pal, cover)
        assertEquals(2, d.palettes.size)
    }

    @Test
    fun autoSequenceFillsGreyCovers() {
        val seq = CoverPalette.autoSequence(0xFF808080.toInt(), listOf(0xFF7A7A7A.toInt(), 0xFF202020.toInt()))
        assertTrue(seq.size >= 3)
    }
}
