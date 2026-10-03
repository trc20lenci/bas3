package com.base.editor.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTrackTest {
    @Test fun addKeepsOrderAndNormalizes() {
        val t = TextTrack()
        t.add(TextClip(id = "b", text = "B", startMs = 5000, durationMs = 10))
        t.add(TextClip(id = "a", text = "A", startMs = 1000, positionX = 4f))
        assertEquals(listOf("a", "b"), t.all().map { it.id })
        assertEquals(1f, t.find("a")!!.positionX, 0f)
        assertEquals(TextClip.MIN_DURATION_MS, t.find("b")!!.durationMs)
    }

    @Test fun activeAtRespectsRange() {
        val t = TextTrack()
        t.add(TextClip(id = "a", text = "A", startMs = 1000, durationMs = 2000))
        assertTrue(t.activeAt(999).isEmpty()); assertEquals(1, t.activeAt(1000).size)
        assertEquals(1, t.activeAt(2999).size); assertTrue(t.activeAt(3000).isEmpty())
    }

    @Test fun updateAndRemove() {
        val t = TextTrack()
        val c = t.add(TextClip(text = "A", startMs = 0))
        assertTrue(t.update(c.copy(text = "Привет")))
        assertEquals("Привет", t.find(c.id)!!.text)
        assertTrue(t.remove(c.id)); assertFalse(t.remove(c.id))
    }

    @Test fun jsonRoundTripAndBrokenInput() {
        val items = listOf(TextClip(id = "x", text = "Тест", startMs = 500, durationMs = 2500, positionX = .3f, positionY = .8f, fontSizeSp = 32f, textColor = 0xFFFF1744, backgroundColor = 0xCC000000))
        assertEquals(items, TextJson.decode(TextJson.encode(items)))
        assertTrue(TextJson.decode("{oops").isEmpty())
    }
}
