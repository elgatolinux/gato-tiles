package com.gatotiles.game

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.gatotiles.util.ColorUtil
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Pre-rendered artwork for the game, drawn once per layout so each frame only blits
 * bitmaps (blurs and gradients are too expensive to do per tile per frame). These are
 * ALPHA_8 sprites, tinted with the paint color when drawn; colored tiles are [TileSprites].
 */
class Sprites(laneW: Float, tileH: Float, private val density: Float) {
    val gap = laneW * 0.07f
    val tileW = laneW - 2 * gap
    val tileHeight = tileH
    val pad = max(6f * density, laneW * 0.16f)
    val spriteW = (tileW + 2 * pad).toInt()
    val spriteH = (tileH + 2 * pad).toInt()

    val glowDot: Bitmap = makeGlowDot(64)
    val glowLine: Bitmap = makeGlowLine(256, 64)
    val beam: Bitmap = makeBeam(64, 256)
    /** Soft disc for the aurora glows: 1 in the center, 0.32 at 45 %, 0 at the edge. */
    val auroraDot: Bitmap = makeRadial(128, floatArrayOf(1f, 0.318f, 0f), floatArrayOf(0f, 0.45f, 1f))

    /** Vertical alpha ramp: 0 at the top, 1 at [peak], [end] at the bottom (track edges). */
    fun makeEdgeRamp(peak: Float, end: Float): Bitmap {
        val h = 256
        val bmp = Bitmap.createBitmap(1, h, Bitmap.Config.ALPHA_8)
        val px = ByteArray(h)
        for (y in 0 until h) {
            val f = y / (h - 1f)
            val v = if (f < peak) f / peak else 1f + (end - 1f) * (f - peak) / (1f - peak)
            px[y] = (255 * v).toInt().coerceIn(0, 255).toByte()
        }
        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(px))
        return bmp
    }

    private fun makeRadial(size: Int, values: FloatArray, stops: FloatArray): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val px = ByteArray(size * size)
        val c = (size - 1) / 2f
        for (y in 0 until size) for (x in 0 until size) {
            val r = Math.hypot((x - c).toDouble(), (y - c).toDouble()).toFloat() / (size / 2f)
            var v = 0f
            for (k in 0 until stops.size - 1) {
                if (r >= stops[k] && r <= stops[k + 1]) {
                    v = values[k] + (values[k + 1] - values[k]) * (r - stops[k]) / (stops[k + 1] - stops[k])
                    break
                }
            }
            px[y * size + x] = (255 * v).toInt().coerceIn(0, 255).toByte()
        }
        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(px))
        return bmp
    }

    private fun makeGlowDot(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                size / 2f, size / 2f, size / 2f,
                intArrayOf(0xFFFFFFFF.toInt(), 0x66FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.35f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        return bmp
    }

    private fun makeGlowLine(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val px = ByteArray(w * h)
        for (y in 0 until h) {
            val dy = (y - h / 2f) / (h / 2f)
            val vy = exp(-dy * dy * 6.0)
            for (x in 0 until w) {
                val ex = min(x, w - 1 - x) / (w * 0.08)
                val vx = min(1.0, ex)
                px[y * w + x] = (255 * vy * vx).toInt().coerceIn(0, 255).toByte()
            }
        }
        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(px))
        return bmp
    }

    private fun makeBeam(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val px = ByteArray(w * h)
        for (y in 0 until h) {
            val vy = (y / (h - 1.0)).pow(1.8)
            for (x in 0 until w) {
                val vx = sin(PI * (x + 0.5) / w).pow(0.6)
                px[y * w + x] = (255 * vy * vx).toInt().coerceIn(0, 255).toByte()
            }
        }
        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(px))
        return bmp
    }

    fun recycle() {
        listOf(glowDot, glowLine, beam, auroraDot).forEach { it.recycle() }
    }
}

/** The tiles in one palette color (a song that changes color has one set per color). */
class TileSprites(geo: Sprites, private val accent: Int, private val accent2: Int, private val density: Float) {
    private val pad = geo.pad
    private val tileW = geo.tileW
    private val tileHeight = geo.tileHeight
    private val spriteW = geo.spriteW
    private val spriteH = geo.spriteH

    val tile: Bitmap = makeTile(hold = false)
    val holdHead: Bitmap = makeTile(hold = true)
    val tileFlash: Bitmap = makeFlash()
    val holdBody: Bitmap = makeHoldBody(active = false)
    val holdBodyActive: Bitmap = makeHoldBody(active = true)

    private fun tileRect() = RectF(pad, pad, pad + tileW, pad + tileHeight)

