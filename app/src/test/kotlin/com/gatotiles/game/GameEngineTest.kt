package com.gatotiles.game

import com.gatotiles.analyzer.model.Chart
import com.gatotiles.analyzer.model.Difficulty
import com.gatotiles.analyzer.model.Note
import com.gatotiles.data.GameMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameEngineTest {
    private class Recorder : GameListener {
        val judgments = ArrayList<Pair<Judgment, Boolean>>()
        var fail: FailReason? = null
        var ghosts = 0
        override fun onJudge(note: GameNote, judgment: Judgment, deltaMs: Double, tail: Boolean) {
            judgments.add(judgment to tail)
        }
        override fun onHoldStart(note: GameNote) = Unit
        override fun onCombo(combo: Int) = Unit
        override fun onFail(reason: FailReason, lane: Int, vMs: Double, note: GameNote?) {
            fail = reason
        }
        override fun onGhostTap(lane: Int) {
            ghosts++
        }
    }

    private fun engine(mode: GameMode, vararg notes: Note): Pair<GameEngine, Recorder> {
        val r = Recorder()
        return GameEngine(Chart(Difficulty.NORMAL, 5, notes.toList()), mode, noFail = false, listener = r) to r
    }

    @Test
    fun rhythmJudgmentsFollowTimingWindows() {
        val (e, r) = engine(GameMode.RHYTHM, Note(1000, 0), Note(2000, 1), Note(3000, 2), Note(4000, 3))
        e.tapRhythm(1, 0.5f, 1010.0)
        e.tapRhythm(1, 1.5f, 2070.0)
        e.tapRhythm(1, 2.5f, 2880.0)
        e.update(4200.0)
        assertEquals(
            listOf(Judgment.PERFECT, Judgment.GREAT, Judgment.GOOD, Judgment.MISS),
            r.judgments.map { it.first },
        )
        assertEquals(0, e.combo)
        assertEquals(3, e.maxCombo)
        assertTrue(e.life < 1f)
        assertNull(r.fail)
    }

    @Test
    fun tapsOutsideTheWindowAreGhostTaps() {
        val (e, r) = engine(GameMode.RHYTHM, Note(1000, 0))
        e.tapRhythm(1, 0.5f, 700.0)
        assertEquals(1, r.ghosts)
        assertTrue(r.judgments.isEmpty())
    }

    @Test
    fun neighbouringLaneIsForgivenNearTheEdge() {
        val (e, r) = engine(GameMode.RHYTHM, Note(1000, 2))
        e.tapRhythm(1, 1.85f, 1000.0)
        assertEquals(Judgment.PERFECT, r.judgments.single().first)
    }

    @Test
    fun holdsGiveHeadAndTailJudgments() {
        val (e, r) = engine(GameMode.RHYTHM, Note(1000, 1, 800), Note(3000, 1, 800))
        e.tapRhythm(7, 1.5f, 1000.0)
        e.update(1500.0)
        e.release(7, 1750.0) // within HOLD_RELEASE of the end (1800)
        e.tapRhythm(8, 1.5f, 3000.0)
        e.release(8, 3300.0) // far too early
        assertEquals(
            listOf(Judgment.PERFECT to false, Judgment.PERFECT to true, Judgment.PERFECT to false, Judgment.MISS to true),
            r.judgments,
        )
    }

    @Test
    fun heldTileCompletesByItselfAtTheEnd() {
        val (e, r) = engine(GameMode.RHYTHM, Note(1000, 0, 500))
        e.tapRhythm(3, 0.2f, 1000.0)
        e.update(1600.0)
        assertEquals(listOf(Judgment.PERFECT to false, Judgment.PERFECT to true), r.judgments)
        assertTrue(e.allDone || e.notes.all { it.state == NoteState.DONE })
    }

    @Test
    fun perfectRunScoresOneMillion() {
        val notes = (0 until 37).map { Note(1000 + it * 300, it % 4, if (it % 5 == 0) 200 else 0) }.toTypedArray()
        val (e, _) = engine(GameMode.RHYTHM, *notes)
        e.autoPlay(100_000.0) { }
        e.update(100_000.0)
        val res = e.result(cleared = true, songMs = 100_000.0, durationMs = 100_000)
        assertEquals(1_000_000, res.score)
        assertTrue(res.allPerfect)
        assertEquals("S+", res.rank)
        assertEquals(5, res.stars)
    }

    @Test
    fun classicFailsOnWrongTap() {
        val (e, r) = engine(GameMode.CLASSIC, Note(1000, 0))
        e.tapClassic(1, 3, 0.0, null, 500.0)
        assertEquals(FailReason.WRONG_TILE, r.fail)
        assertTrue(e.failed)
    }

    @Test
    fun classicFailsOnMissedTile() {
        val (e, r) = engine(GameMode.CLASSIC, Note(1000, 0), Note(1500, 1))
        e.update(1000.0 + HitWindows.CLASSIC_LATE + 1)
        assertEquals(FailReason.MISSED_TILE, r.fail)
        assertEquals(1, e.result(false, 1200.0, 5000).miss)
    }

    @Test
    fun classicAcceptsTouchingAVisibleTileEarly() {
        val (e, r) = engine(GameMode.CLASSIC, Note(2000, 2))
        val tile = e.tileAt(lane = 2, touchMs = 2050.0, tileLenMs = 150.0, songMs = 1000.0, nowWall = 0L)
        e.tapClassic(1, 2, 1050.0, tile, 1000.0)
        assertNull(r.fail)
        assertEquals(Judgment.GOOD, r.judgments.single().first)
    }

    @Test
    fun classicForgivesTapNearTheLineEvenIfFingerMissedTheTile() {
        val (e, r) = engine(GameMode.CLASSIC, Note(1000, 1))
        e.tapClassic(1, 1, 400.0, null, 990.0)
        assertFalse(e.failed)
        assertEquals(Judgment.PERFECT, r.judgments.single().first)
    }
}
