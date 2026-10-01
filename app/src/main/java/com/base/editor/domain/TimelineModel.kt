package com.base.editor.domain

import com.base.editor.core.Clip
import com.base.editor.core.MediaType
import com.base.editor.core.Transition
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class TimelineState(val clips: List<Clip>, val transitions: List<Transition>) {
    val totalMs: Long get() = clips.maxOfOrNull { it.endMs } ?: 0L
}

/**
 * Модель таймлайна: дорожки → клипы, переходы на стыках, undo/redo.
 * Чистый Kotlin без Android-зависимостей. Время — миллисекунды.
 * Не потокобезопасна: владелец (TimelineController) обращается к ней с одного потока.
 */
class TimelineModel {
    private var clips = mutableListOf<Clip>()
    private var transitions = mutableListOf<Transition>()
    private var nextId = 1L
    private val undoStack = ArrayDeque<TimelineState>()
    private val redoStack = ArrayDeque<TimelineState>()

    fun state() = TimelineState(clips.toList(), transitions.toList())
    val totalMs get() = state().totalMs
    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    // ───────── операции ─────────
    /** Добавляет клип в конец дорожки. */
    fun addClip(row: Int, type: MediaType, uri: String, srcDurMs: Long, lengthMs: Long): Long {
        val start = clips.filter { it.row == row }.maxOfOrNull { it.endMs } ?: 0L
        val c = Clip(nextId++, row, type, start, start + max(lengthMs, MIN_CLIP_MS), 0, srcDurMs, uri)
        clips += c
        return c.id
    }

    /**
     * newStartMs — «сырая» позиция от начала жеста. Магнит к краям/нулю/extraSnapMs (курсор, −1 = нет),
     * затем ближайший свободный промежуток нужной длины: клипы можно «перепрыгивать».
     */
    fun moveClip(id: Long, newStartMs: Long, snapThresholdMs: Long, extraSnapMs: Long): Boolean {
        val c = find(id) ?: return false
        val len = c.lengthMs
        val others = clips.filter { it.row == c.row && it.id != id }.sortedBy { it.startMs }

        var desired = newStartMs
        var best = snapThresholdMs + 1
        var snapped = newStartMs
        fun trySnap(target: Long) {
            val d = abs(desired - target)
            if (d <= snapThresholdMs && d < best) { best = d; snapped = target }
        }
        trySnap(0)
        if (extraSnapMs >= 0) { trySnap(extraSnapMs); trySnap(extraSnapMs - len) }
        others.forEach { trySnap(it.endMs); trySnap(it.startMs - len) }
        if (best <= snapThresholdMs) desired = snapped

        var result = -1L
        var dist = Long.MAX_VALUE
        var gapLo = 0L
        fun consider(lo: Long, hi: Long) {
            if (hi - lo < len) return
            val cand = desired.coerceIn(lo, hi - len)
            val d = abs(cand - desired)
            if (d < dist) { dist = d; result = cand }
        }
        others.forEach { consider(gapLo, it.startMs); gapLo = max(gapLo, it.endMs) }
        consider(gapLo, INF)
        if (result < 0) return false
        replace(c.copy(startMs = result, endMs = result + len))
        sanitize()
        return true
    }

    fun trimStart(id: Long, newStartMs: Long): Boolean {
        val c = find(id) ?: return false
        val prevEnd = clips.filter { it.row == c.row && it.id != id && it.endMs <= c.startMs }.maxOfOrNull { it.endMs } ?: 0L
        val bounded = c.srcDurMs > 0
        var lo = prevEnd
        if (bounded) lo = max(lo, c.startMs - c.srcInMs)
        val hi = c.endMs - MIN_CLIP_MS
        val v = newStartMs.coerceIn(lo, max(lo, hi))
        replace(c.copy(startMs = v, srcInMs = if (bounded) c.srcInMs + (v - c.startMs) else c.srcInMs))
        sanitize()
        return true
    }

    fun trimEnd(id: Long, newEndMs: Long): Boolean {
        val c = find(id) ?: return false
        val nextStart = clips.filter { it.row == c.row && it.id != id && it.startMs >= c.endMs }.minOfOrNull { it.startMs } ?: INF
        var hi = nextStart
        if (c.srcDurMs > 0) hi = min(hi, c.startMs + (c.srcDurMs - c.srcInMs))
        val lo = c.startMs + MIN_CLIP_MS
        replace(c.copy(endMs = newEndMs.coerceIn(lo, max(lo, hi))))
        sanitize()
        return true
    }

    /** Возвращает id правой половины или −1. */
    fun split(id: Long, atMs: Long): Long {
        val c = find(id) ?: return -1
        if (atMs < c.startMs + MIN_CLIP_MS || atMs > c.endMs - MIN_CLIP_MS) return -1
        val right = c.copy(
            id = nextId++, startMs = atMs,
            srcInMs = if (c.srcDurMs > 0) c.srcInMs + (atMs - c.startMs) else c.srcInMs,
        )
        replace(c.copy(endMs = atMs))
        clips += right
        // стык с правым соседом переезжает к правой половине
        transitions = transitions.map { if (it.leftId == id) it.copy(leftId = right.id) else it }.toMutableList()
        sanitize()
        return right.id
    }

