package com.gatotiles.analyzer

import com.gatotiles.analyzer.chart.ChartGenerator
import com.gatotiles.analyzer.dsp.Decimator
import com.gatotiles.analyzer.dsp.FloatList
import com.gatotiles.analyzer.dsp.percentile
import com.gatotiles.analyzer.features.EnergyProfile
import com.gatotiles.analyzer.features.FeatureExtractor
import com.gatotiles.analyzer.features.Features
import com.gatotiles.analyzer.melody.MelodyNote
import com.gatotiles.analyzer.melody.MelodyTracker
import com.gatotiles.analyzer.model.ANALYZER_VERSION
import com.gatotiles.analyzer.model.Difficulty
import com.gatotiles.analyzer.model.LANE_OPTIONS
import com.gatotiles.analyzer.model.SongChart
import com.gatotiles.analyzer.rhythm.BeatGrid
import com.gatotiles.analyzer.rhythm.BeatTracker
import com.gatotiles.analyzer.rhythm.Onset
import com.gatotiles.analyzer.rhythm.OnsetDetector
import com.gatotiles.analyzer.structure.SongStructure
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Everything the analysis found; [chart] is what the game stores and plays. */
class AnalysisResult(
    val chart: SongChart,
    val features: Features,
    val onsets: List<Onset>,
    val grid: BeatGrid,
    val melody: List<MelodyNote>,
    val energy: EnergyProfile,
    val structure: SongStructure.Result,
)

/**
 * Streaming song analyzer. Feed decoded PCM (any sample rate / channel count) in order,
 * then call [finish]. Audio is downmixed and decimated to ~22-24 kHz for analysis.
 */
class SongAnalyzer(sourceSampleRate: Int, private val channels: Int) {
    private val factor = max(1, (sourceSampleRate / 22050.0).roundToInt())
    val analysisRate: Int = sourceSampleRate / factor
    private val decimator = Decimator(factor)
    private val extractor = FeatureExtractor(analysisRate)
    private var mono = FloatArray(8192)
    private val decimated = FloatList(16384)

    init {
        require(channels >= 1) { "channels must be >= 1" }
        require(sourceSampleRate >= 4000) { "sample rate too low" }
    }

    /** Seconds of audio pushed so far. */
    val secondsPushed: Double get() = extractor.samplesPushed.toDouble() / analysisRate

    /** Interleaved float samples in -1..1; [frames] = samples per channel. */
    fun pushInterleaved(pcm: FloatArray, frames: Int) {
        ensureMono(frames)
        val ch = channels
        if (ch == 1) {
            System.arraycopy(pcm, 0, mono, 0, frames)
        } else {
            val inv = 1f / ch
            var j = 0
            for (i in 0 until frames) {
                var s = 0f
                for (c in 0 until ch) s += pcm[j++]
                mono[i] = s * inv
            }
        }
        feed(frames)
    }

    /** Interleaved 16-bit samples; [frames] = samples per channel. */
    fun pushInterleaved16(pcm: ShortArray, frames: Int) {
        ensureMono(frames)
        val ch = channels
        val inv = 1f / (32768f * ch)
        var j = 0
        for (i in 0 until frames) {
            var s = 0
            for (c in 0 until ch) s += pcm[j++]
            mono[i] = s * inv
        }
        feed(frames)
    }

    private fun ensureMono(frames: Int) {
        if (mono.size < frames) mono = FloatArray(frames)
    }

    private fun feed(frames: Int) {
        decimator.push(mono, 0, frames, decimated)
        if (decimated.size >= 8192) flushDecimated()
    }

    private fun flushDecimated() {
        if (decimated.size > 0) extractor.push(decimated.raw(), 0, decimated.size)
        decimated.clear()
    }

    fun finish(): AnalysisResult {
        decimator.flush(decimated)
        flushDecimated()
        val f = extractor.finish()
        return analyze(f)
    }

    companion object {
        fun analyze(f: Features): AnalysisResult {
            val env = OnsetDetector.envelopes(f)
            val onsets = OnsetDetector.detect(f, env)
            val grid = BeatTracker.track(f, env, onsets)
            val melody = MelodyTracker.track(f, env, onsets)
            val energy = EnergyProfile.from(f)
            var seed = (f.durationSec * 1000).toLong() * 1_000_003L + onsets.size
            for (i in 0 until min(256, f.nFrames)) seed = seed * 31 + (f.fluxTotal[i] * 1000).toLong()
            val gen = ChartGenerator(f.durationSec, grid, onsets, melody, energy, seed)
            val charts = LANE_OPTIONS.flatMap { lanes -> Difficulty.entries.map { gen.generate(it, lanes) } }
            val structure = SongStructure.detect(f, grid, energy)
            val durationMs = (f.durationSec * 1000).roundToInt()
            val chart = SongChart(
                version = ANALYZER_VERSION,
                durationMs = durationMs,
                bpm = grid.bpm.toFloat(),
                beatsMs = grid.beats.map { (it * 1000).roundToInt() },
                downbeatPhase = grid.downbeatPhase,
                intensity = energy.resampled(10.0, f.durationSec),
                waveform = waveform(f, 400),
                previewMs = previewStart(energy, grid, f.durationSec),
                charts = charts,
                sections = structure.sections,
            )
            return AnalysisResult(chart, f, onsets, grid, melody, energy, structure)
        }

        private fun waveform(f: Features, bars: Int): List<Int> {
            val n = f.nFrames
            if (n == 0) return List(bars) { 0 }
            val ref = max(percentile(f.peak, 0.995), 1e-6f)
            return List(bars) { i ->
                val a = (i.toLong() * n / bars).toInt()
                val b = max(a + 1, ((i + 1).toLong() * n / bars).toInt())
                var m = 0f
                for (k in a until min(b, n)) m = max(m, f.peak[k])
                ((m / ref) * 100).roundToInt().coerceIn(0, 100)
            }
        }

        private fun previewStart(energy: EnergyProfile, grid: BeatGrid, duration: Double): Int {
            val win = 15.0
            if (duration <= win + 5) return 0
            var best = 0.0
            var bestScore = -1f
            var t = 0.0
            while (t + win <= duration - 2) {
                val s = energy.mean(t, t + win)
                if (s > bestScore) {
                    bestScore = s
                    best = t
                }
                t += 0.5
            }
            // Start on a bar line just before the loud section.
            var start = best
            for (i in grid.beats.indices.reversed()) {
                val b = grid.beats[i]
                if (b <= best && grid.isDownbeat(i)) {
                    start = b
                    break
                }
            }
            return (start * 1000).roundToInt()
        }
    }
}
