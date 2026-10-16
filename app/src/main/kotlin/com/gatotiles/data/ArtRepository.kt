package com.gatotiles.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.palette.graphics.Palette
import com.gatotiles.util.ArtColors
import com.gatotiles.util.ColorUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/** Album covers (embedded art) with an in-memory cache, plus the colors extracted from them. */
class ArtRepository(private val context: Context) {
    private val cache = object : LruCache<String, Bitmap>(cacheKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = max(1, value.byteCount / 1024)
    }
    private val missing = ConcurrentHashMap.newKeySet<Long>()
    private val colorCache = ConcurrentHashMap<String, ArtColors>()
    private val paletteCache = ConcurrentHashMap<String, CoverPalette>()

    private fun cacheKb(): Int = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceIn(8 * 1024, 64 * 1024)

    fun cached(song: Song, size: Int): Bitmap? = cache.get("${song.id}@$size")

    fun cachedColors(song: Song): ArtColors? = colorCache[song.key]

    suspend fun load(song: Song, size: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "${song.id}@$size"
        cache.get(key)?.let { return@withContext it }
        if (song.id in missing) return@withContext null
        val bmp = runCatching { loadUncached(song, size) }.getOrNull()
        if (bmp != null) cache.put(key, bmp) else missing.add(song.id)
        bmp
    }

    private fun loadUncached(song: Song, size: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                return context.contentResolver.loadThumbnail(song.uri, Size(size, size), null)
            } catch (_: Exception) {
                // No embedded art or unsupported: fall through to the retriever.
            }
        }
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, song.uri)
            val bytes = mmr.embeddedPicture ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { mmr.release() }
        }
    }

    suspend fun colors(song: Song): ArtColors {
        colorCache[song.key]?.let { return it }
        val bmp = load(song, 256)
        val colors = withContext(Dispatchers.Default) { if (bmp == null) ArtColors.Default else extract(bmp) }
        colorCache[song.key] = colors
        return colors
    }

    private fun extract(bmp: Bitmap): ArtColors {
        val p = Palette.from(bmp).maximumColorCount(16).generate()
        val swatch = p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.darkVibrantSwatch ?: p.mutedSwatch ?: p.dominantSwatch
        return if (swatch == null) ArtColors.Default else ArtColors.fromSeed(swatch.rgb)
    }

    /** Every color of the cover (Namida-style palette), plus mixes and the automatic sequence. */
    suspend fun palette(song: Song): CoverPalette {
        paletteCache[song.key]?.let { return it }
        val seed = colors(song).seed
        val bmp = load(song, 256)
        val pal = withContext(Dispatchers.Default) {
            if (bmp == null) CoverPalette.Empty else extractPalette(bmp, seed)
        }
        paletteCache[song.key] = pal
        return pal
    }

    private fun extractPalette(bmp: Bitmap, seed: Int): CoverPalette {
        val p = Palette.from(bmp).maximumColorCount(32).clearFilters().generate()
        val swatches = p.swatches.sortedByDescending { it.population }
        // Drop near duplicates (closer than a just noticeable step in Oklab).
        val kept = ArrayList<Palette.Swatch>()
        val labs = ArrayList<DoubleArray>()
        for (sw in swatches) {
            val lab = ColorUtil.toOklab(sw.rgb)
            if (labs.none { dist(it, lab) < 0.035 }) {
                kept.add(sw)
                labs.add(lab)
            }
            if (kept.size >= 28) break
        }
        val colors = kept.map { it.rgb }
        val weights = kept.map { it.population.toDouble() }
        val lch = colors.map { ColorUtil.toOklch(it) }
        fun mixOf(filter: (Int) -> Boolean): Int? {
            val idx = colors.indices.filter(filter)
            if (idx.size < 2) return null
            return ColorUtil.mix(idx.map { colors[it] }, idx.map { weights[it] })
        }
        val vibrant = listOfNotNull(p.vibrantSwatch, p.lightVibrantSwatch, p.darkVibrantSwatch).map { it.rgb }
        val mixes = listOfNotNull(
            mixOf { true },
            mixOf { it < 3 },
            if (vibrant.size >= 2) ColorUtil.mix(vibrant) else null,
            mixOf { lch[it].c < 0.08 },
            mixOf { lch[it].l > 0.62 },
            mixOf { lch[it].l < 0.42 },
            mixOf { lch[it].c >= 0.08 },
        )
        val mixLabs = ArrayList<DoubleArray>()
        val uniqueMixes = mixes.filter { m ->
            val lab = ColorUtil.toOklab(m)
            val fresh = mixLabs.none { dist(it, lab) < 0.03 }
            if (fresh) mixLabs.add(lab)
            fresh
        }.take(6)
        return CoverPalette(seed, colors, CoverPalette.autoSequence(seed, colors), uniqueMixes)
    }

    private fun dist(a: DoubleArray, b: DoubleArray): Double {
        val dl = a[0] - b[0]
        val da = a[1] - b[1]
        val db = a[2] - b[2]
        return Math.sqrt(dl * dl + da * da + db * db)
    }
}
