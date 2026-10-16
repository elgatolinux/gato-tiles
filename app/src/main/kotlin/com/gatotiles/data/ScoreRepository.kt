package com.gatotiles.data

import android.content.Context
import com.gatotiles.analyzer.model.Difficulty
import com.gatotiles.game.GameResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class BestScore(
    val score: Int,
    val accuracy: Float,
    val maxCombo: Int,
    val stars: Int,
    val crowns: Int = 0,
    val rank: String,
    val fullCombo: Boolean,
    val allPerfect: Boolean,
    val cleared: Boolean,
    val tiles: Int,
    val plays: Int,
    val date: Long,
)

@Serializable
private data class ScoreFile(
    val best: Map<String, BestScore> = emptyMap(),
    val lastPlayed: Map<String, Long> = emptyMap(),
)

/** Local records per song / difficulty / mode. */
class ScoreRepository(context: Context) {
    private val file = File(context.filesDir, "scores.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val _data = MutableStateFlow(load())
    private val data: ScoreFile get() = _data.value
    private val _best = MutableStateFlow(data.best)
    val best: StateFlow<Map<String, BestScore>> = _best.asStateFlow()
    private val _lastPlayed = MutableStateFlow(data.lastPlayed)
    val lastPlayed: StateFlow<Map<String, Long>> = _lastPlayed.asStateFlow()

    fun best(songKey: String, d: Difficulty, m: GameMode, lanes: Int): BestScore? = data.best[key(songKey, d, m, lanes)]

    /** Best of any mode/difficulty for list badges. */
    fun bestStars(songKey: String): Int = data.best.filterKeys { it.startsWith("$songKey|") }.values.maxOfOrNull { it.stars } ?: 0

    /** Saves the result; returns true when it is a new best score. */
    @Synchronized
    fun record(songKey: String, r: GameResult): Boolean {
        val k = key(songKey, r.difficulty, r.mode, r.lanes)
        val prev = data.best[k]
        val value = if (r.mode == GameMode.CLASSIC) r.tiles else r.score
        val prevValue = if (prev == null) -1 else if (r.mode == GameMode.CLASSIC) prev.tiles else prev.score
        val isNew = value > prevValue
        val plays = (prev?.plays ?: 0) + 1
        val entry = if (isNew || prev == null) {
            BestScore(
                score = r.score,
                accuracy = r.accuracy,
                maxCombo = r.maxCombo,
                stars = maxOf(r.stars, prev?.stars ?: 0),
                crowns = maxOf(r.crowns, prev?.crowns ?: 0),
                rank = r.rank,
                fullCombo = r.fullCombo || (prev?.fullCombo ?: false),
                allPerfect = r.allPerfect || (prev?.allPerfect ?: false),
                cleared = r.cleared || (prev?.cleared ?: false),
                tiles = r.tiles,
                plays = plays,
                date = System.currentTimeMillis(),
            )
        } else {
            prev.copy(
                plays = plays,
                stars = maxOf(prev.stars, r.stars),
                crowns = maxOf(prev.crowns, r.crowns),
                fullCombo = prev.fullCombo || r.fullCombo,
                allPerfect = prev.allPerfect || r.allPerfect,
                cleared = prev.cleared || r.cleared,
            )
        }
        val next = data.copy(
            best = data.best + (k to entry),
            lastPlayed = data.lastPlayed + (songKey to System.currentTimeMillis()),
        )
        _data.value = next
        _best.value = next.best
        _lastPlayed.value = next.lastPlayed
        save(next)
        return isNew && prev != null
    }

    companion object {
        /** Records of 4-lane charts keep the original key, so old scores stay valid. */
        fun key(songKey: String, d: Difficulty, m: GameMode, lanes: Int) =
            if (lanes == 4) "$songKey|${d.name}|${m.name}" else "$songKey|${d.name}|${m.name}|$lanes"
    }

    private fun load(): ScoreFile = runCatching {
        if (file.exists()) json.decodeFromString(ScoreFile.serializer(), file.readText()) else ScoreFile()
    }.getOrDefault(ScoreFile())

    private fun save(d: ScoreFile) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(ScoreFile.serializer(), d))
            tmp.renameTo(file)
        }
    }
}
