package com.base.editor.captions.asr

import com.base.editor.captions.WordTimestamp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Участок времени в мс (относительно начала проанализированного звука). */
data class Span(val startMs: Long, val endMs: Long) { val lengthMs get() = endMs - startMs }

/** Окно для распознавания: границы + голосовые участки внутри него. */
data class SpeechWindow(val startMs: Long, val endMs: Long, val voiced: List<Span>)

/**
 * Голосовая активность и оценка времени слов.
 * Модель распознавания отдаёт только текст без таймингов, поэтому слова раскладываются
 * по реально звучащим участкам окна пропорционально длине (паузы пропускаются) — это даёт пословную
 * подсветку, привязанную к речи, но не является точным выравниванием по фонемам.
 */
object SpeechActivity {
    private const val FRAME_MS = 20
    private const val MIN_WORD_MS = 60L
    private const val RATE = 16_000
    private const val FRAME = RATE * FRAME_MS / 1000

    fun frameEnergies(pcm: ShortArray, length: Int = pcm.size): FloatArray {
        val n = length / FRAME
        return FloatArray(n) { f ->
            var s = 0.0
            for (i in f * FRAME until (f + 1) * FRAME) { val v = pcm[i] / 32768.0; s += v * v }
            sqrt(s / FRAME).toFloat()
        }
    }

