package com.base.editor.captions

import java.util.UUID

/** Режет поток распознанных слов на удобные для чтения карточки. */
class CaptionSegmenter(
    private val maxWords: Int = 5,
    private val maxChars: Int = 26,
    private val maxDurationMs: Long = 1200,
    private val maxGapMs: Long = 600,
    private val tailMs: Long = 150,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    fun segment(words: List<WordTimestamp>): List<CaptionItem> {
        val sorted = words.filter { it.word.isNotBlank() }.sortedBy { it.startMs }
        val out = mutableListOf<CaptionItem>()
        var cur = mutableListOf<WordTimestamp>()
        fun flush() {
            if (cur.isEmpty()) return
            out += CaptionItem(newId(), cur.first().startMs, cur.last().endMs, cur.joinToString(" ") { it.word }, cur.toList())
            cur = mutableListOf()
        }
        for (w in sorted) {
            if (cur.isNotEmpty()) {
                val chars = cur.sumOf { it.word.length + 1 } + w.word.length
                val breakHere = cur.size >= maxWords || chars > maxChars ||
                    w.startMs - cur.last().endMs > maxGapMs || w.endMs - cur.first().startMs > maxDurationMs ||
                    cur.last().word.lastOrNull() in SENTENCE_END
                if (breakHere) flush()
            }
            cur += w
        }
        flush()
        // карточка остаётся на экране чуть дольше последнего слова, но не наезжает на следующую
        return out.mapIndexed { i, c ->
            val limit = out.getOrNull(i + 1)?.startMs ?: Long.MAX_VALUE
            c.copy(endMs = minOf(c.endMs + tailMs, limit).coerceAtLeast(c.endMs))
        }
    }

    private companion object { val SENTENCE_END = setOf('.', '?', '!', '…') }
}