    /** На основной дорожке удаление «схлопывает» пустоту (ripple). */
    fun remove(id: Long): Boolean {
        val c = find(id) ?: return false
        clips.removeAll { it.id == id }
        if (c.row == 0) {
            clips = clips.map { if (it.row == 0 && it.startMs >= c.startMs) it.copy(startMs = it.startMs - c.lengthMs, endMs = it.endMs - c.lengthMs) else it }.toMutableList()
        }
        sanitize()
        return true
    }

    // ───────── переходы ─────────
    fun rightNeighbour(leftId: Long): Clip? {
        val l = find(leftId) ?: return null
        if (l.row != 0) return null
        return clips.firstOrNull { it.row == 0 && it.id != l.id && it.startMs == l.endMs }
    }

    /** Максимальная длительность перехода после клипа leftId (0 — стыка нет). */
    fun maxTransitionMs(leftId: Long): Long = rightNeighbour(leftId)?.lengthMs ?: 0L

    /** shaderId == null — снять переход. false, если стыка нет. */
    fun setTransition(leftId: Long, shaderId: String?, durationMs: Long): Boolean {
        val r = rightNeighbour(leftId) ?: return false
        transitions.removeAll { it.leftId == leftId }
        if (shaderId != null) transitions += Transition(leftId, r.id, shaderId, durationMs)
        sanitize()
        return true
    }

    // ───────── история ─────────
    /** Вызывается один раз ПЕРЕД жестом/операцией. */
    fun checkpoint() {
        undoStack.addLast(state())
        if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        redoStack.clear()
    }

    fun discardCheckpointIfNoop() {
        if (undoStack.isNotEmpty() && undoStack.last() == state()) undoStack.removeLast()
    }

    fun undo(): Boolean {
        val s = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(state()); restore(s); return true
    }

    fun redo(): Boolean {
        val s = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(state()); restore(s); return true
    }

    // ───────── сохранение (формат V1; совместим с ранее сохранёнными проектами) ─────────
    fun serialize(): String = buildString {
        append("V1\t").append(nextId).append('\n')
        clips.forEach { c ->
            append(c.id).append('\t').append(c.row).append('\t').append(c.type.code).append('\t')
                .append(c.startMs).append('\t').append(c.endMs).append('\t').append(c.srcInMs).append('\t')
                .append(c.srcDurMs).append('\t').append(c.uri).append('\n')
        }
        transitions.forEach { t -> append("T\t${t.leftId}\t${t.rightId}\t${t.shaderId}\t${t.durationMs}\n") }
    }

    fun load(data: String): Boolean {
        val newClips = mutableListOf<Clip>()
        val newTr = mutableListOf<Transition>()
        var next = 1L
        var header = false
        for (line in data.lineSequence()) {
            if (line.isEmpty()) continue
            val f = line.split('\t', limit = 8)
            try {
                if (!header) {
                    if (f.size < 2 || f[0] != "V1") return false
                    next = f[1].toLong(); header = true; continue
                }
                if (f[0] == "T") {
                    val t = line.split('\t')
                    if (t.size >= 5) newTr += Transition(t[1].toLong(), t[2].toLong(), t[3], t[4].toLong())
                    continue
                }
                if (f.size < 8) continue
                newClips += Clip(f[0].toLong(), f[1].toInt(), MediaType.of(f[2].toInt()), f[3].toLong(), f[4].toLong(), f[5].toLong(), f[6].toLong(), f[7])
            } catch (_: NumberFormatException) { return false }
        }
        if (!header) return false
        clips = newClips; transitions = newTr; nextId = next
        undoStack.clear(); redoStack.clear()
        sanitize()
        return true
    }

    // ───────── внутреннее ─────────
    private fun find(id: Long) = clips.firstOrNull { it.id == id }
    private fun replace(c: Clip) { val i = clips.indexOfFirst { it.id == c.id }; if (i >= 0) clips[i] = c }
    private fun restore(s: TimelineState) { clips = s.clips.toMutableList(); transitions = s.transitions.toMutableList() }

    /** Убирает переходы, потерявшие стык, и зажимает длительность в [MIN_TRANSITION_MS, длина входящего клипа]. */
    private fun sanitize() {
        transitions = transitions.mapNotNull { t ->
            val l = find(t.leftId); val r = find(t.rightId)
            if (l == null || r == null || l.row != 0 || r.row != 0 || l.endMs != r.startMs) null
            else t.copy(durationMs = t.durationMs.coerceIn(MIN_TRANSITION_MS, max(MIN_TRANSITION_MS, r.lengthMs)))
        }.toMutableList()
    }

    companion object {
        const val MIN_CLIP_MS = 100L
        const val MIN_TRANSITION_MS = 200L
        private const val INF = Long.MAX_VALUE / 4
        private const val MAX_HISTORY = 100
    }
}
