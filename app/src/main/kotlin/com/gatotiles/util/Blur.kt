package com.gatotiles.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

object Blur {
    /**
     * Small, heavily blurred center-crop of [src] with the aspect ratio [w]:[h]. Meant to be
     * drawn scaled up with bilinear filtering (the blur hides the low resolution).
     */
    fun backdrop(src: Bitmap, w: Int, h: Int, smallWidth: Int = 72, radius: Int = 6): Bitmap {
        val sw = smallWidth
        val sh = max(1, (smallWidth.toLong() * h / max(1, w)).toInt())
        val out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val srcAspect = src.width.toFloat() / src.height
        val dstAspect = sw.toFloat() / sh
        val crop = if (srcAspect > dstAspect) {
            val cw = (src.height * dstAspect).toInt()
            val x = (src.width - cw) / 2
            Rect(x, 0, x + cw, src.height)
        } else {
            val ch = (src.width / dstAspect).toInt()
            val y = (src.height - ch) / 2
            Rect(0, y, src.width, y + ch)
        }
        canvas.drawBitmap(src, crop, Rect(0, 0, sw, sh), Paint(Paint.FILTER_BITMAP_FLAG))
        val px = IntArray(sw * sh)
        out.getPixels(px, 0, sw, 0, 0, sw, sh)
        repeat(3) { boxBlur(px, sw, sh, radius) }
        out.setPixels(px, 0, sw, 0, 0, sw, sh)
        return out
    }

    /** Separable box blur (one horizontal and one vertical pass) with running sums. */
    fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        pass(px, tmp, w, h, r, horizontal = true)
        pass(tmp, px, w, h, r, horizontal = false)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            var sa = 0
            var sr = 0
            var sg = 0
            var sb = 0
            fun idx(i: Int): Int {
                val c = min(len - 1, max(0, i))
                return if (horizontal) line * w + c else c * w + line
            }
            for (i in -r..r) {
                val p = src[idx(i)]
                sa += p ushr 24
                sr += (p shr 16) and 0xFF
                sg += (p shr 8) and 0xFF
                sb += p and 0xFF
            }
            for (i in 0 until len) {
                dst[idx(i)] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val pOut = src[idx(i - r)]
                val pIn = src[idx(i + r + 1)]
                sa += (pIn ushr 24) - (pOut ushr 24)
                sr += ((pIn shr 16) and 0xFF) - ((pOut shr 16) and 0xFF)
                sg += ((pIn shr 8) and 0xFF) - ((pOut shr 8) and 0xFF)
                sb += (pIn and 0xFF) - (pOut and 0xFF)
            }
        }
    }
}
