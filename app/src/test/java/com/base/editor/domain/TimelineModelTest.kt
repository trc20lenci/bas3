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

    @Test fun gapDropsTransitionAndUndoRestoresIt() {
        val (m, a, _) = twoClips()
        m.setTransition(a, "fade", 500)
        m.checkpoint(); m.trimEnd(a, 9_000)
        assertTrue(m.state().transitions.isEmpty())
        assertTrue(m.undo())
        assertEquals(1, m.state().transitions.size)
    }

    @Test fun moveHopsOverNeighbour() {
        val m = TimelineModel()
        val x = m.addClip(0, MediaType.VIDEO, "x", 3_000, 3_000)
        m.addClip(0, MediaType.VIDEO, "y", 3_000, 3_000)
        m.moveClip(x, 5_000, 100, -1)
        assertEquals(6_000, m.state().clips.first { it.id == x }.startMs)
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
