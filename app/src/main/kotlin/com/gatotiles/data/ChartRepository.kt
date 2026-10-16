package com.gatotiles.data

import android.content.Context
import android.util.LruCache
import com.gatotiles.analyzer.SongAnalyzer
import com.gatotiles.analyzer.model.ANALYZER_VERSION
import com.gatotiles.analyzer.model.SongChart
import com.gatotiles.audio.PcmDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors

sealed interface AnalysisState {
    data object Idle : AnalysisState
    data class Working(val progress: Float, val stage: String) : AnalysisState
    data class Ready(val chart: SongChart) : AnalysisState
    data class Failed(val message: String) : AnalysisState
}

/**
 * Generates charts on the device (decode + analyze, one song at a time) and caches them
 * as JSON so each song is analyzed only once.
 */
class ChartRepository(private val context: Context) {
    private val dir = File(context.filesDir, "charts").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val memory = LruCache<String, SongChart>(12)
    private val states = ConcurrentHashMap<String, MutableStateFlow<AnalysisState>>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val analysisDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "chart-analysis").apply { priority = Thread.NORM_PRIORITY - 1 }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val readyKeys = ConcurrentHashMap.newKeySet<String>()

    private fun file(song: Song) = File(dir, "${song.key}.v$ANALYZER_VERSION.json")

    /** Cheap check for list badges: is there a chart on disk for this song? */
    fun isCached(song: Song): Boolean = song.key in readyKeys || memory.get(song.key) != null || file(song).exists()

    fun refreshCachedKeys(songs: List<Song>) {
        // Charts made by an older analyzer are regenerated, so they do not count as ready.
        val current = ".v$ANALYZER_VERSION.json"
        dir.listFiles()?.forEach { if (!it.name.endsWith(current)) it.delete() }
        for (s in songs) if (file(s).exists()) readyKeys.add(s.key)
    }

    fun state(song: Song): StateFlow<AnalysisState> =
        states.getOrPut(song.key) { MutableStateFlow(AnalysisState.Idle) }.asStateFlow()

    suspend fun load(song: Song): SongChart? = withContext(Dispatchers.IO) {
        memory.get(song.key)?.let { return@withContext it }
        val f = file(song)
        if (!f.exists()) return@withContext null
        val chart = runCatching { json.decodeFromString(SongChart.serializer(), f.readText()) }.getOrNull()
        if (chart == null || chart.version != ANALYZER_VERSION) {
            f.delete()
            readyKeys.remove(song.key)
            return@withContext null
        }
        memory.put(song.key, chart)
        readyKeys.add(song.key)
        chart
    }

    /** Starts (or joins) the analysis of [song]; observe progress with the returned flow. */
    fun request(song: Song): StateFlow<AnalysisState> {
        val flow = states.getOrPut(song.key) { MutableStateFlow(AnalysisState.Idle) }
        val cur = flow.value
        if (cur is AnalysisState.Working || cur is AnalysisState.Ready) return flow.asStateFlow()
        jobs[song.key] = scope.launch {
            val cached = load(song)
            if (cached != null) {
                flow.value = AnalysisState.Ready(cached)
                return@launch
            }
            flow.value = AnalysisState.Working(0f, "En cola…")
            try {
                val chart = withContext(analysisDispatcher) {
                    analyze(song) { p, stage -> flow.value = AnalysisState.Working(p, stage) }
                }
                withContext(Dispatchers.IO) { file(song).writeText(json.encodeToString(SongChart.serializer(), chart)) }
                memory.put(song.key, chart)
                readyKeys.add(song.key)
                flow.value = AnalysisState.Ready(chart)
            } catch (e: CancellationException) {
                flow.value = AnalysisState.Idle
                throw e
            } catch (e: Throwable) {
                flow.value = AnalysisState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
        return flow.asStateFlow()
    }

    fun cancel(song: Song) {
        jobs.remove(song.key)?.cancel()
    }

    fun delete(song: Song) {
        cancel(song)
        memory.remove(song.key)
        readyKeys.remove(song.key)
        file(song).delete()
        states[song.key]?.value = AnalysisState.Idle
    }

    fun clearAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        memory.evictAll()
        readyKeys.clear()
        dir.listFiles()?.forEach { it.delete() }
        states.values.forEach { it.value = AnalysisState.Idle }
    }

    fun cacheSizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    private class Chunk(val data: FloatArray, val frames: Int, val sampleRate: Int, val channels: Int)

    /**
     * Decodes on one thread and analyzes on this one, so the total time is roughly the
     * slower of the two instead of their sum.
     */
    private suspend fun analyze(song: Song, progress: (Float, String) -> Unit): SongChart {
        if (song.durationMs > MAX_DURATION_MS) throw IllegalArgumentException("La canción dura más de 20 minutos")
        progress(0f, "Escuchando la canción…")
        val started = System.nanoTime()
        val queue = ArrayBlockingQueue<Any>(24)
        val eof = Any()
        val stop = AtomicBoolean(false)
        val decoder = Thread({
            try {
                PcmDecoder(context, song.uri).use { dec ->
                    val buf = FloatArray(16384)
                    while (!stop.get()) {
                        val n = dec.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        queue.put(Chunk(buf.copyOf(n * dec.channels), n, dec.sampleRate, dec.channels))
                    }
                }
                queue.put(eof)
            } catch (_: InterruptedException) {
                // cancelled
            } catch (e: Throwable) {
                runCatching { queue.put(e) }
            }
        }, "chart-decode")
        decoder.start()
        try {
            var analyzer: SongAnalyzer? = null
            val durationSec = (song.durationMs / 1e3).coerceAtLeast(1.0)
            var lastReport = 0L
            while (true) {
                val item = queue.poll(200, TimeUnit.MILLISECONDS)
                currentCoroutineContext().ensureActive()
                if (item == null) continue
                if (item === eof) break
                if (item is Throwable) throw item
                val c = item as Chunk
                val a = analyzer ?: SongAnalyzer(c.sampleRate, c.channels).also { analyzer = it }
                a.pushInterleaved(c.data, c.frames)
                val now = System.nanoTime()
                if (now - lastReport > 100_000_000L) {
                    lastReport = now
                    progress((0.85 * a.secondsPushed / durationSec).toFloat().coerceIn(0f, 0.85f), "Escuchando la canción…")
                }
            }
            val a = analyzer ?: throw IllegalStateException("No se pudo leer el audio")
            progress(0.88f, "Buscando el ritmo y la melodía…")
            val t2 = System.nanoTime()
            val result = a.finish()
            android.util.Log.i(
                "GatoTiles",
                "analysis ${song.title}: total %.1fs (finish %.1fs), %d Hz, bpm %.1f".format(
                    (System.nanoTime() - started) / 1e9, (System.nanoTime() - t2) / 1e9, a.analysisRate, result.chart.bpm,
                ),
            )
            progress(1f, "Listo")
            return result.chart
        } finally {
            stop.set(true)
            decoder.interrupt()
        }
    }

    private companion object {
        const val MAX_DURATION_MS = 20 * 60 * 1000L
    }
}