    private fun makeTile(hold: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(spriteW, spriteH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = tileRect()
        val rad = min(r.width(), r.height()) * 0.18f
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtil.withAlpha(accent, 0.75f)
            maskFilter = BlurMaskFilter(pad * 0.75f, BlurMaskFilter.Blur.OUTER)
        }
        c.drawRoundRect(r, rad, rad, glow)
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, r.top, 0f, r.bottom,
                intArrayOf(0xFF1D1A28.toInt(), 0xFF0C0B12.toInt(), ColorUtil.lerp(0xFF0C0B12.toInt(), accent, 0.18f)),
                floatArrayOf(0f, 0.6f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawRoundRect(r, rad, rad, body)
        val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, r.top, 0f, r.top + r.height() * 0.5f, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        }
        val inset = 2f * density
        c.drawRoundRect(RectF(r.left + inset, r.top + inset, r.right - inset, r.top + r.height() * 0.5f), rad * 0.8f, rad * 0.8f, sheen)
        val sw = 2.2f * density
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = sw
            shader = LinearGradient(
                0f, r.top, 0f, r.bottom,
                ColorUtil.lerp(accent, 0xFFFFFFFF.toInt(), 0.35f), accent2,
                Shader.TileMode.CLAMP,
            )
        }
        c.drawRoundRect(RectF(r.left + sw / 2, r.top + sw / 2, r.right - sw / 2, r.bottom - sw / 2), rad, rad, border)
        // Beatstar-like glowing slit across the middle (two for holds).
        val slitW = r.width() * 0.5f
        val slitH = 3.2f * density
        val ys = if (hold) floatArrayOf(r.centerY() - slitH * 1.6f, r.centerY() + slitH * 1.6f) else floatArrayOf(r.centerY())
        val slitGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            maskFilter = BlurMaskFilter(5f * density, BlurMaskFilter.Blur.NORMAL)
        }
        val slitCore = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtil.lerp(accent, 0xFFFFFFFF.toInt(), 0.7f) }
        for (y in ys) {
            val sr = RectF(r.centerX() - slitW / 2, y - slitH / 2, r.centerX() + slitW / 2, y + slitH / 2)
            c.drawRoundRect(sr, slitH, slitH, slitGlow)
            sr.inset(slitW * 0.08f, slitH * 0.2f)
            c.drawRoundRect(sr, slitH, slitH, slitCore)
        }
        return bmp
    }

    private fun makeFlash(): Bitmap {
        val bmp = Bitmap.createBitmap(spriteW, spriteH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = tileRect()
        val rad = min(r.width(), r.height()) * 0.18f
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            maskFilter = BlurMaskFilter(pad * 0.9f, BlurMaskFilter.Blur.OUTER)
        }
        c.drawRoundRect(r, rad, rad, glow)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                r.centerX(), r.centerY(), max(r.width(), r.height()) * 0.75f,
                intArrayOf(0xFFFFFFFF.toInt(), ColorUtil.lerp(accent, 0xFFFFFFFF.toInt(), 0.45f), accent),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawRoundRect(r, rad, rad, fill)
        return bmp
    }

    private fun makeHoldBody(active: Boolean): Bitmap {
        val h = 16
        val bmp = Bitmap.createBitmap(spriteW, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = spriteW / 2f
        val bodyW = tileW * 0.42f
        val glowW = bodyW * 0.5f
        // Horizontal profile: soft outer glow, translucent column, bright edges and core.
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                cx - bodyW / 2 - glowW, 0f, cx + bodyW / 2 + glowW, 0f,
                intArrayOf(0, ColorUtil.withAlpha(accent, if (active) 0.55f else 0.25f), ColorUtil.withAlpha(accent, if (active) 0.55f else 0.25f), 0),
                floatArrayOf(0f, 0.3f, 0.7f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(cx - bodyW / 2 - glowW, 0f, cx + bodyW / 2 + glowW, h.toFloat(), glow)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (active) ColorUtil.withAlpha(ColorUtil.lerp(accent, accent2, 0.3f), 0.85f) else 0xD0120F1A.toInt()
        }
        c.drawRect(cx - bodyW / 2, 0f, cx + bodyW / 2, h.toFloat(), fill)
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtil.withAlpha(accent, if (active) 1f else 0.8f) }
        val ew = 1.8f * density
        c.drawRect(cx - bodyW / 2, 0f, cx - bodyW / 2 + ew, h.toFloat(), edge)
        c.drawRect(cx + bodyW / 2 - ew, 0f, cx + bodyW / 2, h.toFloat(), edge)
        val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (active) 0xFFFFFFFF.toInt() else ColorUtil.withAlpha(accent, 0.7f)
        }
        val cw = (if (active) 2.6f else 1.6f) * density
        c.drawRect(cx - cw / 2, 0f, cx + cw / 2, h.toFloat(), core)
        return bmp
    }

    fun recycle() {
        listOf(tile, holdHead, tileFlash, holdBody, holdBodyActive).forEach { it.recycle() }
    }
}
