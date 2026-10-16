package com.gatotiles.game

import com.gatotiles.analyzer.model.Difficulty
import com.gatotiles.data.GameMode

enum class Judgment(val label: String, val color: Int, val factor: Float) {
    PERFECT("PERFECT", 0xFF7DF9FF.toInt(), 1f),
    GREAT("GREAT", 0xFF8DFFA8.toInt(), 0.75f),
    GOOD("GOOD", 0xFFFFD66E.toInt(), 0.4f),
    MISS("MISS", 0xFFFF5C7C.toInt(), 0f),
}

/** Timing windows in ms (+/-) around a tile's hit time. */
object HitWindows {
    const val PERFECT = 45
    const val GREAT = 90
    const val GOOD = 135
    const val CLASSIC_LATE = 170
    /** Releasing a hold this close to its end still counts as complete. */
    const val HOLD_RELEASE = 140
}

enum class NoteState { PENDING, HOLDING, DONE }

class GameNote(val index: Int, val timeMs: Int, val lane: Int, val durMs: Int) {
    var state = NoteState.PENDING
    var judgment: Judgment? = null
    var deltaMs = 0.0
    /** Wall-clock time (ms) when it was hit/missed, for effects. */
    var effectAt = 0L
    var pointerId = -1
    var tailDone = false
    var tailBroken = false
    var isDouble = false
    val isHold: Boolean get() = durMs > 0
    val endMs: Int get() = timeMs + durMs
}

enum class FailReason(val label: String) {
    WRONG_TILE("Tocaste fuera de una tecla"),
    MISSED_TILE("Se te escapó una tecla"),
    NO_LIFE("Te quedaste sin vida"),
}

data class GameResult(
    val mode: GameMode,
    val difficulty: Difficulty,
    val lanes: Int,
    val score: Int,
    val accuracy: Float,
    val maxCombo: Int,
    val perfect: Int,
    val great: Int,
    val good: Int,
    val miss: Int,
    val tiles: Int,
    val totalTiles: Int,
    val cleared: Boolean,
    val failReason: FailReason?,
    val stars: Int,
    val crowns: Int,
    val rank: String,
    val fullCombo: Boolean,
    val allPerfect: Boolean,
    val progress: Float,
    val meanOffsetMs: Float,
)
