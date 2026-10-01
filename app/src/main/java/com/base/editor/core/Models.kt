package com.base.editor.core

enum class MediaType(val code: Int) {
    VIDEO(0), IMAGE(1), AUDIO(2), TEXT(3);
    companion object { fun of(code: Int) = entries.firstOrNull { it.code == code } ?: VIDEO }
}

/** Клип на шкале времени (зеркало base::Clip из C++). Время — миллисекунды. */
data class Clip(
    val id: Long,
    val row: Int,
    val type: MediaType,
    val startMs: Long,
    val endMs: Long,
    val srcInMs: Long,
    val srcDurMs: Long,
    val uri: String,
) {
    val lengthMs get() = endMs - startMs
}

/**
 * Переход «в» клип rightId на стыке с левым соседом leftId.
 * Занимает первые durationMs входящего клипа: уходящий клип в этот момент показан последним кадром.
 */
data class Transition(val leftId: Long, val rightId: Long, val shaderId: String, val durationMs: Long)

/** Файл, выбранный в галерее. */
data class PickedMedia(val uri: String, val type: MediaType, val durationMs: Long) {
    fun encode() = "${type.code}|$durationMs|$uri"
    companion object {
        fun decode(s: String): PickedMedia {
            val (t, d, u) = s.split("|", limit = 3)
            return PickedMedia(u, MediaType.of(t.toInt()), d.toLong())
        }
    }
}

data class ProjectMeta(
    val id: String,
    val name: String,
    val modifiedAt: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val hasVideo: Boolean,
    val thumbPath: String?,
)

const val IMAGE_DEFAULT_MS = 3000L
