package com.gatotiles.data

import android.content.Context
import com.gatotiles.util.ArtColors
import com.gatotiles.util.ColorUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/** How the game colors move during a song. */
@Serializable
enum class ColorFlow(val label: String, val description: String) {
    FIXED("Fijo", "Un solo color durante toda la canción."),
    SECTIONS("Por partes", "Cambia de color cuando cambia la música: estrofa, estribillo, puente… Las partes que se repiten vuelven a su color."),
    GRADUAL("Gradual", "Se va transformando poco a poco, de un color al siguiente, mientras avanza la canción."),
}

/** The player's color choices for one song. */
@Serializable
data class SongColorConfig(
    /** null = the default from the settings. */
    val flow: ColorFlow? = null,
    /** Colors in the order they are used; empty = automatic (from the cover). */
    val colors: List<Int> = emptyList(),
    /** Colors the player added to the palette with "+". */
    val custom: List<Int> = emptyList(),
    /** Cover colors the player removed from the palette. */
    val hidden: List<Int> = emptyList(),
) {
    val isDefault: Boolean get() = this == SongColorConfig()
}

/** Colors extracted from a cover: [swatches] for the palette, [auto] for the automatic sequence. */
data class CoverPalette(val seed: Int, val swatches: List<Int>, val auto: List<Int>, val mixes: List<Int>) {
    companion object {
        val Empty = CoverPalette(ArtColors.DEFAULT_SEED, emptyList(), autoSequence(ArtColors.DEFAULT_SEED, emptyList()), emptyList())

        /**
         * Up to 4 clearly different, colorful swatches starting with [seed]; covers with
         * few colors are completed with neighbouring hues of the seed.
         */
        fun autoSequence(seed: Int, swatches: List<Int>): List<Int> {
            val out = arrayListOf(seed)
            val hues = arrayListOf(ColorUtil.toOklch(seed).h)
            for (c in swatches) {
                if (out.size >= 4) break
                val o = ColorUtil.toOklch(c)
                if (o.c < 0.06 || o.l < 0.25) continue
                if (hues.all { ColorUtil.hueDistance(it, o.h) >= 40 }) {
                    out.add(c)
                    hues.add(o.h)
                }
            }
            if (out.size < 3) {
                val o = ColorUtil.toOklch(seed)
                val c = if (o.c < 0.04) 0.14 else o.c.coerceAtLeast(0.12)
                val h = if (o.c < 0.02) 285.0 else o.h
                for (dh in listOf(55.0, -55.0, 150.0)) {
                    if (out.size >= 3) break
                    val nh = (h + dh + 360) % 360
                    if (hues.all { ColorUtil.hueDistance(it, nh) >= 35 }) {
                        out.add(ColorUtil.fromOklch(0.7, c, nh))
                        hues.add(nh)
                    }
                }
            }
            return out
        }
    }
}

/** What a song will actually use in the game. */
data class ResolvedColors(val flow: ColorFlow, val seeds: List<Int>, val palettes: List<ArtColors>, val chosen: Boolean) {
    val main: ArtColors get() = palettes.first()

    companion object {
        const val MAX_COLORS = 6

        fun resolve(cfg: SongColorConfig, defaultFlow: ColorFlow, cover: CoverPalette, coverColors: ArtColors): ResolvedColors {
            val flow = cfg.flow ?: defaultFlow
            val chosen = cfg.colors.isNotEmpty()
            val seeds = when {
                chosen && flow == ColorFlow.FIXED -> cfg.colors.take(1)
                chosen -> cfg.colors.take(MAX_COLORS)
                flow == ColorFlow.FIXED -> listOf(coverColors.seed)
                else -> cover.auto
            }
            val palettes = seeds.mapIndexed { i, s ->
                if (!chosen && i == 0) coverColors else ArtColors.fromSeed(s, neutral = chosen)
            }
            return ResolvedColors(flow, seeds, palettes, chosen)
        }
    }
}

/** Per-song color choices, saved as JSON. */
class SongColorRepository(context: Context) {
    private val file = File(context.filesDir, "song_colors.json")
    private val serializer = MapSerializer(String.serializer(), SongColorConfig.serializer())
    private val json = Json { ignoreUnknownKeys = true }
    private val _all = MutableStateFlow(load())
    val all: StateFlow<Map<String, SongColorConfig>> = _all.asStateFlow()

    fun get(songKey: String): SongColorConfig = _all.value[songKey] ?: SongColorConfig()

    @Synchronized
    fun set(songKey: String, cfg: SongColorConfig) {
        val next = if (cfg.isDefault) _all.value - songKey else _all.value + (songKey to cfg)
        if (next == _all.value) return
        _all.value = next
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(serializer, next))
            tmp.renameTo(file)
        }
    }

    private fun load(): Map<String, SongColorConfig> = runCatching {
        if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyMap()
    }.getOrDefault(emptyMap())
}
