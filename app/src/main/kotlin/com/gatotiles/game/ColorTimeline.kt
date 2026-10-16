package com.gatotiles.game

import com.gatotiles.analyzer.model.Section
import com.gatotiles.data.ColorFlow
import com.gatotiles.util.ArtColors
import com.gatotiles.util.ColorUtil
import com.gatotiles.util.Oklch
import kotlin.math.max
import kotlin.math.roundToInt

/** Colors of one frame; filled by [ColorTimeline.sample] without allocating. */
class ColorFrame {
    var accent = 0
    var accent2 = 0
    var deep = 0
    /** Sprite sets to draw: [from] fully, then [to] on top with alpha [mix]. */
    var from = 0
    var to = 0
    var mix = 0f
    /** Index of the last color change that has started (-1 = none yet), for effects. */
    var change = -1
}

/**
 * Which color the game shows at each moment of the song. All blends are precomputed in
 * OKLCH (vivid midpoints instead of grey ones), so [sample] is just a lookup.
 */
class ColorTimeline(
    val palettes: List<ArtColors>,
    flow: ColorFlow,
    sections: List<Section>,
    durationMs: Int,
) {
    private class Change(val startMs: Double, val endMs: Double, val from: Int, val to: Int, val smooth: Boolean) {
        lateinit var accent: IntArray
        lateinit var accent2: IntArray
        lateinit var deep: IntArray
    }

    private val changes: Array<Change>
    private val initial: Int

    /** Palette index of each section (SECTIONS mode), for the preview. */
    val sectionColors: IntArray

    /** True when the colors actually change during the song. */
    val dynamic: Boolean get() = changes.isNotEmpty()

    init {
        require(palettes.isNotEmpty())
        val n = palettes.size
        val list = ArrayList<Change>()
        sectionColors = if (flow == ColorFlow.SECTIONS && n > 1) colorsForSections(sections, n) else IntArray(sections.size)
        when {
            n == 1 || flow == ColorFlow.FIXED -> initial = 0
            flow == ColorFlow.SECTIONS && sections.size > 1 -> {
                initial = sectionColors[0]
                for (i in 1 until sections.size) {
                    val prev = sectionColors[i - 1]
                    val cur = sectionColors[i]
                    if (cur == prev) continue
                    // The new color lands right on the first beat of the part.
                    val t = sections[i].t.toDouble()
                    list.add(Change(t - FADE_BEFORE_MS, t + FADE_AFTER_MS, prev, cur, smooth = false))
                }
            }
            flow == ColorFlow.GRADUAL -> {
                initial = 0
                val seg = max(1.0, durationMs.toDouble() / (n - 1))
                for (k in 0 until n - 1) list.add(Change(k * seg, (k + 1) * seg, k, k + 1, smooth = true))
            }
            else -> initial = 0
        }
        val lch = palettes.map { Triple(ColorUtil.toOklch(it.accent), ColorUtil.toOklch(it.accent2), ColorUtil.toOklch(it.deep)) }
        for (c in list) {
            val steps = if (c.smooth) 256 else 32
            val a = lch[c.from]
            val b = lch[c.to]
            val pa = palettes[c.from]
            val pb = palettes[c.to]
            // Exact palette colors at both ends (an OKLCH round trip can be off by one).
            fun lut(x: Int, y: Int, lx: Oklch, ly: Oklch) = IntArray(steps + 1) {
                when (it) {
                    0 -> x
                    steps -> y
                    else -> ColorUtil.lerpOklch(lx, ly, it / steps.toFloat())
                }
            }
            c.accent = lut(pa.accent, pb.accent, a.first, b.first)
            c.accent2 = lut(pa.accent2, pb.accent2, a.second, b.second)
            c.deep = lut(pa.deep, pb.deep, a.third, b.third)
        }
        changes = list.toTypedArray()
    }

    /** Start times (ms) of the color changes that are cuts (not slow blends). */
    val cutTimes: List<Int> get() = changes.filter { !it.smooth }.map { (it.startMs + FADE_BEFORE_MS).roundToInt() }

    fun sample(songMs: Double, out: ColorFrame) {
        // Last change that has started.
        var lo = 0
        var hi = changes.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (changes[mid].startMs <= songMs) {
                idx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        out.change = idx
        if (idx < 0) {
            steady(initial, out)
            return
        }
        val c = changes[idx]
        if (songMs >= c.endMs) {
            steady(c.to, out)
            return
        }
        var t = ((songMs - c.startMs) / (c.endMs - c.startMs)).toFloat().coerceIn(0f, 1f)
        // Gentle in and out, so each color lingers a while.
        t = t * t * (3 - 2 * t)
        val k = (t * (c.accent.size - 1)).roundToInt()
        out.accent = c.accent[k]
        out.accent2 = c.accent2[k]
        out.deep = c.deep[k]
        out.from = c.from
        out.to = c.to
        out.mix = t
    }

    /** Colors at a fraction (0..1) of the song, for previews. */
    fun accentAt(songMs: Double): Int {
        val f = ColorFrame()
        sample(songMs, f)
        return f.accent
    }

    private fun steady(i: Int, out: ColorFrame) {
        val p = palettes[i]
        out.accent = p.accent
        out.accent2 = p.accent2
        out.deep = p.deep
        out.from = i
        out.to = i
        out.mix = 0f
    }

    companion object {
        const val FADE_BEFORE_MS = 380.0
        const val FADE_AFTER_MS = 170.0

        /**
         * A color per section: each label keeps its color (a chorus always looks the same)
         * and, when possible, consecutive parts get different colors.
         */
        fun colorsForSections(sections: List<Section>, n: Int): IntArray {
            val byLabel = HashMap<Int, Int>()
            val out = IntArray(sections.size)
            var next = 0
            for ((i, s) in sections.withIndex()) {
                val known = byLabel[s.label]
                val c = if (known != null) {
                    known
                } else {
                    var pick = next % n
                    if (i > 0 && pick == out[i - 1] && n > 1) pick = (pick + 1) % n
                    next = pick + 1
                    byLabel[s.label] = pick
                    pick
                }
                out[i] = c
            }
            return out
        }
    }
}
