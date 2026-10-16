package com.gatotiles.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import com.gatotiles.data.GameMode
import com.gatotiles.util.Blur
import com.gatotiles.util.ColorUtil
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws a game frame on a hardware canvas: blurred-cover background with aurora glows,
 * the album disc with live spectrum bars, the track (flat or perspective), tiles, holds,
 * hit effects, judgments and HUD. All per-frame work is allocation free.
 */
class GameRenderer(context: Context, private val s: GameSession, private val art: Bitmap?) {
    private val density = context.resources.displayMetrics.density
    private val quality = s.settings.effects
    private val timeline = s.timeline
    private val frame = ColorFrame()
    // Current colors: change during the song when the timeline is dynamic.
    private var accent = timeline.palettes[0].accent
    private var accent2 = timeline.palettes[0].accent2
    private var deep = timeline.palettes[0].deep
    private val cutTimes = timeline.cutTimes.toIntArray()
    private var nextCut = 0
    private var cutAt = -10_000L
    private var frameNowMs = 0L
    private val mode = s.mode
    private val rnd = Random(7)

    private val tfBlack = font(context, 900)
    private val tfBold = font(context, 700)
    private val tfMedium = font(context, 500)

    var layout: TrackLayout? = null
        private set
    private var sprites: Sprites? = null
    private var tileSets: Array<TileSprites> = emptyArray()
    private var edgeRamp: Bitmap? = null
    /** Additive layer that tints the background with [deep] (only for songs that change color). */
    private var deepMask: Bitmap? = null
    private var w = 0
    private var h = 0
    private var topInset = 0f
    private var backdrop: Bitmap? = null
    private var disc: Bitmap? = null

    // Geometry (screen space)
    private val screen = RectF()
    private var discCx = 0f
    private var discCy = 0f
    private var discR = 0f
    private var scoreY = 0f
    private var comboY = 0f
    private var judgeY = 0f
    /** Touches above this y are HUD taps, not lane taps. */
    var hudBottom = 0f
        private set

