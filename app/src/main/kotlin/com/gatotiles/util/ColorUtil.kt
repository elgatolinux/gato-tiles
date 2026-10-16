package com.gatotiles.util

import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Color in OKLCH: lightness 0..1, chroma ~0..0.37, hue in degrees. */
data class Oklch(val l: Double, val c: Double, val h: Double)

object ColorUtil {

    private fun toLinear(c: Double): Double = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun toSrgb(c: Double): Double = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055

    fun toOklch(argb: Int): Oklch {
        val r = toLinear(((argb shr 16) and 0xFF) / 255.0)
        val g = toLinear(((argb shr 8) and 0xFF) / 255.0)
        val b = toLinear((argb and 0xFF) / 255.0)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        val ll = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
        val aa = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        val bb = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        var h = Math.toDegrees(atan2(bb, aa))
        if (h < 0) h += 360.0
        return Oklch(ll, sqrt(aa * aa + bb * bb), h)
    }

    /** Linear sRGB triple, possibly out of gamut. */
    private fun oklchToLinear(l: Double, c: Double, h: Double): DoubleArray {
        val hr = Math.toRadians(h)
        val a = c * cos(hr)
        val b = c * sin(hr)
        val l_ = l + 0.3963377774 * a + 0.2158037573 * b
        val m_ = l - 0.1055613458 * a - 0.0638541728 * b
        val s_ = l - 0.0894841775 * a - 1.2914855480 * b
        val ll = l_ * l_ * l_
        val mm = m_ * m_ * m_
        val ss = s_ * s_ * s_
        return doubleArrayOf(
            4.0767416621 * ll - 3.3077115913 * mm + 0.2309699292 * ss,
            -1.2684380046 * ll + 2.6097574011 * mm - 0.3413193965 * ss,
            -0.0041960863 * ll - 0.7034186147 * mm + 1.7076147010 * ss,
        )
    }

    private fun inGamut(rgb: DoubleArray): Boolean = rgb.all { it >= -1e-4 && it <= 1.0001 }

    /** OKLCH to ARGB, reducing chroma until the color fits in sRGB. */
    fun fromOklch(l: Double, c: Double, h: Double, alpha: Int = 255): Int {
        val lc = l.coerceIn(0.0, 1.0)
        var rgb = oklchToLinear(lc, c, h)
        if (!inGamut(rgb)) {
            var lo = 0.0
            var hi = c
            repeat(18) {
                val mid = (lo + hi) / 2
                if (inGamut(oklchToLinear(lc, mid, h))) lo = mid else hi = mid
            }
            rgb = oklchToLinear(lc, lo, h)
        }
        val r = (toSrgb(rgb[0].coerceIn(0.0, 1.0)) * 255).toInt().coerceIn(0, 255)
        val g = (toSrgb(rgb[1].coerceIn(0.0, 1.0)) * 255).toInt().coerceIn(0, 255)
        val b = (toSrgb(rgb[2].coerceIn(0.0, 1.0)) * 255).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun withAlpha(argb: Int, alpha: Int): Int = (argb and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    fun withAlpha(argb: Int, alpha: Float): Int = withAlpha(argb, (alpha * 255).toInt())

    /** Oklab (l, a, b) of an ARGB color. */
    fun toOklab(argb: Int): DoubleArray {
        val o = toOklch(argb)
        val hr = Math.toRadians(o.h)
        return doubleArrayOf(o.l, o.c * cos(hr), o.c * sin(hr))
    }

    fun fromOklab(l: Double, a: Double, b: Double): Int {
        var h = Math.toDegrees(atan2(b, a))
        if (h < 0) h += 360.0
        return fromOklch(l, sqrt(a * a + b * b), h)
    }

    /** Weighted average in Oklab (perceptual, so mixes do not turn muddy). */
    fun mix(colors: List<Int>, weights: List<Double>? = null): Int {
        if (colors.isEmpty()) return 0xFF000000.toInt()
        var l = 0.0
        var a = 0.0
        var b = 0.0
        var wsum = 0.0
        for ((i, c) in colors.withIndex()) {
            val w = weights?.getOrNull(i) ?: 1.0
            val lab = toOklab(c)
            l += lab[0] * w
            a += lab[1] * w
            b += lab[2] * w
            wsum += w
        }
        if (wsum <= 0) return colors[0]
        return fromOklab(l / wsum, a / wsum, b / wsum)
    }

    /** Interpolates in OKLCH along the shorter hue arc, keeping colors vivid half way. */
    fun lerpOklch(a: Oklch, b: Oklch, t: Float): Int {
        val tt = t.coerceIn(0f, 1f).toDouble()
        // A grey has no meaningful hue: borrow the other color's.
        val ha = if (a.c < 0.02) b.h else a.h
        val hb = if (b.c < 0.02) a.h else b.h
        var dh = hb - ha
        if (dh > 180) dh -= 360.0
        if (dh < -180) dh += 360.0
        var h = ha + dh * tt
        if (h < 0) h += 360.0
        if (h >= 360) h -= 360.0
        return fromOklch(a.l + (b.l - a.l) * tt, a.c + (b.c - a.c) * tt, h)
    }

    /** Hue distance in degrees (0..180). */
    fun hueDistance(a: Double, b: Double): Double {
        val d = kotlin.math.abs(a - b) % 360.0
        return if (d > 180) 360 - d else d
    }

    fun toHex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

    /** Parses "#RRGGBB", "RRGGBB" or "#RGB"; null if it is not a color. */
    fun parseHex(text: String): Int? {
        var t = text.trim().removePrefix("#")
        if (t.length == 3) t = t.map { "$it$it" }.joinToString("")
        if (t.length != 6 || !t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return (0xFF000000.toInt() or t.toInt(16))
    }

    fun lerp(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * tt).toInt() and 0xFF
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/**
 * Colors derived from an album cover for the game screen: [accent] is a bright neon
 * version of the cover's main hue, [accent2] a neighbouring hue for gradients, [deep] a
 * very dark tint for backgrounds.
 */
data class ArtColors(
    val seed: Int,
    val accent: Int,
    val accent2: Int,
    val deep: Int,
    val isDefault: Boolean,
) {
    companion object {
        const val DEFAULT_SEED = 0xFF8A5CFF.toInt()

        /**
         * Game colors for [seed]. With [neutral] (a color the player picked), whites and greys
         * stay grey instead of being given a hue.
         */
        fun fromSeed(seed: Int, isDefault: Boolean = false, neutral: Boolean = false): ArtColors {
            val o = ColorUtil.toOklch(seed)
            if (neutral && o.c < 0.035) {
                return ArtColors(
                    seed = seed,
                    accent = ColorUtil.fromOklch(0.88, o.c * 0.5, o.h),
                    accent2 = ColorUtil.fromOklch(0.70, o.c * 0.5, o.h),
                    deep = ColorUtil.fromOklch(0.15, 0.006, o.h),
                    isDefault = isDefault,
                )
            }
            // Grey covers: keep the hue but give it some color so the game still glows.
            val c = if (o.c < 0.04) 0.13 else o.c.coerceIn(0.14, 0.24)
            val hue = if (o.c < 0.02) 285.0 else o.h
            return ArtColors(
                seed = seed,
                accent = ColorUtil.fromOklch(0.80, c, hue),
                accent2 = ColorUtil.fromOklch(0.70, c, (hue + 50) % 360),
                deep = ColorUtil.fromOklch(0.16, 0.05, hue),
                isDefault = isDefault,
            )
        }

        val Default = fromSeed(DEFAULT_SEED, isDefault = true)
    }
}