    /** Участки, где есть голос. */
    fun detect(energies: FloatArray, mergeGapMs: Int = 300, minLengthMs: Int = 120): List<Span> {
        if (energies.isEmpty()) return emptyList()
        val sorted = energies.sortedArray()
        val noise = sorted[(sorted.size * 0.10).toInt()]
        val loud = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)]
        if (loud < 0.003f) return emptyList()                                   // практически тишина
        // есть чёткие паузы — порог над шумом; звук без пауз (шумный фон, непрерывная речь) — весь считается голосом
        val threshold = if (loud > noise * 3f) max(max(noise * 3f, loud * 0.08f), 0.004f) else noise * 0.5f
        val spans = mutableListOf<Span>()
        var start = -1
        for (i in energies.indices) {
            val on = energies[i] > threshold
            if (on && start < 0) start = i
            if (!on && start >= 0) { spans += Span(start * FRAME_MS.toLong(), i * FRAME_MS.toLong()); start = -1 }
        }
        if (start >= 0) spans += Span(start * FRAME_MS.toLong(), energies.size * FRAME_MS.toLong())
        // склеиваем близкие, выбрасываем щелчки
        val merged = mutableListOf<Span>()
        for (s in spans) {
            val last = merged.lastOrNull()
            if (last != null && s.startMs - last.endMs <= mergeGapMs) merged[merged.lastIndex] = Span(last.startMs, s.endMs) else merged += s
        }
        return merged.filter { it.lengthMs >= minLengthMs }
    }

    /**
     * Группирует голосовые участки в окна до [maxMs]. Слишком длинный непрерывный участок режется
     * в самом тихом месте — чтобы тайминги внутри окна не «плыли».
     */
    fun windows(voiced: List<Span>, energies: FloatArray, totalMs: Long, maxMs: Long = 12_000, padMs: Long = 150, breakGapMs: Long = 2_500): List<SpeechWindow> {
        val pieces = voiced.flatMap { splitLong(it, energies, maxMs) }
        val out = mutableListOf<SpeechWindow>()
        var cur = mutableListOf<Span>()
        fun flush() {
            if (cur.isEmpty()) return
            out += SpeechWindow(max(0, cur.first().startMs - padMs), min(totalMs, cur.last().endMs + padMs), cur.toList())
            cur = mutableListOf()
        }
        for (p in pieces) {
            if (cur.isNotEmpty() && (p.endMs - cur.first().startMs > maxMs || p.startMs - cur.last().endMs > breakGapMs)) flush()
            cur += p
        }
        flush()
        return out
    }

    private fun splitLong(s: Span, energies: FloatArray, maxMs: Long): List<Span> {
        if (s.lengthMs <= maxMs) return listOf(s)
        val from = (s.startMs + maxMs * 6 / 10) / FRAME_MS
        val to = min((s.startMs + maxMs) / FRAME_MS, energies.size.toLong() - 1)
        var cut = to
        for (f in from..to) if (energies[f.toInt()] < energies[cut.toInt()]) cut = f
        val cutMs = cut * FRAME_MS
        return listOf(Span(s.startMs, cutMs)) + splitLong(Span(cutMs, s.endMs), energies, maxMs)
    }

    /**
     * Раскладывает слова по голосовым участкам: сначала грубо — пропорционально длине слова (паузы пропускаются),
     * затем каждая внутренняя граница слов «притягивается» к ближайшему провалу энергии звука
     * (между словами и слогами речь на мгновение стихает). Так границы привязаны к реальному сигналу.
     *
     * @param energies энергии кадров по 20 мс от начала проанализированного звука (общая шкала с [voiced]).
     */
    fun assignWordTimes(words: List<String>, voiced: List<Span>, fallback: Span, energies: FloatArray? = null): List<WordTimestamp> {
        if (words.isEmpty()) return emptyList()
        val spans = voiced.filter { it.lengthMs > 0 }.ifEmpty { listOf(fallback) }
        val total = spans.sumOf { it.lengthMs }.toDouble()
        val weights = words.map { it.length + 1.0 }
        val wsum = weights.sum()

        /** Позиция в «голосовом» времени → реальное время. */
        fun toReal(voicedOffset: Double): Long {
            var rest = voicedOffset
            for (s in spans) { if (rest <= s.lengthMs) return s.startMs + rest.toLong(); rest -= s.lengthMs }
            return spans.last().endMs
        }

        var acc = 0.0
        val starts = LongArray(words.size); val ends = LongArray(words.size)
        for (i in words.indices) {
            val a = acc / wsum * total; acc += weights[i]; val b = acc / wsum * total
            starts[i] = toReal(a); ends[i] = max(toReal(b), starts[i] + 1)
        }
        if (energies != null && energies.size > 4) snapBoundaries(starts, ends, energies)
        return words.indices.map { WordTimestamp(words[it], starts[it], ends[it]) }
    }

    /** Двигает общие границы слов к минимумам сглаженной энергии в окне вокруг оценки. */
    private fun snapBoundaries(starts: LongArray, ends: LongArray, energies: FloatArray) {
        val smooth = FloatArray(energies.size) { i ->
            var sum = 0f; var n = 0
            for (k in -1..1) energies.getOrNull(i + k)?.let { sum += it; n++ }
            sum / max(1, n)
        }
        val peak = (smooth.maxOrNull() ?: 0f).coerceAtLeast(1e-6f)
        var lowerBound = starts[0]
        for (i in 0 until starts.size - 1) {
            val b = ends[i]
            if (b != starts[i + 1]) continue                              // граница на паузе — уже точная
            val shortest = min(ends[i] - starts[i], ends[i + 1] - starts[i + 1])
            val window = (shortest * 0.20).toLong().coerceIn(60L, 260L)
            val lo = max(b - window, lowerBound + MIN_WORD_MS).coerceAtLeast(0)
            val hi = min(b + window, ends[i + 1] - MIN_WORD_MS)
            if (hi <= lo) { lowerBound = b; continue }
            var best = b; var bestScore = Float.MAX_VALUE
            var t = lo
            while (t <= hi) {
                val f = (t / FRAME_MS).toInt().coerceIn(0, smooth.size - 1)
                val score = smooth[f] / peak + 0.60f * abs(t - b).toFloat() / window   // провал энергии + близость к оценке
                if (score < bestScore) { bestScore = score; best = t }
                t += FRAME_MS
            }
            ends[i] = best; starts[i + 1] = best
            lowerBound = best
        }
    }

    fun toFloats(pcm: ShortArray, fromMs: Long, toMs: Long): FloatArray {
        val a = (fromMs * RATE / 1000).toInt().coerceIn(0, pcm.size)
        val b = (toMs * RATE / 1000).toInt().coerceIn(a, pcm.size)
        return FloatArray(b - a) { pcm[a + it] / 32768f }
    }

    @Suppress("unused") private fun gap(a: Span, b: Span) = abs(b.startMs - a.endMs)
}
