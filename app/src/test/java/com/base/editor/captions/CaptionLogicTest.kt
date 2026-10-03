package com.base.editor.captions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionLogicTest {
    private fun words() = listOf(
        WordTimestamp("привет", 0, 400), WordTimestamp("это", 450, 600), WordTimestamp("тест", 650, 900), WordTimestamp("субтитров", 950, 1500),
        WordTimestamp("потом", 1550, 1800), WordTimestamp("пауза", 3000, 3300), WordTimestamp("конец.", 3350, 3700), WordTimestamp("снова", 3750, 4000),
    )

    @Test fun segmentsByWordCountPauseAndSentence() {
        var n = 0
        val items = CaptionSegmenter(maxWords = 4, maxChars = 40, maxDurationMs = 3200, newId = { "id${n++}" }).segment(words())
        assertEquals(4, items.size)
        assertEquals("привет это тест субтитров", items[0].text)
        assertTrue(items[0].endMs <= items[1].startMs)
    }

    @Test fun activeWordFollowsTime() {
        val item = CaptionItem("a", 0, 1500, "x", words().take(4))
        assertEquals(-1, CaptionOps.activeWordIndex(item, -5))
        assertEquals(0, CaptionOps.activeWordIndex(item, 0))
        assertEquals(2, CaptionOps.activeWordIndex(item, 920))
    }

    @Test fun retextKeepsTimingsWhenWordCountSame() {
        val item = CaptionItem("a", 0, 1500, "x", words().take(4))
        val r = CaptionOps.retext(item, "A B C D")
        assertEquals(item.words.map { it.startMs }, r.words.map { it.startMs })
        assertEquals(listOf("A", "B", "C", "D"), r.words.map { it.word })
    }

    @Test fun retextRedistributesWhenCountChanges() {
        val item = CaptionItem("a", 0, 1500, "x", words().take(4))
        val r = CaptionOps.retext(item, "один два")
        assertEquals(2, r.words.size)
        assertEquals(0, r.words.first().startMs)
        assertTrue(r.words.last().endMs <= 1500)
    }

    @Test fun retimeScalesWordsAndEnforcesMinimum() {
        val item = CaptionItem("a", 0, 1500, "x", words().take(4))
        val m = CaptionOps.retime(item, 1000, 2000)
        assertEquals(1000, m.words.first().startMs)
        assertTrue(m.words.last().endMs <= 2000)
        assertEquals(700, CaptionOps.retime(item, 500, 400).endMs)
    }

    @Test fun jsonRoundTripKeepsItemsAndStyle() {
        val items = CaptionSegmenter().segment(words())
        val style = CaptionPresets.byId("karaoke")!!.copy(positionY = 0.4f)
        val (i2, s2) = CaptionJson.decode(CaptionJson.encode(items, style))
        assertEquals(items, i2)
        assertEquals(style, s2)
    }

    @Test fun brokenJsonFallsBackToDefaults() {
        val (items, style) = CaptionJson.decode("{oops")
        assertTrue(items.isEmpty()); assertEquals(CaptionPresets.default, style)
    }

    @Test fun presetsAreTikTokStyle() {
        assertEquals(listOf("tiktok", "contrast", "karaoke", "pulse"), CaptionPresets.all.map { it.id })
        assertTrue(CaptionPresets.all.all { it.uppercase && it.strokeEm > 0f })
    }
}