    // Paints
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val backdropPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint()
    private val vignettePaint = Paint()
    private val flashPaint = Paint().additive()
    private val auroraPaint = Paint(Paint.FILTER_BITMAP_FLAG).additive()
    private val deepPaint = Paint(Paint.FILTER_BITMAP_FLAG).additive()
    private val trackPaint = Paint()
    private val sepPaint = Paint()
    private val edgePaint = Paint(Paint.FILTER_BITMAP_FLAG).additive()
    private val beatPaint = Paint()
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG).additive()
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val hitCorePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val redPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val judgePaint = textPaint(tfBlack, 30f, Paint.Align.CENTER).apply { letterSpacing = 0.12f }
    private val subPaint = textPaint(tfBold, 12f, Paint.Align.CENTER).apply { letterSpacing = 0.25f }
    private val comboPaint = textPaint(tfBlack, 56f, Paint.Align.CENTER)
    private val comboLabelPaint = textPaint(tfBold, 12f, Paint.Align.CENTER).apply { letterSpacing = 0.45f }
    private val scorePaint = textPaint(tfBold, 22f, Paint.Align.CENTER).apply { letterSpacing = 0.05f }
    private val tilesPaint = textPaint(tfBlack, 44f, Paint.Align.CENTER)
    private val startBigPaint = textPaint(tfBlack, 30f, Paint.Align.CENTER).apply { letterSpacing = 0.1f }
    private val startSmallPaint = textPaint(tfMedium, 13f, Paint.Align.CENTER).apply { letterSpacing = 0.2f }
    private val fpsPaint = textPaint(tfMedium, 11f, Paint.Align.LEFT)

    private val rect = RectF()
    private val rect2 = RectF()
    private val pt = FloatArray(2)
    private val pt2 = FloatArray(2)
    private val chars = CharArray(24)
    private val fmtTmp = CharArray(24)
    private val starPath = Path()

    // Spectrum
    private val specRaw = FloatArray(com.gatotiles.audio.SongPlayer.BANDS)
    private val spec = FloatArray(com.gatotiles.audio.SongPlayer.BANDS)

    // Effects state
    private val lanes = s.lanes
    private val laneGlow = FloatArray(lanes)
    private val laneGlowColor = IntArray(lanes) { accent }
    private class Burst {
        var lane = 0
        var at = 0L
        var color = 0
        var hold = false
    }
    private val bursts = Array(16) { Burst().apply { at = -10_000 } }
    private var burstIdx = 0
    private var judge: Judgment? = null
    private var judgeAt = -10_000L
    private var judgeTiming = 0
    private var comboShown = 0
    private var comboBumpAt = -10_000L
    private val shockAt = LongArray(4) { -10_000L }
    private val shockColor = IntArray(4)
    private var shockIdx = 0
    private var displayScore = 0.0
    private var failAt = -1L
    private var failReason: FailReason? = null
    private var failLane = 0
    private var failV = 0.0
    private var failNote: GameNote? = null

    // Particles (structure of arrays)
    private val pCap = when (quality) {
        0 -> 48
        1 -> 160
        else -> 320
    }
    private val px = FloatArray(pCap)
    private val py = FloatArray(pCap)
    private val pvx = FloatArray(pCap)
    private val pvy = FloatArray(pCap)
    private val pLife = FloatArray(pCap)
    private val pMax = FloatArray(pCap)
    private val pSize = FloatArray(pCap)
    private val pColor = IntArray(pCap)
    private var pNext = 0

    // FPS
    private var fpsCount = 0
    private var fpsWindowStart = 0L
    private var fps = 0

    private fun font(context: Context, weight: Int): Typeface =
        runCatching {
            Typeface.Builder(context.assets, "fonts/LexendDeca.ttf")
                .setFontVariationSettings("'wght' $weight")
                .build()
        }.getOrNull() ?: Typeface.DEFAULT_BOLD

    private fun textPaint(tf: Typeface, sp: Float, align: Paint.Align) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = tf
        textSize = sp * density
        textAlign = align
        color = 0xFFFFFFFF.toInt()
    }

    private fun Paint.additive(): Paint {
        if (Build.VERSION.SDK_INT >= 29) blendMode = BlendMode.PLUS else xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        return this
    }

    fun resize(width: Int, height: Int, inset: Float) {
        w = width
        h = height
        topInset = inset
        screen.set(0f, 0f, w.toFloat(), h.toFloat())
        val l = TrackLayout(w, h, s.settings.trackStyle, s.settings.approachMs, lanes)
        layout = l
        sprites?.recycle()
        tileSets.forEach { it.recycle() }
        val geo = Sprites(l.laneW, l.tileH, density)
        sprites = geo
        tileSets = Array(timeline.palettes.size) { TileSprites(geo, timeline.palettes[it].accent, timeline.palettes[it].accent2, density) }
        val hitFrac = (l.hitY - l.planeFar) / (l.planeBottom - l.planeFar)
        edgeRamp?.recycle()
        edgeRamp = geo.makeEdgeRamp(hitFrac, 0.25f / 0.75f)

        discR = min(w * 0.115f, h * 0.058f)
        discCx = w / 2f
        discCy = topInset + 26 * density + discR
        scoreY = discCy + discR + 34 * density
        hudBottom = discCy + discR * 0.6f
        comboY = h * (if (l.flat) 0.42f else 0.44f)
        judgeY = l.hitY - h * 0.085f

        backdrop?.recycle()
        backdrop = if (art != null && quality > 0) Blur.backdrop(art, w, h) else null
        disc?.recycle()
        disc = art?.let { makeDisc(it, (discR * 2).toInt()) }

        // A song that changes color gets the deep tint as a separate additive layer.
        val shadeTop = if (timeline.dynamic) 0xFF000000.toInt() else deep
        shadePaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(ColorUtil.withAlpha(shadeTop, 0.68f), ColorUtil.withAlpha(0xFF000000.toInt(), 0.74f), ColorUtil.withAlpha(0xFF000000.toInt(), 0.9f)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        vignettePaint.shader = RadialGradient(
            w / 2f, h * 0.55f, max(w, h) * 0.75f,
            intArrayOf(0, 0, 0xB0000000.toInt()),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP,
        )
        trackPaint.shader = LinearGradient(
            0f, l.planeFar, 0f, l.planeBottom,
            intArrayOf(0x00000000, 0x7A05040A, 0x9A05040A.toInt()),
            floatArrayOf(0f, (l.hitY - l.planeFar) / (l.planeBottom - l.planeFar), 1f),
            Shader.TileMode.CLAMP,
        )
        sepPaint.shader = LinearGradient(
            0f, l.planeFar, 0f, l.planeBottom,
            intArrayOf(0x00FFFFFF, 0x30FFFFFF, 0x12FFFFFF),
            floatArrayOf(0f, (l.hitY - l.planeFar) / (l.planeBottom - l.planeFar), 1f),
            Shader.TileMode.CLAMP,
        )
        buildStar()
        bakeBackground()
    }

    /**
     * Cover blur + shading + vignette never change during a song, so they are drawn once
     * into a half-resolution bitmap (it is all soft gradients) and blitted each frame.
     */
    private fun bakeBackground() {
        val bw = max(2, w / 2)
        val bh = max(2, h / 2)
        val baked = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(baked)
        c.scale(bw / w.toFloat(), bh / h.toFloat())
        val bd = backdrop
        val dynamic = timeline.dynamic
        if (bd != null) c.drawBitmap(bd, null, screen, backdropPaint) else c.drawColor(if (dynamic) 0xFF000000.toInt() else deep)
        c.drawRect(screen, shadePaint)
        c.drawRect(screen, vignettePaint)
        backdrop?.recycle()
        backdrop = baked
        deepMask?.recycle()
        deepMask = if (dynamic) makeDeepMask(bw, bh, bd != null) else null
    }

    /**
     * How much of the deep color the static background had at each pixel, so it can be
     * added back with whatever deep color is current: the shade gradient (deep fading to
     * black over the top 45 %) or, without a cover, the whole deep fill, under the vignette.
     */
    private fun makeDeepMask(bw: Int, bh: Int, hasArt: Boolean): Bitmap {
        val px = ByteArray(bw * bh)
        val cx = bw / 2f
        val cy = bh * 0.55f
        val rad = max(bw, bh) * 0.75f
        for (y in 0 until bh) {
            val fy = y / (bh - 1f)
            val a = if (fy < 0.45f) 0.68f + 0.06f * fy / 0.45f else 0.74f + 0.16f * (fy - 0.45f) / 0.55f
            val sMix = (fy / 0.45f).coerceAtMost(1f)
            val m = if (hasArt) a * (1f - sMix) else 1f - a * sMix
            for (x in 0 until bw) {
                val r = Math.hypot((x - cx).toDouble(), (y - cy).toDouble()).toFloat() / rad
                val v = if (r < 0.6f) 0f else ((r - 0.6f) / 0.4f).coerceAtMost(1f) * (0xB0 / 255f)
                px[y * bw + x] = (255 * m * (1f - v)).toInt().coerceIn(0, 255).toByte()
            }
        }
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8)
        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(px))
        return bmp
    }

    private fun makeDisc(src: Bitmap, size: Int): Bitmap {
        val out = Bitmap.createBitmap(max(2, size), max(2, size), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val sh = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = size.toFloat() / min(src.width, src.height)
        val m = android.graphics.Matrix()
        m.setScale(scale, scale)
        m.postTranslate((size - src.width * scale) / 2f, (size - src.height * scale) / 2f)
        sh.setLocalMatrix(m)
        p.shader = sh
        c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        return out
    }

    private fun buildStar() {
        starPath.reset()
        val size = 8 * density
        for (i in 0 until 10) {
            val r = (if (i % 2 == 0) 1f else 0.45f) * size
            val a = -PI / 2 + i * PI / 5
            val x = (cos(a) * r).toFloat()
            val y = (sin(a) * r).toFloat()
            if (i == 0) starPath.moveTo(x, y) else starPath.lineTo(x, y)
        }
        starPath.close()
    }

    fun release() {
        sprites?.recycle()
        tileSets.forEach { it.recycle() }
        edgeRamp?.recycle()
        deepMask?.recycle()
        backdrop?.recycle()
        disc?.recycle()
    }

    // ---------------------------------------------------------------- events

    fun onLanePress(lane: Int) {
        if (lane in 0 until lanes) {
            laneGlow[lane] = max(laneGlow[lane], 0.55f)
            laneGlowColor[lane] = accent
        }
    }

    fun onJudge(note: GameNote, j: Judgment, deltaMs: Double, tail: Boolean, nowMs: Long) {
        judge = j
        judgeAt = nowMs
        judgeTiming = if (j == Judgment.PERFECT || j == Judgment.MISS || tail) 0 else if (deltaMs < 0) -1 else 1
        val lane = note.lane
        if (j != Judgment.MISS) {
            laneGlow[lane] = 1f
            laneGlowColor[lane] = j.color
            val b = bursts[burstIdx]
            burstIdx = (burstIdx + 1) % bursts.size
            b.lane = lane
            b.at = nowMs
            b.color = j.color
            b.hold = tail
            spawnHitParticles(lane, j, if (tail) 0.6f else 1f)
        } else {
            laneGlow[lane] = 0.8f
            laneGlowColor[lane] = Judgment.MISS.color
        }
        if (s.engine.combo > comboShown) comboBumpAt = nowMs
        comboShown = s.engine.combo
    }

    fun onCombo(nowMs: Long) {
        val i = shockIdx
        shockIdx = (shockIdx + 1) % shockAt.size
        shockAt[i] = nowMs
        shockColor[i] = accent
    }

    fun onGhostTap(lane: Int) {
        laneGlow[lane] = max(laneGlow[lane], 0.35f)
        laneGlowColor[lane] = 0xFFFFFFFF.toInt()
    }

    fun onFail(reason: FailReason, lane: Int, vMs: Double, note: GameNote?, nowMs: Long) {
        failAt = nowMs
        failReason = reason
        failLane = lane
        failV = vMs
        failNote = note
    }

    private fun spawnHitParticles(lane: Int, j: Judgment, scale: Float) {
        val l = layout ?: return
        if (quality == 0) return
        l.toScreen(l.laneCenter(lane), l.hitY, pt)
        val n = ((if (quality >= 2) 18 else 9) * scale * (if (j == Judgment.PERFECT) 1.2f else 0.8f)).toInt()
        for (k in 0 until n) {
            val a = (-PI / 2 + (rnd.nextFloat() - 0.5f) * PI * 0.95).toFloat()
            val sp = (0.12f + rnd.nextFloat() * 0.5f) * density
            val c = if (rnd.nextFloat() < 0.35f) 0xFFFFFFFF.toInt() else if (rnd.nextBoolean()) j.color else accent
            spawn(pt[0] + (rnd.nextFloat() - 0.5f) * l.laneW * 0.5f, pt[1], cos(a) * sp, sin(a) * sp, 320f + rnd.nextFloat() * 420f, (3f + rnd.nextFloat() * 6f) * density, c)
        }
    }

    private fun spawn(x: Float, y: Float, vx: Float, vy: Float, life: Float, size: Float, color: Int) {
        val i = pNext
        pNext = (pNext + 1) % pCap
        px[i] = x
        py[i] = y
        pvx[i] = vx
        pvy[i] = vy
        pLife[i] = life
        pMax[i] = life
        pSize[i] = size
        pColor[i] = color
    }

    // ---------------------------------------------------------------- frame

    fun draw(c: Canvas, songMs: Double, nowMs: Long, dtMs: Float) {
        val l = layout ?: return
        val sp = sprites ?: return
        val chart = s.songChart
        val tSec = nowMs / 1000f
        frameNowMs = nowMs
        timeline.sample(songMs, frame)
        accent = frame.accent
        accent2 = frame.accent2
        deep = frame.deep
        hitCorePaint.color = ColorUtil.lerp(accent, 0xFFFFFFFF.toInt(), 0.6f)
        // A new part of the song: a wave of the new color from the hit line.
        while (nextCut < cutTimes.size && songMs >= cutTimes[nextCut]) {
            if (songMs - cutTimes[nextCut] < 300 && s.phase == GameSession.Phase.PLAYING) {
                cutAt = nowMs
                onCombo(nowMs)
            }
            nextCut++
        }

        // Beat pulse and loudness drive most of the motion.
        val beats = chart.beatsMs
        var pulse = 0f
        var downbeat = false
        if (beats.isNotEmpty() && songMs >= beats[0]) {
            var lo = 0
            var hi = beats.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (beats[mid] <= songMs) lo = mid else hi = mid - 1
            }
            val since = (songMs - beats[lo]).toFloat()
            downbeat = Math.floorMod(lo - chart.downbeatPhase, 4) == 0
            pulse = exp(-since / 170f) * (if (downbeat) 1f else 0.6f)
        }
        val inten = chart.intensity.getOrElse((songMs / 100).toInt().coerceAtLeast(0)) { 0 } / 100f
        pulse *= 0.45f + 0.55f * inten
        if (s.player.started) {
            s.player.spectrum.bands(s.player.frameForSongTime(songMs), specRaw)
        } else {
            specRaw.fill(0f)
        }
        val decay = dtMs / 380f
        for (i in spec.indices) spec[i] = max(specRaw[i], spec[i] - decay)
        val bass = (spec[0] + spec[1] + spec[2] + spec[3]) / 4f

        // Screen shake right after a failure.
        c.save()
        if (failAt > 0) {
            val age = (nowMs - failAt).toFloat()
            if (age < 350f) {
                val amp = (1f - age / 350f) * 10f * density
                c.translate((rnd.nextFloat() - 0.5f) * amp, (rnd.nextFloat() - 0.5f) * amp)
            }
        }

        drawBackground(c, tSec, pulse, inten, bass)
        drawHud(c, songMs, pulse, bass)
        drawTrack(c, l, sp, songMs, nowMs, pulse, dtMs)
        updateAndDrawParticles(c, sp, dtMs)
        drawShockwaves(c, l, nowMs)
        drawJudgment(c, nowMs)
        drawCombo(c, nowMs)
        if (s.phase == GameSession.Phase.READY) drawStart(c, l, tSec)
        drawFailOverlay(c, nowMs)
        c.restore()

        if (s.settings.showFps) {
            fpsCount++
            if (nowMs - fpsWindowStart >= 1000) {
                fps = fpsCount
                fpsCount = 0
                fpsWindowStart = nowMs
            }
            val n = formatInt(fps, false)
            c.drawText(chars, 0, n, 12 * density, h - 14 * density, fpsPaint)
        }
    }

    private fun drawBackground(c: Canvas, t: Float, pulse: Float, inten: Float, bass: Float) {
        val bd = backdrop
        if (bd != null) c.drawBitmap(bd, null, screen, backdropPaint) else c.drawColor(deep)
        val dm = deepMask
        if (dm != null) {
            deepPaint.color = deep
            c.drawBitmap(dm, null, screen, deepPaint)
        }
        val sp = sprites
        if (quality >= 1 && sp != null) {
            val n = if (quality >= 2) 3 else 2
            val strength = (0.45f + 0.4f * inten + 0.5f * pulse).coerceIn(0f, 1f) * 0.22f
            for (i in 0 until n) {
                val cx = w * (0.5f + 0.42f * sin(t * 0.11f + i * 2.1f))
                val cy = h * (0.38f + 0.28f * sin(t * 0.07f + i * 1.7f))
                val r = w * (0.62f + 0.12f * sin(t * 0.13f + i) + 0.1f * bass)
                val color = when (i) {
                    0 -> accent
                    1 -> accent2
                    else -> ColorUtil.lerp(accent, 0xFFFFFFFF.toInt(), 0.25f)
                }
                auroraPaint.color = ColorUtil.withAlpha(color, strength)
                rect.set(cx - r, cy - r, cx + r, cy + r)
                c.drawBitmap(sp.auroraDot, null, rect, auroraPaint)
            }
        }
        if (pulse > 0.01f) {
            flashPaint.color = ColorUtil.withAlpha(accent, pulse * 0.07f)
            c.drawRect(screen, flashPaint)
        }
        val sinceCut = (frameNowMs - cutAt).toFloat()
        if (sinceCut in 0f..600f) {
            val k = 1f - sinceCut / 600f
            flashPaint.color = ColorUtil.withAlpha(accent, 0.16f * k * k)
            c.drawRect(screen, flashPaint)
        }
    }

    private fun drawHud(c: Canvas, songMs: Double, pulse: Float, bass: Float) {
        val sp = sprites ?: return
        val r = discR
        // Halo behind the disc.
        glowPaint.color = ColorUtil.withAlpha(accent, 0.35f + 0.45f * pulse)
        val gr = r * (2.3f + 0.4f * pulse)
        rect.set(discCx - gr, discCy - gr, discCx + gr, discCy + gr)
        c.drawBitmap(sp.glowDot, null, rect, glowPaint)

        // Spectrum bars, mirrored on both sides of the disc.
        val bars = 16
        val startX = r + 16 * density
        val endX = w / 2f - 14 * density
        val step = (endX - startX) / bars
        barPaint.strokeWidth = max(2f * density, step * 0.42f)
        val maxH = r * 0.95f
        for (i in 0 until bars) {
            val v = spec[min(spec.size - 1, i)].pow(1.35f)
            val hh = 2.5f * density + maxH * v
            val a = 0.35f + 0.65f * v
            barPaint.color = ColorUtil.withAlpha(ColorUtil.lerp(accent, accent2, i / bars.toFloat()), a)
            val xl = discCx - startX - i * step
            val xr = discCx + startX + i * step
            c.drawLine(xl, discCy - hh, xl, discCy + hh, barPaint)
            c.drawLine(xr, discCy - hh, xr, discCy + hh, barPaint)
        }

        // Album disc, breathing with the beat.
        val scale = 1f + 0.035f * pulse + 0.03f * bass
        val d = disc
        c.save()
        c.scale(scale, scale, discCx, discCy)
        if (d != null) {
            rect.set(discCx - r, discCy - r, discCx + r, discCy + r)
            c.drawBitmap(d, null, rect, discPaint)
        } else {
            discPaint.shader = null
            discPaint.color = ColorUtil.lerp(deep, accent, 0.35f)
            c.drawCircle(discCx, discCy, r, discPaint)
            discPaint.color = accent
            c.drawCircle(discCx, discCy, r * 0.18f, discPaint)
        }
        c.restore()
        // Progress ring.
        val progress = (songMs / max(1, s.songChart.durationMs)).toFloat().coerceIn(0f, 1f)
        val rr = r * scale + 7 * density
        rect.set(discCx - rr, discCy - rr, discCx + rr, discCy + rr)
        ringPaint.strokeWidth = 3f * density
        ringPaint.color = 0x2EFFFFFF
        c.drawArc(rect, 0f, 360f, false, ringPaint)
        ringPaint.color = accent
        if (progress > 0f) c.drawArc(rect, -90f, 360f * progress, false, ringPaint)

        // Score / tile counter.
        val e = s.engine
        if (mode == GameMode.CLASSIC) {
            val n = formatInt(e.tilesHit, false)
            tilesPaint.setShadowLayer(12 * density, 0f, 0f, ColorUtil.withAlpha(accent, 0.8f))
            c.drawText(chars, 0, n, discCx, scoreY + 12 * density, tilesPaint)
            drawClassicStars(c, progress)
        } else {
            displayScore += (e.score - displayScore) * 0.25
            val n = formatInt((displayScore + 0.5).toInt(), true)
            scorePaint.setShadowLayer(8 * density, 0f, 0f, ColorUtil.withAlpha(accent, 0.6f))
            c.drawText(chars, 0, n, discCx, scoreY, scorePaint)
            if (!s.settings.noFail) {
                val bw = w * 0.34f
                val by = scoreY + 12 * density
                linePaint.color = 0x33FFFFFF
                rect.set(discCx - bw / 2, by, discCx + bw / 2, by + 4 * density)
                c.drawRoundRect(rect, 2 * density, 2 * density, linePaint)
                linePaint.color = ColorUtil.lerp(Judgment.MISS.color, accent, ((e.life - 0.2f) / 0.6f).coerceIn(0f, 1f))
                rect.right = rect.left + bw * e.life
                c.drawRoundRect(rect, 2 * density, 2 * density, linePaint)
            }
        }
    }

    private fun drawClassicStars(c: Canvas, progress: Float) {
        val y = topInset + 10 * density
        val x0 = 18 * density
        val x1 = w - 18 * density
        linePaint.color = 0x33FFFFFF
        rect.set(x0, y - 1.5f * density, x1, y + 1.5f * density)
        c.drawRoundRect(rect, density, density, linePaint)
        linePaint.color = accent
        rect.right = x0 + (x1 - x0) * progress
        c.drawRoundRect(rect, density, density, linePaint)
        for (k in 1..3) {
            val fx = x0 + (x1 - x0) * k / 3f - if (k == 3) 8 * density else 0f
            val lit = progress >= k / 3f - 0.001f
            starPaint.style = if (lit) Paint.Style.FILL else Paint.Style.STROKE
            starPaint.strokeWidth = 1.5f * density
            starPaint.color = if (lit) 0xFFFFD66E.toInt() else 0x88FFFFFF.toInt()
            if (lit) starPaint.setShadowLayer(8 * density, 0f, 0f, 0xFFFFD66E.toInt()) else starPaint.clearShadowLayer()
            c.save()
            c.translate(fx, y)
            c.drawPath(starPath, starPaint)
            c.restore()
        }
    }

    private fun drawTrack(c: Canvas, l: TrackLayout, sp: Sprites, songMs: Double, nowMs: Long, pulse: Float, dtMs: Float) {
        c.save()
        c.concat(l.matrix)
        val far = l.planeFar - l.tileH
        c.drawRect(0f, far, l.trackW, l.planeBottom, trackPaint)
        val sepW = max(1f, 1.2f * density)
        for (lane in 1 until lanes) {
            val x = lane * l.laneW
            c.drawRect(x - sepW / 2, far, x + sepW / 2, l.planeBottom, sepPaint)
        }
        val ramp = edgeRamp
        if (ramp != null) {
            edgePaint.color = ColorUtil.withAlpha(accent, 0.75f * (0.55f + 0.45f * pulse))
            val ew = 2.2f * density
            rect.set(0f, l.planeFar, ew, l.planeBottom)
            c.drawBitmap(ramp, null, rect, edgePaint)
            rect.set(l.trackW - ew, l.planeFar, l.trackW, l.planeBottom)
            c.drawBitmap(ramp, null, rect, edgePaint)
        }

        // Beat lines scrolling with the tiles (brighter on bar lines).
        val beats = s.songChart.beatsMs
        if (beats.isNotEmpty()) {
            var lo = 0
            var hi = beats.size
            val from = songMs - 250
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (beats[mid] < from) lo = mid + 1 else hi = mid
            }
            var i = lo
            while (i < beats.size && beats[i] <= songMs + l.approachMs) {
                val v = beats[i] - songMs
                val y = l.planeY(v)
                val bar = Math.floorMod(i - s.songChart.downbeatPhase, 4) == 0
                val fade = (1f - (v / l.approachMs).toFloat()).coerceIn(0f, 1f)
                beatPaint.color = ColorUtil.withAlpha(0xFFFFFFFF.toInt(), (if (bar) 0.16f else 0.06f) * fade)
                val bh = (if (bar) 2.2f else 1.2f) * density
                c.drawRect(0f, y - bh / 2, l.trackW, y + bh / 2, beatPaint)
                i++
            }
        }

        // Lane light beams (press feedback and hits).
        val beamH = (l.hitY - l.planeFar) * 0.42f
        for (lane in 0 until lanes) {
            if (s.laneDown[lane] > 0) laneGlow[lane] = max(laneGlow[lane], 0.5f)
            val g = laneGlow[lane]
            if (g > 0.01f) {
                glowPaint.color = ColorUtil.withAlpha(laneGlowColor[lane], g * 0.55f)
                rect.set(lane * l.laneW, l.hitY - beamH, (lane + 1) * l.laneW, l.hitY + sp.pad)
                c.drawBitmap(sp.beam, null, rect, glowPaint)
            }
            laneGlow[lane] = max(0f, g - dtMs / 260f)
        }

        // Hit line.
        glowPaint.color = ColorUtil.withAlpha(accent, 0.55f + 0.45f * pulse)
        val gh = 34 * density
        rect.set(-sp.pad, l.hitY - gh / 2, l.trackW + sp.pad, l.hitY + gh / 2)
        c.drawBitmap(sp.glowLine, null, rect, glowPaint)
        hitCorePaint.alpha = 230
        c.drawRect(0f, l.hitY - 1.2f * density, l.trackW, l.hitY + 1.2f * density, hitCorePaint)

        drawNotes(c, l, sp, songMs, nowMs)
        drawBursts(c, l, sp, nowMs)
        drawFailTile(c, l, sp, nowMs)
        c.restore()
    }

    private fun approachAlpha(l: TrackLayout, v: Double): Float {
        val a = l.approachMs.toDouble()
        return ((a - v) / (a * 0.16)).toFloat().coerceIn(0f, 1f)
    }

    private fun spriteOf(set: TileSprites, kind: Int): Bitmap = when (kind) {
        HOLD_HEAD -> set.holdHead
        FLASH -> set.tileFlash
        HOLD_BODY -> set.holdBody
        HOLD_BODY_ACTIVE -> set.holdBodyActive
        else -> set.tile
    }

    /** Draws a colored sprite into [rect]; while the song changes color, the next color fades in on top. */
    private fun drawColored(c: Canvas, kind: Int, alpha: Float) {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        tilePaint.alpha = a
        c.drawBitmap(spriteOf(tileSets[frame.from], kind), null, rect, tilePaint)
        if (frame.to != frame.from && frame.mix > 0.004f) {
            tilePaint.alpha = (a * frame.mix).toInt()
            c.drawBitmap(spriteOf(tileSets[frame.to], kind), null, rect, tilePaint)
        }
    }

    private fun drawTileSprite(c: Canvas, kind: Int, sp: Sprites, x: Float, yBottom: Float, alpha: Float, scale: Float = 1f) {
        val cx = x + sp.tileW / 2
        val cy = yBottom - sp.tileHeight / 2
        val hw = (sp.tileW / 2 + sp.pad) * scale
        val hh = (sp.tileHeight / 2 + sp.pad) * scale
        rect.set(cx - hw, cy - hh, cx + hw, cy + hh)
        drawColored(c, kind, alpha)
    }

    private fun drawNotes(c: Canvas, l: TrackLayout, sp: Sprites, songMs: Double, nowMs: Long) {
        val notes = s.engine.notes
        if (notes.isEmpty()) return
        // First note that can still be visible (holds may start a few seconds back).
        var lo = 0
        var hi = notes.size
        val from = songMs - 6000
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (notes[mid].timeMs < from) lo = mid + 1 else hi = mid
        }
        val limit = songMs + l.approachMs + l.tileLenMs
        val minV = l.vAt(l.planeBottom) - l.tileLenMs
        // Pass 1: hold bodies (under everything). Pass 2: tiles.
        for (pass in 0..1) {
            var i = lo
            while (i < notes.size) {
                val n = notes[i]
                if (n.timeMs > limit) break
                i++
                val v = n.timeMs - songMs
                val vEnd = n.endMs - songMs
                if (vEnd < minV && v < minV) continue
                val x = n.lane * l.laneW + sp.gap
                if (pass == 0) {
                    if (!n.isHold) continue
                    when (n.state) {
                        NoteState.PENDING -> drawHoldBody(c, l, sp, x, v, vEnd, false, approachAlpha(l, v))
                        NoteState.HOLDING -> drawHoldBody(c, l, sp, x, 0.0, vEnd, true, 1f)
                        NoteState.DONE -> if (n.tailBroken || n.judgment == Judgment.MISS) {
                            drawHoldBody(c, l, sp, x, max(v, minV), vEnd, false, 0.35f)
                        }
                    }
                    continue
                }
                when (n.state) {
                    NoteState.PENDING -> {
                        val a = approachAlpha(l, v)
                        if (n.isDouble && i < notes.size && notes[i].timeMs == n.timeMs && notes[i].state == NoteState.PENDING) {
                            drawDoubleLink(c, l, sp, n, notes[i], v, a)
                        }
                        drawTileSprite(c, if (n.isHold) HOLD_HEAD else TILE, sp, x, l.planeY(v), a)
                    }
                    NoteState.HOLDING -> {
                        drawTileSprite(c, HOLD_HEAD, sp, x, l.hitY + sp.tileHeight * 0.15f, 1f)
                        val wob = 0.45f + 0.25f * sin(nowMs / 60f)
                        drawTileSprite(c, FLASH, sp, x, l.hitY + sp.tileHeight * 0.15f, wob)
                        if (quality >= 1 && rnd.nextFloat() < 0.7f) {
                            l.toScreen(l.laneCenter(n.lane), l.hitY, pt)
                            spawn(pt[0] + (rnd.nextFloat() - 0.5f) * l.laneW * 0.4f, pt[1], (rnd.nextFloat() - 0.5f) * 0.15f * density, -(0.2f + rnd.nextFloat() * 0.35f) * density, 300f + rnd.nextFloat() * 250f, (2.5f + rnd.nextFloat() * 4f) * density, if (rnd.nextBoolean()) accent else 0xFFFFFFFF.toInt())
                        }
                    }
                    NoteState.DONE -> {
                        if (n.judgment == Judgment.MISS) {
                            if (v > minV) {
                                redPaint.color = ColorUtil.withAlpha(Judgment.MISS.color, 0.5f)
                                drawTileSprite(c, if (n.isHold) HOLD_HEAD else TILE, sp, x, l.planeY(v), 0.45f)
                                rect.set(x, l.planeY(v) - sp.tileHeight, x + sp.tileW, l.planeY(v))
                                c.drawRoundRect(rect, sp.tileW * 0.12f, sp.tileW * 0.12f, redPaint)
                            }
                        } else if (mode == GameMode.CLASSIC && !n.isHold) {
                            // Piano Tiles feel: a tapped tile stays, lit, and keeps scrolling away.
                            val age = (nowMs - n.effectAt).toFloat()
                            if (age < 600f && v > minV) drawTileSprite(c, FLASH, sp, x, l.planeY(v), 0.4f * (1f - age / 600f))
                        }
                    }
                }
            }
        }
    }

    private fun drawHoldBody(c: Canvas, l: TrackLayout, sp: Sprites, x: Float, vStart: Double, vEnd: Double, active: Boolean, alpha: Float) {
        val yStart = l.planeY(vStart) - sp.tileHeight * 0.5f
        val yEnd = l.planeY(vEnd)
        if (yEnd >= yStart) return
        rect.set(x - sp.pad, yEnd, x + sp.tileW + sp.pad, yStart)
        drawColored(c, if (active) HOLD_BODY_ACTIVE else HOLD_BODY, alpha)
        // End cap.
        capPaint.color = ColorUtil.withAlpha(if (active) 0xFFFFFFFF.toInt() else accent, alpha)
        val capW = sp.tileW * 0.5f
        val capH = 5f * density
        rect2.set(x + sp.tileW / 2 - capW / 2, yEnd - capH / 2, x + sp.tileW / 2 + capW / 2, yEnd + capH / 2)
        c.drawRoundRect(rect2, capH, capH, capPaint)
    }

    private fun drawDoubleLink(c: Canvas, l: TrackLayout, sp: Sprites, a: GameNote, b: GameNote, v: Double, alpha: Float) {
        val y = l.planeY(v) - sp.tileHeight / 2
        val x0 = min(a.lane, b.lane) * l.laneW + l.laneW / 2
        val x1 = max(a.lane, b.lane) * l.laneW + l.laneW / 2
        glowPaint.color = ColorUtil.withAlpha(accent, 0.7f * alpha)
        val gh = 14 * density
        rect.set(x0, y - gh / 2, x1, y + gh / 2)
        c.drawBitmap(sp.glowLine, null, rect, glowPaint)
    }

    private fun drawBursts(c: Canvas, l: TrackLayout, sp: Sprites, nowMs: Long) {
        for (b in bursts) {
            val age = (nowMs - b.at).toFloat()
            if (age < 0f || age > 260f) continue
            val t = age / 260f
            val x = b.lane * l.laneW + sp.gap
            val scale = 1f + 0.35f * t
            drawTileSprite(c, FLASH, sp, x, l.hitY + sp.tileHeight * 0.1f, (1f - t).pow(1.5f), scale)
            // Ring of light expanding from the tile.
            ringPaint.color = ColorUtil.withAlpha(b.color, (1f - t) * 0.9f)
            ringPaint.strokeWidth = (3f - 2f * t) * density
            val cx = x + sp.tileW / 2
            val cy = l.hitY - sp.tileHeight * 0.4f
            val rw = sp.tileW * (0.55f + 0.6f * t)
            val rh = sp.tileHeight * (0.55f + 0.6f * t)
            rect.set(cx - rw, cy - rh, cx + rw, cy + rh)
            c.drawRoundRect(rect, rw * 0.3f, rw * 0.3f, ringPaint)
        }
    }

    private fun drawFailTile(c: Canvas, l: TrackLayout, sp: Sprites, nowMs: Long) {
        if (failAt < 0) return
        val reason = failReason ?: return
        if (reason == FailReason.NO_LIFE) return
        val age = nowMs - failAt
        val blink = (age / 150) % 2 == 0L
        redPaint.color = ColorUtil.withAlpha(Judgment.MISS.color, if (blink) 0.95f else 0.45f)
        val x = failLane * l.laneW + sp.gap
        val yBottom = if (reason == FailReason.WRONG_TILE) l.planeY(failV) + sp.tileHeight / 2 else l.planeY(failV)
        rect.set(x, yBottom - sp.tileHeight, x + sp.tileW, yBottom)
        val rad = sp.tileW * 0.14f
        c.drawRoundRect(rect, rad, rad, redPaint)
    }

    private fun updateAndDrawParticles(c: Canvas, sp: Sprites, dtMs: Float) {
        val g = 0.0011f * density
        for (i in 0 until pCap) {
            if (pLife[i] <= 0f) continue
            pLife[i] -= dtMs
            if (pLife[i] <= 0f) continue
            pvy[i] += g * dtMs
            px[i] += pvx[i] * dtMs
            py[i] += pvy[i] * dtMs
            val a = pLife[i] / pMax[i]
            val size = pSize[i] * (0.5f + 0.5f * a)
            glowPaint.color = ColorUtil.withAlpha(pColor[i], a)
            rect.set(px[i] - size, py[i] - size, px[i] + size, py[i] + size)
            c.drawBitmap(sp.glowDot, null, rect, glowPaint)
        }
    }

    private fun drawShockwaves(c: Canvas, l: TrackLayout, nowMs: Long) {
        for (k in shockAt.indices) {
            val age = (nowMs - shockAt[k]).toFloat()
            if (age < 0f || age > 700f) continue
            val t = age / 700f
            ringPaint.color = ColorUtil.withAlpha(shockColor[k], (1f - t) * 0.8f)
            ringPaint.strokeWidth = (5f - 4f * t) * density
            val r = w * (0.08f + 0.75f * t.pow(0.6f))
            l.toScreen(l.trackW / 2, l.hitY, pt2)
            c.drawCircle(pt2[0], pt2[1], r, ringPaint)
        }
    }

    private fun drawJudgment(c: Canvas, nowMs: Long) {
        val j = judge ?: return
        val age = (nowMs - judgeAt).toFloat()
        if (age > 700f) return
        val scale = if (age < 90f) 1.3f - 0.3f * (age / 90f) else 1f
        val alpha = if (age > 480f) 1f - (age - 480f) / 220f else 1f
        val cx = w / 2f
        judgePaint.color = ColorUtil.withAlpha(j.color, alpha)
        judgePaint.setShadowLayer(16 * density, 0f, 0f, ColorUtil.withAlpha(j.color, alpha * 0.9f))
        c.save()
        c.scale(scale, scale, cx, judgeY)
        c.drawText(j.label, cx, judgeY, judgePaint)
        val tw = judgePaint.measureText(j.label) / 2
        linePaint.color = ColorUtil.withAlpha(j.color, alpha * 0.8f)
        val ly = judgeY - judgePaint.textSize * 0.36f
        val lw = 26 * density
        val gap = 12 * density
        rect.set(cx - tw - gap - lw, ly - density, cx - tw - gap, ly + density)
        c.drawRect(rect, linePaint)
        rect.set(cx + tw + gap, ly - density, cx + tw + gap + lw, ly + density)
        c.drawRect(rect, linePaint)
        if (judgeTiming != 0 && s.settings.earlyLate) {
            subPaint.color = ColorUtil.withAlpha(0xFFFFFFFF.toInt(), alpha * 0.75f)
            c.drawText(if (judgeTiming < 0) "TEMPRANO" else "TARDE", cx, judgeY + 20 * density, subPaint)
        }
        c.restore()
    }

    private fun drawCombo(c: Canvas, nowMs: Long) {
        val combo = s.engine.combo
        // Classic mode already shows the tile counter under the disc.
        if (combo < 5 || s.phase == GameSession.Phase.READY || mode == GameMode.CLASSIC) return
        val age = (nowMs - comboBumpAt).toFloat()
        val scale = if (age < 120f) 1.12f - 0.12f * (age / 120f) else 1f
        val n = formatInt(combo, false)
        val cx = w / 2f
        comboPaint.color = 0xB8FFFFFF.toInt()
        comboPaint.setShadowLayer(18 * density, 0f, 0f, ColorUtil.withAlpha(accent, 0.85f))
        c.save()
        c.scale(scale, scale, cx, comboY)
        c.drawText(chars, 0, n, cx, comboY, comboPaint)
        comboLabelPaint.color = ColorUtil.withAlpha(accent, 0.9f)
        c.drawText("COMBO", cx, comboY + 20 * density, comboLabelPaint)
        c.restore()
    }

    private fun drawStart(c: Canvas, l: TrackLayout, t: Float) {
        val sp = sprites ?: return
        val breathe = 0.5f + 0.5f * sin(t * 4f)
        c.save()
        c.concat(l.matrix)
        val x0 = l.laneW + sp.gap
        val x1 = 3 * l.laneW - sp.gap
        val bottom = l.hitY - sp.tileHeight * 0.15f
        val top = bottom - sp.tileHeight * 1.7f
        glowPaint.color = ColorUtil.withAlpha(accent, 0.5f + 0.4f * breathe)
        rect.set(x0 - sp.pad * 2, top - sp.pad * 2, x1 + sp.pad * 2, bottom + sp.pad * 2)
        c.drawBitmap(sp.glowDot, null, rect, glowPaint)
        rect.set(x0, top, x1, bottom)
        val rad = sp.tileW * 0.16f
        capPaint.color = 0xF00C0B12.toInt()
        c.drawRoundRect(rect, rad, rad, capPaint)
        ringPaint.color = accent
        ringPaint.strokeWidth = 2.5f * density
        c.drawRoundRect(rect, rad, rad, ringPaint)
        val cx = (x0 + x1) / 2
        startBigPaint.setShadowLayer(14 * density, 0f, 0f, accent)
        c.drawText("TOCA", cx, (top + bottom) / 2 + startBigPaint.textSize * 0.2f, startBigPaint)
        startSmallPaint.color = ColorUtil.withAlpha(0xFFFFFFFF.toInt(), 0.8f)
        c.drawText("PARA EMPEZAR", cx, (top + bottom) / 2 + startBigPaint.textSize * 0.2f + 20 * density, startSmallPaint)
        c.restore()
    }

    private fun drawFailOverlay(c: Canvas, nowMs: Long) {
        if (failAt < 0) return
        val age = (nowMs - failAt).toFloat()
        val a = if (age < 120f) age / 120f * 0.35f else max(0f, 0.35f - (age - 120f) / 900f * 0.2f)
        flashPaint.color = ColorUtil.withAlpha(Judgment.MISS.color, a * 0.6f)
        c.drawRect(screen, flashPaint)
    }

    private companion object {
        const val TILE = 0
        const val HOLD_HEAD = 1
        const val FLASH = 2
        const val HOLD_BODY = 3
        const val HOLD_BODY_ACTIVE = 4
    }

    /** Writes [v] into [chars] (grouped by thousands with thin spaces if [group]); returns length. */
    private fun formatInt(v: Int, group: Boolean): Int {
        var x = max(0, v)
        val tmp = fmtTmp
        var n = 0
        var digits = 0
        do {
            if (group && digits > 0 && digits % 3 == 0) tmp[n++] = ' '
            tmp[n++] = ('0' + x % 10)
            x /= 10
            digits++
        } while (x > 0)
        for (k in 0 until n) chars[k] = tmp[n - 1 - k]
        return n
    }
}
