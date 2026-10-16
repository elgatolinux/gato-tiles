package com.gatotiles.analyzer.model

import kotlinx.serialization.Serializable

/** Bump when the analysis changes so cached charts get regenerated. */
const val ANALYZER_VERSION = 4

const val DEFAULT_LANES = 4

/** Lane counts every song gets a chart for. */
val LANE_OPTIONS = listOf(3, 4)

@Serializable
enum class Difficulty(val label: String) {
    EASY("Fácil"),
    NORMAL("Normal"),
    HARD("Difícil"),
    EXPERT("Experto"),
}

/** A tile: [t] = hit time in ms from the start of the audio, [d] = hold length in ms (0 = tap). */
@Serializable
data class Note(val t: Int, val lane: Int, val d: Int = 0)

@Serializable
data class Chart(val difficulty: Difficulty, val level: Int, val notes: List<Note>, val lanes: Int = DEFAULT_LANES)

/**
 * A part of the song (intro, verse, chorus...) starting at [t] ms. Parts that sound alike
 * share a [label] (0, 1, 2... in order of first appearance); [energy] is 0..100.
 */
@Serializable
data class Section(val t: Int, val label: Int, val energy: Int)

@Serializable
data class SongChart(
    val version: Int,
    val durationMs: Int,
    val bpm: Float,
    val beatsMs: List<Int>,
    /** Beat i is the first beat of a bar when i % 4 == downbeatPhase. */
    val downbeatPhase: Int,
    /** Loudness intensity 0..100, 10 values per second. */
    val intensity: List<Int>,
    /** Waveform overview 0..100 for the UI. */
    val waveform: List<Int>,
    /** Where the song preview should start (loudest section). */
    val previewMs: Int,
    val charts: List<Chart>,
    /** Parts of the song in time order; the first starts at 0. */
    val sections: List<Section> = emptyList(),
) {
    fun chart(d: Difficulty, lanes: Int = DEFAULT_LANES): Chart =
        charts.firstOrNull { it.difficulty == d && it.lanes == lanes } ?: charts.first { it.difficulty == d }
}
