package com.gatotiles.game

import com.gatotiles.analyzer.model.Chart
import com.gatotiles.data.GameMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

interface GameListener {
    fun onJudge(note: GameNote, judgment: Judgment, deltaMs: Double, tail: Boolean)
    fun onHoldStart(note: GameNote)
    fun onCombo(combo: Int)
    fun onFail(reason: FailReason, lane: Int, vMs: Double, note: GameNote?)
    fun onGhostTap(lane: Int)
}

/**
 * Game rules, independent of rendering. All times are song times in ms.
 * Rhythm mode judges timing against the hit line; Classic mode accepts touching the tile
 * itself (as in Piano Tiles) and ends the run on the first wrong tap or missed tile.
 */
class GameEngine(
    chart: Chart,
    val mode: GameMode,
    private val noFail: Boolean,
    private val listener: GameListener,
) {
    val notes: List<GameNote> = chart.notes.mapIndexed { i, n -> GameNote(i, n.t, n.lane, n.d) }
    val difficulty = chart.difficulty
    val lanes = chart.lanes
    private val totalJudgments = max(1, notes.sumOf { if (it.isHold) 2L else 1L }.toInt())
    val totalTiles = notes.size
    val lastNoteEndMs: Int = notes.maxOfOrNull { it.endMs } ?: 0

    var score = 0
        private set
    var combo = 0
        private set
    var maxCombo = 0
        private set
    var life = 1f
        private set
    var tilesHit = 0
        private set
    var perfect = 0
        private set
    var great = 0
        private set
    var good = 0
        private set
    var miss = 0
        private set
    var failed = false
        private set
    var failReason: FailReason? = null
        private set
    var judgedCount = 0
        private set

    private var scoreAcc = 0.0
    private var factorSum = 0.0
    private var offsetSum = 0.0
    private var offsetCount = 0
    private var firstPending = 0
    private var brokenHolds = 0

    init {
        for (i in 1 until notes.size) {
            if (notes[i].timeMs == notes[i - 1].timeMs) {
                notes[i].isDouble = true
                notes[i - 1].isDouble = true
            }
        }
    }

    val allDone: Boolean get() = firstPending >= notes.size

    fun update(songMs: Double) {
        if (failed) return
        while (firstPending < notes.size && notes[firstPending].state == NoteState.DONE) firstPending++
        val lateLimit = if (mode == GameMode.CLASSIC) HitWindows.CLASSIC_LATE else HitWindows.GOOD
        var i = firstPending
        while (i < notes.size) {
            val n = notes[i]
            if (n.timeMs > songMs + 2000) break
            when (n.state) {
                NoteState.PENDING -> if (songMs > n.timeMs + lateLimit) {
                    missNote(n, songMs)
                    if (failed) return
                }
                NoteState.HOLDING -> if (songMs >= n.endMs) completeHold(n)
                NoteState.DONE -> Unit
            }
            i++
        }
    }

    /** Pending notes in [lane] whose hit window contains [songMs] (earliest first). */
    private fun findInWindow(lane: Int, songMs: Double, early: Int, late: Int): GameNote? {
        var i = firstPending
        while (i < notes.size) {
            val n = notes[i]
            if (n.timeMs > songMs + early) break
            if (n.lane == lane && n.state == NoteState.PENDING && songMs <= n.timeMs + late) return n
            i++
        }
        return null
    }

    /**
     * Rhythm mode tap at fractional lane position [laneU] (0..lanes). Taps near a lane edge
     * also try the neighbouring lane, which forgives slightly-off fingers.
     */
    fun tapRhythm(pointerId: Int, laneU: Float, songMs: Double) {
        if (failed) return
        val primary = laneU.toInt().coerceIn(0, lanes - 1)
        val frac = laneU - primary
        var note = findInWindow(primary, songMs, HitWindows.GOOD, HitWindows.GOOD)
        if (note == null) {
            val other = when {
                frac < 0.28f && primary > 0 -> primary - 1
                frac > 0.72f && primary < lanes - 1 -> primary + 1
                else -> -1
            }
            if (other >= 0) note = findInWindow(other, songMs, HitWindows.GOOD, HitWindows.GOOD)
        }
        if (note == null) {
            listener.onGhostTap(primary)
            return
        }
        hit(note, pointerId, songMs)
    }

    /**
     * Classic mode tap. [tile] is the tile under the finger (found by the renderer from the
     * touch position), or null when the finger landed on empty track.
     */
    fun tapClassic(pointerId: Int, lane: Int, vMs: Double, tile: GameNote?, songMs: Double) {
        if (failed) return
        if (tile != null && tile.state == NoteState.PENDING) {
            hit(tile, pointerId, songMs)
            return
        }
        // Forgive taps right on the hit line of the lane even if the finger missed the tile.
        val timed = findInWindow(lane, songMs, HitWindows.GOOD, HitWindows.CLASSIC_LATE)
        if (timed != null) {
            hit(timed, pointerId, songMs)
            return
        }
        if (tile != null) return // tapped a tile that is already being held / just hit
        fail(FailReason.WRONG_TILE, lane, vMs, null)
    }

    /** Candidate tile for Classic hit testing: pending tile in [lane] covering song time [touchMs]. */
    fun tileAt(lane: Int, touchMs: Double, tileLenMs: Double, songMs: Double, nowWall: Long): GameNote? {
        val tol = tileLenMs * 0.35
        var i = firstPending
        while (i < notes.size) {
            val n = notes[i]
            if (n.timeMs > touchMs + tol) break
            if (n.lane == lane) {
                val top = n.timeMs + max(tileLenMs, n.durMs.toDouble())
                val covers = touchMs >= n.timeMs - tol && touchMs <= top + tol
                if (covers) {
                    // Any tile under the finger is a valid tap (as in Piano Tiles); very early
                    // taps simply score GOOD.
                    when (n.state) {
                        NoteState.PENDING, NoteState.HOLDING -> return n
                        NoteState.DONE -> if (nowWall - n.effectAt < 250) return n
                    }
                }
            }
            i++
        }
        return null
    }

    private fun hit(n: GameNote, pointerId: Int, songMs: Double) {
        val delta = songMs - n.timeMs
        val ad = abs(delta)
        val j = when {
            ad <= HitWindows.PERFECT -> Judgment.PERFECT
            ad <= HitWindows.GREAT -> Judgment.GREAT
            else -> Judgment.GOOD
        }
        n.judgment = j
        n.deltaMs = delta
        if (ad <= HitWindows.GOOD) {
            offsetSum += delta
            offsetCount++
        }
        tilesHit++
        if (n.isHold) {
            n.state = NoteState.HOLDING
            n.pointerId = pointerId
            listener.onHoldStart(n)
        } else {
            n.state = NoteState.DONE
        }
        register(j, n, delta, tail = false)
    }

    fun release(pointerId: Int, songMs: Double) {
        if (failed) return
        for (i in firstPending until notes.size) {
            val n = notes[i]
            if (n.timeMs > songMs + 1000) break
            if (n.state == NoteState.HOLDING && n.pointerId == pointerId) {
                if (songMs >= n.endMs - HitWindows.HOLD_RELEASE) {
                    completeHold(n)
                } else {
                    n.state = NoteState.DONE
                    n.tailBroken = true
                    brokenHolds++
                    if (mode == GameMode.RHYTHM) register(Judgment.MISS, n, 0.0, tail = true)
                }
            }
        }
    }

    /** Demo mode: hits every tile exactly on time. Returns lanes hit this call via [onHit]. */
    fun autoPlay(songMs: Double, onHit: (Int) -> Unit) {
        if (failed) return
        var i = firstPending
        while (i < notes.size) {
            val n = notes[i]
            if (n.timeMs > songMs) break
            if (n.state == NoteState.PENDING) {
                hit(n, AUTO_POINTER - n.lane, n.timeMs.toDouble())
                onHit(n.lane)
            }
            i++
        }
    }

    /** Pausing: finish any held tiles generously instead of breaking them. */
    fun releaseAllHolds() {
        for (i in firstPending until notes.size) {
            val n = notes[i]
            if (n.state == NoteState.HOLDING) completeHold(n)
        }
    }

    private fun completeHold(n: GameNote) {
        n.state = NoteState.DONE
        n.tailDone = true
        register(Judgment.PERFECT, n, 0.0, tail = true)
    }

    private fun missNote(n: GameNote, songMs: Double) {
        n.state = NoteState.DONE
        n.judgment = Judgment.MISS
        if (mode == GameMode.CLASSIC) {
            miss++
            judgedCount++
            combo = 0
            fail(FailReason.MISSED_TILE, n.lane, n.timeMs - songMs, n)
            return
        }
        register(Judgment.MISS, n, 0.0, tail = false)
        if (n.isHold && !failed) register(Judgment.MISS, n, 0.0, tail = true)
    }

    private fun register(j: Judgment, n: GameNote, delta: Double, tail: Boolean) {
        when (j) {
            Judgment.PERFECT -> perfect++
            Judgment.GREAT -> great++
            Judgment.GOOD -> good++
            Judgment.MISS -> miss++
        }
        judgedCount++
        factorSum += j.factor
        scoreAcc += 1_000_000.0 * j.factor / totalJudgments
        score = scoreAcc.roundToInt()
        if (j == Judgment.MISS) {
            combo = 0
            life -= 0.08f
        } else {
            combo++
            if (combo > maxCombo) maxCombo = combo
            life += when (j) {
                Judgment.PERFECT -> 0.02f
                Judgment.GREAT -> 0.012f
                else -> 0f
            }
        }
        life = life.coerceIn(0f, 1f)
        n.effectAt = System.nanoTime() / 1_000_000L
        listener.onJudge(n, j, delta, tail)
        if (combo > 0 && (combo == 10 || combo == 25 || combo % 50 == 0)) listener.onCombo(combo)
        if (mode == GameMode.RHYTHM && !noFail && life <= 0f) fail(FailReason.NO_LIFE, n.lane, 0.0, n)
    }

    private fun fail(reason: FailReason, lane: Int, vMs: Double, note: GameNote?) {
        if (failed) return
        failed = true
        failReason = reason
        listener.onFail(reason, lane, vMs, note)
    }

    fun result(cleared: Boolean, songMs: Double, durationMs: Int): GameResult {
        val acc = if (judgedCount == 0) 0f else (factorSum / judgedCount * 100).toFloat()
        val progress = if (cleared) 1f else (songMs / max(1, durationMs)).toFloat().coerceIn(0f, 1f)
        val stars: Int
        var crowns = 0
        if (mode == GameMode.CLASSIC) {
            stars = when {
                cleared -> 3
                progress >= 2f / 3 -> 2
                progress >= 1f / 3 -> 1
                else -> 0
            }
            if (cleared) {
                crowns = when {
                    acc >= 98f -> 3
                    acc >= 93f -> 2
                    acc >= 85f -> 1
                    else -> 0
                }
            }
        } else {
            stars = if (!cleared) {
                0
            } else {
                when {
                    score >= 990_000 -> 5
                    score >= 950_000 -> 4
                    score >= 880_000 -> 3
                    score >= 750_000 -> 2
                    else -> 1
                }
            }
        }
        val rank = when {
            !cleared -> "F"
            score >= 990_000 -> "S+"
            score >= 950_000 -> "S"
            score >= 900_000 -> "A"
            score >= 800_000 -> "B"
            score >= 700_000 -> "C"
            else -> "D"
        }
        return GameResult(
            mode = mode,
            difficulty = difficulty,
            lanes = lanes,
            score = score,
            accuracy = acc,
            maxCombo = maxCombo,
            perfect = perfect,
            great = great,
            good = good,
            miss = miss,
            tiles = tilesHit,
            totalTiles = totalTiles,
            cleared = cleared,
            failReason = failReason,
            stars = stars,
            crowns = crowns,
            rank = rank,
            fullCombo = cleared && miss == 0 && brokenHolds == 0,
            allPerfect = cleared && miss == 0 && great == 0 && good == 0 && brokenHolds == 0,
            progress = progress,
            meanOffsetMs = if (offsetCount == 0) 0f else (offsetSum / offsetCount).toFloat(),
        )
    }

    companion object {
        const val AUTO_POINTER = -100
    }
}
