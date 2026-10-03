package com.base.editor.captions.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class SpeechActivityTest {
    private val rate = 16_000
    private fun burst(pcm: ShortArray, a: Double, b: Double) {
        for (i in (a * rate).toInt() until (b * rate).toInt()) pcm[i] = (sin(i * 0.12) * 8000).toInt().toShort()
    }

    @Test fun detectsVoicedSpansAndSkipsPause() {
        val pcm = ShortArray(rate * 7); burst(pcm, 0.5, 2.0); burst(pcm, 2.6, 5.5)
        val v = SpeechActivity.detect(SpeechActivity.frameEnergies(pcm))
        assertEquals(2, v.size)
        assertTrue(v[0].startMs in 400..600 && v[1].endMs in 5400..5700)
    }

    @Test fun wordTimesAreMonotonicAndInsideVoice() {
        val voiced = listOf(Span(500, 2000), Span(2600, 5500))
        val t = SpeechActivity.assignWordTimes(listOf("привет", "это", "тест", "субтитров"), voiced, Span(350, 5650))
        assertTrue(t.zipWithNext().all { (a, b) -> a.endMs <= b.startMs + 1 })
        assertTrue(t.first().startMs >= 500 && t.last().endMs <= 5500)
    }

    @Test fun longContinuousVoiceIsSplitIntoShortWindows() {
        val pcm = ShortArray(rate * 30) { (sin(it * 0.12) * 8000).toInt().toShort() }
        val e = SpeechActivity.frameEnergies(pcm)
        val w = SpeechActivity.windows(SpeechActivity.detect(e), e, 30_000)
        assertTrue(w.size >= 3 && w.all { it.endMs - it.startMs <= 12_400 })
    }
}
