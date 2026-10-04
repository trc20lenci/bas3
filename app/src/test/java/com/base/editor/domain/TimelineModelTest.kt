package com.base.editor.domain

import com.base.editor.core.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineModelTest {
    private fun twoClips(): Triple<TimelineModel, Long, Long> {
        val m = TimelineModel()
        val a = m.addClip(0, MediaType.VIDEO, "a", 10_000, 10_000)
        val b = m.addClip(0, MediaType.VIDEO, "b", 5_000, 5_000)
        return Triple(m, a, b)
    }

    @Test fun transitionIsClampedToIncomingClip() {
        val (m, a, _) = twoClips()
        assertEquals(5_000, m.maxTransitionMs(a))
        assertTrue(m.setTransition(a, "fade", 9_999))
        assertEquals(5_000, m.state().transitions.single().durationMs)
    }

    @Test fun splitMovesTransitionToRightHalf() {
        val (m, a, b) = twoClips()
        m.setTransition(a, "fade", 500)
        val a2 = m.split(a, 4_000)
        val t = m.state().transitions.single()
        assertEquals(a2, t.leftId); assertEquals(b, t.rightId)
    }

    @Test fun trimEndClosesGapAndKeepsTransition() {
        val (m, a, b) = twoClips()
        m.setTransition(a, "fade", 500)
        m.checkpoint(); m.trimEnd(a, 9_000)
        // следующий клип подтянулся влево: пустоты нет, переход сохранён
        assertEquals(9_000, m.state().clips.first { it.id == b }.startMs)
        assertEquals(1, m.state().transitions.size)
        assertTrue(m.undo())
        assertEquals(10_000, m.state().clips.first { it.id == b }.startMs)
    }

    @Test fun trimStartKeepsPositionAndRipplesFollowers() {
        val (m, a, b) = twoClips()
        m.checkpoint(); m.trimStart(a, 2_000)             // срезали 2 с с начала первого клипа
        val s = m.state().clips
        assertEquals(0, s.first { it.id == a }.startMs)
        assertEquals(8_000, s.first { it.id == a }.lengthMs)
        assertEquals(2_000, s.first { it.id == a }.srcInMs)
        assertEquals(8_000, s.first { it.id == b }.startMs)
    }

    @Test fun repeatedGestureStepsAreIdempotent() {
        val (m, a, _) = twoClips()
        m.checkpoint()
        m.trimEnd(a, 8_000); m.trimEnd(a, 6_000); m.trimEnd(a, 7_000)   // палец ходит туда-сюда
        assertEquals(7_000, m.state().clips.first { it.id == a }.lengthMs)
    }

    @Test fun mainTrackNeverHasGaps() {
        val m = TimelineModel()
        val ids = (1..5).map { m.addClip(0, MediaType.VIDEO, "c$it", 4_000, 4_000) }
        m.checkpoint(); m.trimEnd(ids[1], 1_500)
        m.checkpoint(); m.remove(ids[2])
        m.checkpoint(); m.trimStart(ids[0], 1_000)
        m.checkpoint(); m.moveClip(ids[4], 0, 100, -1)
        m.checkpoint(); m.split(ids[3], m.state().clips.first { it.id == ids[3] }.startMs + 1_000)
        val main = m.state().clips.filter { it.row == 0 }.sortedBy { it.startMs }
        assertEquals(0, main.first().startMs)
        main.zipWithNext().forEach { (x, y) -> assertEquals(x.endMs, y.startMs) }
    }

    @Test fun loadClosesGapsOfOldProjects() {
        val m = TimelineModel()
        assertTrue(m.load("V1\t3\n1\t0\t0\t0\t1000\t0\t5000\ta\n2\t0\t0\t4000\t6000\t0\t5000\tb\n"))
        assertEquals(1_000, m.state().clips.first { it.id == 2L }.startMs)
    }

    @Test fun dragReordersClipsWithoutGaps() {
        val m = TimelineModel()
        val x = m.addClip(0, MediaType.VIDEO, "x", 3_000, 3_000)
        val y = m.addClip(0, MediaType.VIDEO, "y", 3_000, 3_000)
        m.checkpoint(); m.moveClip(x, 5_000, 100, -1)      // перетащили x за y
        val s = m.state().clips
        assertEquals(0, s.first { it.id == y }.startMs)
        assertEquals(3_000, s.first { it.id == x }.startMs)
    }

    @Test fun serializeRoundTrip() {
        val (m, a, _) = twoClips()
        m.setTransition(a, "wipeLeft", 700)
        val copy = TimelineModel()
        assertTrue(copy.load(m.serialize()))
        assertEquals(m.serialize(), copy.serialize())
    }

    @Test fun rippleDeleteOnMainTrack() {
        val (m, a, b) = twoClips()
        m.remove(a)
        assertEquals(0, m.state().clips.single { it.id == b }.startMs)
    }
}
