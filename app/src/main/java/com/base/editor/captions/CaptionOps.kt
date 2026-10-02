package com.base.editor.captions

import kotlin.math.max
import kotlin.math.min

/** Чистая логика правки субтитров: без Android, легко тестируется. */
object CaptionOps {
    const val MIN_DURATION_MS = 200L

    /**
     * Индекс слова, звучащего в момент [timeMs]: последнее слово, начавшееся не позже этого момента
     * (в паузах между словами остаётся подсвеченным предыдущее). −1 — ещё ни одно слово не началось.
     */
    fun activeWordIndex(item: CaptionItem, timeMs: Long): Int {
        var idx = -1
        for (i in item.words.indices) if (item.words[i].startMs <= timeMs) idx = i else break
        return idx
    }

    /** Субтитр, который виден в момент [timeMs] (при перекрытии — начавшийся позже). */
    fun captionAt(items: List<CaptionItem>, timeMs: Long): CaptionItem? =
        items.lastOrNull { timeMs >= it.startMs && timeMs < it.endMs }

    /**
     * Меняет текст. Если число слов не изменилось — тайминги слов сохраняются,
     * иначе слова заново раскладываются по длительности пропорционально числу букв.
     */
    fun retext(item: CaptionItem, newText: String): CaptionItem {
        val tokens = newText.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return item.copy(text = "", words = emptyList())
        val words = if (tokens.size == item.words.size) {
            item.words.mapIndexed { i, w -> w.copy(word = tokens[i]) }
        } else distribute(tokens, item.startMs, item.endMs)
        return item.copy(text = tokens.joinToString(" "), words = words)
    }

    /** Новые границы карточки; тайминги слов линейно переносятся на новый интервал. */
    fun retime(item: CaptionItem, newStartMs: Long, newEndMs: Long): CaptionItem {
        val start = max(0, newStartMs)
        val end = max(start + MIN_DURATION_MS, newEndMs)
        val oldLen = max(1L, item.endMs - item.startMs).toDouble()
        val k = (end - start) / oldLen
        fun map(t: Long) = (start + (t - item.startMs) * k).toLong().coerceIn(start, end)
        return item.copy(startMs = start, endMs = end, words = item.words.map { it.copy(startMs = map(it.startMs), endMs = map(it.endMs)) })
    }

    /** Равномерная раскладка слов (вес — длина слова) по интервалу. */
    fun distribute(tokens: List<String>, startMs: Long, endMs: Long): List<WordTimestamp> {
        val total = max(1, tokens.sumOf { it.length + 1 }).toDouble()
        val span = max(tokens.size.toLong(), endMs - startMs).toDouble()
        var cursor = startMs.toDouble()
        return tokens.map { t ->
            val len = span * (t.length + 1) / total
            val w = WordTimestamp(t, cursor.toLong(), min(endMs.toDouble(), cursor + len).toLong())
            cursor += len; w
        }
    }

    /** Ручной субтитр: 2 секунды на указанной позиции. */
    fun manual(id: String, atMs: Long, text: String = "Текст"): CaptionItem =
        CaptionItem(id, atMs, atMs + 2000, text, distribute(listOf(text), atMs, atMs + 2000))
}
