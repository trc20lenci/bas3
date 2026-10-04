package com.base.editor.media

import android.content.Context
import android.graphics.Bitmap
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings
import com.base.editor.pag.PagTemplateStore
import com.base.editor.pag.PagTitles
import com.base.editor.text.TextClip

/**
 * Анимированные титры (.pag) в экспорте: кадр шаблона по времени проекта берётся у PAGDecoder
 * и накладывается на видео по центру в ширину кадра.
 */
@UnstableApi
class PagBitmapOverlay(context: Context, clips: List<TextClip>, canvas: android.util.Size) : BitmapOverlay() {
    private class Item(val clip: TextClip, val frames: PagTitles.PagFrames)

    private val items: List<Item>
    private val blank: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private val settings: OverlaySettings = StaticOverlaySettings.Builder().setOverlayFrameAnchor(0f, 0f).setBackgroundFrameAnchor(0f, 0f).build()

    init {
        val store = PagTemplateStore(context)
        items = clips.mapNotNull { c ->
            val ref = c.pagTemplate ?: return@mapNotNull null
            val file = PagTitles.load(context, store, ref, c.text.ifBlank { " " }) ?: return@mapNotNull null
            PagTitles.frameBitmaps(file, canvas.width)?.let { Item(c, it) }
        }
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val t = presentationTimeUs / 1000
        val item = items.firstOrNull { it.clip.isVisibleAt(t) } ?: return blank
        val progress = (t - item.clip.startMs).toDouble() / item.clip.durationMs.coerceAtLeast(1)
        val index = (progress * item.frames.frames).toInt()
        return item.frames.frame(index) ?: blank
    }

    fun release() = items.forEach { it.frames.release() }
}
