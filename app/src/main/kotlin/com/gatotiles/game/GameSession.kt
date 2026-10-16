package com.gatotiles.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import com.gatotiles.analyzer.model.Difficulty
import com.gatotiles.analyzer.model.SongChart
import com.gatotiles.audio.SongPlayer
import com.gatotiles.data.GameMode
import com.gatotiles.data.ResolvedColors
import com.gatotiles.data.Settings
import com.gatotiles.data.Song
import com.gatotiles.data.SpeedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max

sealed interface GameUiState {
    data object Ready : GameUiState
    data object Playing : GameUiState
    data object Paused : GameUiState
    data class Finished(val result: GameResult) : GameUiState
    data class Error(val message: String) : GameUiState
}

/**
 * One play of one chart. Engine, renderer and input processing all run on the render
 * thread (see [GameView]); the UI thread only enqueues touches and calls pause/resume.
 * Touches carry their own timestamps, so judgments do not depend on frame timing.
 */
class GameSession(
    context: Context,
    val song: Song,
    val songChart: SongChart,
    val difficulty: Difficulty,
    val mode: GameMode,
    val settings: Settings,
    val colors: ResolvedColors,
    art: Bitmap?,
) : GameListener {
    enum class Phase { READY, PLAYING, PAUSED, FAILING, FINISHED }

    private val appContext = context.applicationContext
    val timeline = ColorTimeline(colors.palettes, colors.flow, songChart.sections, songChart.durationMs)
    val engine = GameEngine(songChart.chart(difficulty, settings.lanes), mode, settings.noFail, this)
    val lanes = engine.lanes
    /** Where tiles are drawn: plain time, or faster in the busy parts of the song. */
    val scroll: ScrollMap = if (settings.speedFlow == SpeedFlow.OFF) {
        ScrollMap.CONSTANT
    } else {
        val times = engine.notes.map { it.timeMs }.distinct().toIntArray()
        ScrollMap.forSong(songChart.sections, times, songChart.durationMs, settings.speedFlow.boost, settings.approachMs)
    }
    val player = SongPlayer(appContext, song.uri)
    private val renderer = GameRenderer(appContext, this, art)
    private val _ui = MutableStateFlow<GameUiState>(GameUiState.Ready)
    val ui: StateFlow<GameUiState> = _ui.asStateFlow()

    @Volatile
    var phase = Phase.READY
        private set
    private var pausedFromReady = false
    val laneDown = IntArray(lanes)
    private val pointerLane = HashMap<Int, Int>()
    private val inputs = ConcurrentLinkedQueue<TouchInput>()
    private val leadInMs = max(2000, settings.approachMs + 1000)
    private var lastFrameNanos = 0L
    private var vsyncNanos = 16_666_667L
    private var failNanos = 0L
    private var frozenSongMs = 0.0
    private var finishNanos = 0L
    @Volatile private var releaseHoldsRequested = false
    private val tmp = FloatArray(2)
    var topInset = 0f

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Vibrator::class.java)
    }

    private class TouchInput(val down: Boolean, val id: Int, val x: Float, val y: Float, val nanos: Long)

    /** UI thread. */
    fun onTouch(e: MotionEvent) {
        val nanos = if (Build.VERSION.SDK_INT >= 34) e.eventTimeNanos else e.eventTime * 1_000_000L
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                inputs.add(TouchInput(true, e.getPointerId(i), e.getX(i), e.getY(i), nanos))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                inputs.add(TouchInput(false, e.getPointerId(i), e.getX(i), e.getY(i), nanos))
            }
            MotionEvent.ACTION_CANCEL -> for (i in 0 until e.pointerCount) {
                inputs.add(TouchInput(false, e.getPointerId(i), 0f, 0f, nanos))
            }
        }
    }

    /** Render thread. */
    fun onResize(w: Int, h: Int) = renderer.resize(w, h, topInset)

    /** Render thread: one frame. */
    fun onFrame(c: Canvas, frameTimeNanos: Long) {
        if (lastFrameNanos != 0L) {
            val d = frameTimeNanos - lastFrameNanos
            if (d in 4_000_000L..40_000_000L) vsyncNanos = (vsyncNanos * 0.9 + d * 0.1).toLong()
        }
        val dtMs = if (lastFrameNanos == 0L) 16f else ((frameTimeNanos - lastFrameNanos) / 1e6f).coerceIn(0f, 50f)
        lastFrameNanos = frameTimeNanos
        val now = System.nanoTime()
        val nowMs = now / 1_000_000L
        if (releaseHoldsRequested) {
            releaseHoldsRequested = false
            engine.releaseAllHolds()
        }
        while (true) {
            val ev = inputs.poll() ?: break
            handle(ev, nowMs)
        }
        if (phase == Phase.PLAYING) {
            val logic = player.songTimeMs(now) - settings.inputOffsetMs
            if (settings.autoPlay) engine.autoPlay(logic) { lane -> renderer.onLanePress(lane) }
            engine.update(logic)
            val err = player.error
            if (err != null) {
                phase = Phase.FINISHED
                _ui.value = GameUiState.Error(err)
            } else if (phase == Phase.PLAYING && engine.allDone) {
                val audioOver = player.finished || logic > songChart.durationMs
                if (audioOver || logic > engine.lastNoteEndMs + 2500) finish(true, logic)
            }
        }
        if (phase == Phase.FAILING) {
            val age = (now - failNanos) / 1e6
            player.volume = (1.0 - age / 700.0).toFloat().coerceIn(0f, 1f)
            if (age > 1500) {
                player.pause()
                finish(false, frozenSongMs)
            }
        }
        if (phase == Phase.FINISHED && finishNanos > 0 && !player.isPaused) {
            val age = (now - finishNanos) / 1e6
            player.volume = (1.0 - age / 2500.0).toFloat().coerceIn(0f, 1f)
            if (age > 2500) player.pause()
        }
        val renderSong = when {
            !player.started && (phase == Phase.READY || phase == Phase.PAUSED || phase == Phase.PLAYING) -> -leadInMs.toDouble()
            phase == Phase.FAILING || (phase == Phase.FINISHED && failNanos > 0) -> frozenSongMs
            else -> player.songTimeMs(now + vsyncNanos) - settings.visualOffsetMs
        }
        renderer.draw(c, renderSong, nowMs, dtMs)
    }

    private fun handle(ev: TouchInput, nowMs: Long) {
        val l = renderer.layout ?: return
        if (ev.down) {
            if (ev.y < renderer.hudBottom) return
            l.toPlane(ev.x, ev.y, tmp)
            val laneU = (tmp[0] / l.laneW).coerceIn(0f, lanes - 0.001f)
            val lane = laneU.toInt()
            pointerLane[ev.id] = lane
            laneDown[lane]++
            renderer.onLanePress(lane)
            when (phase) {
                Phase.READY -> start()
                Phase.PLAYING -> {
                    val songAt = player.songTimeMs(ev.nanos) - settings.inputOffsetMs
                    if (mode == GameMode.RHYTHM) {
                        engine.tapRhythm(ev.id, laneU, songAt)
                    } else {
                        val vTouch = l.vAt(tmp[1])
                        val shown = player.songTimeMs(ev.nanos) - settings.visualOffsetMs
                        // The song time drawn under the finger, and how long a tile lasts there.
                        val touchMs = scroll.timeAt(scroll.pos(shown) + vTouch)
                        val tile = engine.tileAt(lane, touchMs, l.tileLenMs / scroll.speedAt(touchMs), songAt, nowMs)
                        engine.tapClassic(ev.id, lane, vTouch, tile, songAt)
                    }
                }
                else -> Unit
            }
        } else {
            val lane = pointerLane.remove(ev.id)
            if (lane != null && laneDown[lane] > 0) laneDown[lane]--
            if (phase == Phase.PLAYING) engine.release(ev.id, player.songTimeMs(ev.nanos) - settings.inputOffsetMs)
        }
    }

    private fun start() {
        if (phase != Phase.READY) return
        phase = Phase.PLAYING
        player.start(leadInMs)
        _ui.value = GameUiState.Playing
    }

    private fun finish(cleared: Boolean, songMs: Double) {
        phase = Phase.FINISHED
        if (cleared) finishNanos = System.nanoTime()
        _ui.value = GameUiState.Finished(engine.result(cleared, songMs, songChart.durationMs))
    }

    /** UI thread. */
    fun pause() {
        when (phase) {
            Phase.PLAYING -> {
                phase = Phase.PAUSED
                player.pause()
                releaseHoldsRequested = true
                _ui.value = GameUiState.Paused
            }
            Phase.READY -> {
                phase = Phase.PAUSED
                pausedFromReady = true
                _ui.value = GameUiState.Paused
            }
            else -> Unit
        }
    }

    /** UI thread. */
    fun resume() {
        if (phase != Phase.PAUSED) return
        if (pausedFromReady) {
            pausedFromReady = false
            phase = Phase.READY
            _ui.value = GameUiState.Ready
            return
        }
        player.resume()
        phase = Phase.PLAYING
        _ui.value = GameUiState.Playing
    }

    fun release() {
        player.release()
    }

    private fun vibrate(ms: Long) {
        val v = vibrator ?: return
        runCatching { v.vibrate(VibrationEffect.createOneShot(ms, if (ms > 50) 200 else 70)) }
    }

    // GameListener (render thread)

    override fun onJudge(note: GameNote, judgment: Judgment, deltaMs: Double, tail: Boolean) {
        renderer.onJudge(note, judgment, deltaMs, tail, System.nanoTime() / 1_000_000L)
        if (settings.vibration && judgment != Judgment.MISS && !tail) vibrate(12)
    }

    override fun onHoldStart(note: GameNote) = Unit

    override fun onCombo(combo: Int) = renderer.onCombo(System.nanoTime() / 1_000_000L)

    override fun onFail(reason: FailReason, lane: Int, vMs: Double, note: GameNote?) {
        phase = Phase.FAILING
        failNanos = System.nanoTime()
        frozenSongMs = player.songTimeMs(failNanos) - settings.visualOffsetMs
        // A missed tile comes with its time from the hit line; the renderer wants track distance.
        val v = if (reason == FailReason.MISSED_TILE && note != null) scroll.pos(note.timeMs.toDouble()) - scroll.pos(note.timeMs - vMs) else vMs
        renderer.onFail(reason, lane, v, note, failNanos / 1_000_000L)
        vibrate(140)
    }

    override fun onGhostTap(lane: Int) = renderer.onGhostTap(lane)
}
