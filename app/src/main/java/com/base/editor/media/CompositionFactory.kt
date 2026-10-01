package com.base.editor.media

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.base.editor.core.Clip
import com.base.editor.core.MediaType
import com.base.editor.core.TransitionCatalog
import com.base.editor.domain.TimelineState
import com.base.editor.media.gl.TailCaptureEffect
import com.base.editor.media.gl.TransitionBridge
import com.base.editor.media.gl.TransitionEffect

/** Параметры сборки одной композиции. */
data class CompositionRequest(
    val state: TimelineState,
    val canvas: Size,
    /** Отключить звук (экспорт); в превью громкость регулируется у плеера. */
    val removeAudio: Boolean = false,
    /** true — без шейдерных эффектов (аварийный режим после сбоя декодера/GL). */
    val safeMode: Boolean = false,
    val onTransitionFallback: (String) -> Unit = {},
)

/**
 * Единственное место, где модель таймлайна превращается в Composition Media3.
 * Её используют и плеер превью, и экспорт — картинка совпадает.
 *
 * Основная дорожка → одна последовательность EditedMediaItemSequence:
 *  • обрезка клипа — ClippingConfiguration (In/Out в исходнике);
 *  • зазоры между клипами — addGap (чёрный кадр + тишина);
 *  • каждый клип приводится к общему холсту (Presentation, вписывание с полосами);
 *  • на стыках — TailCaptureEffect у уходящего и TransitionEffect у входящего клипа.
 */
@UnstableApi
class CompositionFactory(private val context: Context, private val catalog: TransitionCatalog) {

    /** null — на основной дорожке нет клипов. Безопасно вызывать с фонового потока. */
    fun build(req: CompositionRequest): Composition? {
        val main = req.state.clips.filter { it.row == 0 }.sortedBy { it.startMs }
        if (main.isEmpty()) return null

        val bridges = HashMap<Long, TransitionBridge>()          // ключ — id левого клипа стыка
        val inbound = req.state.transitions.associateBy { it.rightId }
        val outbound = req.state.transitions.associateBy { it.leftId }

        val seq = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
        var cursorMs = 0L
        for (clip in main) {
            if (clip.startMs > cursorMs) seq.addGap((clip.startMs - cursorMs) * 1000)

            val effects = mutableListOf<Effect>(Presentation.createForWidthAndHeight(req.canvas.width, req.canvas.height, Presentation.LAYOUT_SCALE_TO_FIT))
            if (!req.safeMode) {
                inbound[clip.id]?.let { t ->
                    catalog.spec(t.shaderId)?.let { spec ->
                        effects += TransitionEffect(spec, bridges.getOrPut(t.leftId) { TransitionBridge() }, t.durationMs * 1000, req.onTransitionFallback)
                    } ?: Log.w(TAG, "переход ${t.shaderId} не найден в каталоге")
                }
                // захват хвоста — после перехода, чтобы в мост попал финальный кадр клипа
                outbound[clip.id]?.let { effects += TailCaptureEffect(bridges.getOrPut(clip.id) { TransitionBridge() }) }
            }
            seq.addItem(editedItem(clip, effects, req.removeAudio))
            cursorMs = clip.endMs
        }
        return Composition.Builder(seq.build()).build()
    }

    private fun editedItem(c: Clip, videoEffects: List<Effect>, removeAudio: Boolean): EditedMediaItem {
        val item = MediaItem.Builder().setUri(c.uri)
        val edited: EditedMediaItem.Builder
        if (c.type == MediaType.IMAGE) {
            item.setImageDurationMs(c.lengthMs)
            edited = EditedMediaItem.Builder(item.build()).setDurationUs(c.lengthMs * 1000).setFrameRate(FRAME_RATE)
        } else {
            item.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(c.srcInMs)
                    .setEndPositionMs(c.srcInMs + c.lengthMs)
                    .build(),
            )
            edited = EditedMediaItem.Builder(item.build())
        }
        return edited.setEffects(Effects(emptyList(), videoEffects)).setRemoveAudio(removeAudio).build()
    }

    companion object {
        private const val TAG = "BaseComposition"
        const val FRAME_RATE = 30

        /** Холст по пропорциям первого клипа; short — длина короткой стороны (480/720/1080). */
        fun canvasFor(aspect: Float, short: Int): Size {
            fun even(v: Int) = (v and 1.inv()).coerceAtLeast(2)
            return if (aspect >= 1f) Size(even((short * aspect).toInt()), even(short)) else Size(even(short), even((short / aspect).toInt()))
        }
    }
}
