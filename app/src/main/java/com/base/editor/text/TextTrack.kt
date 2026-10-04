package com.base.editor.text

/** Дорожка текстовых слоёв: чистая логика без Android. Слои могут перекрываться по времени. */
class TextTrack {
    private var clips: List<TextClip> = emptyList()

    fun all(): List<TextClip> = clips

    fun add(clip: TextClip): TextClip {
        val c = normalize(clip)
        clips = (clips + c).sortedBy { it.startMs }
        return c
    }

    fun update(clip: TextClip): Boolean {
        if (clips.none { it.id == clip.id }) return false
        val c = normalize(clip)
        clips = clips.map { if (it.id == c.id) c else it }.sortedBy { it.startMs }
        return true
    }

    fun remove(id: String): Boolean {
        val before = clips.size
        clips = clips.filterNot { it.id == id }
        return clips.size != before
    }

    fun find(id: String) = clips.firstOrNull { it.id == id }

    /** Слои, видимые в момент [timeMs] (порядок = порядок наложения: поздние сверху). */
    fun activeAt(timeMs: Long): List<TextClip> = clips.filter { it.isVisibleAt(timeMs) }

    fun load(items: List<TextClip>) { clips = items.map(::normalize).sortedBy { it.startMs } }

    private fun normalize(c: TextClip) = c.copy(
        startMs = c.startMs.coerceAtLeast(0),
        durationMs = c.durationMs.coerceIn(TextClip.MIN_DURATION_MS, TextClip.MAX_DURATION_MS),
        positionX = c.positionX.coerceIn(0f, 1f),
        positionY = c.positionY.coerceIn(0f, 1f),
        fontSizeSp = c.fontSizeSp.coerceIn(8f, 160f),
        rotationDeg = ((c.rotationDeg % 360f) + 360f) % 360f,
    )
}
