package com.base.editor.pag

import android.content.Context
import android.graphics.Bitmap
import org.libpag.PAGFile

/** Загрузка шаблона и подстановка текста пользователя. */
object PagTitles {
    /** null — шаблон не найден/повреждён (вызывающий код показывает обычный текст). */
    fun load(context: Context, store: PagTemplateStore, ref: String, text: String): PAGFile? = runCatching {
        val file = when {
            ref.startsWith("asset:") -> PAGFile.Load(context.assets, "pag/" + ref.removePrefix("asset:"))
            else -> store.absolutePath(ref)?.let { PAGFile.Load(it) }
        } ?: return null
        replaceFirstText(file, text)
        file
    }.getOrNull()

    /**
     * В libPAG текст меняют через PAGText из шаблона: берём данные первого редактируемого текста,
     * подставляем строку пользователя и возвращаем обратно (стиль и анимация шаблона сохраняются).
     */
    fun replaceFirstText(file: PAGFile, text: String) {
        if (file.numTexts() <= 0) return
        val data = file.getTextData(0)
        data.text = text
        file.replaceText(0, data)
    }

    /** Кадры шаблона для экспорта: PAGDecoder отдаёт Bitmap по номеру кадра. */
    fun frameBitmaps(file: PAGFile, maxWidth: Int): PagFrames? = runCatching {
        val scale = (maxWidth.toFloat() / file.width()).coerceIn(0.05f, 1f)
        val decoder = org.libpag.PAGDecoder.Make(file, 30f, scale) ?: return null
        PagFrames(decoder)
    }.getOrNull()

    class PagFrames(private val decoder: org.libpag.PAGDecoder) {
        val frames get() = decoder.numFrames()
        val frameRate get() = decoder.frameRate()
        val width get() = decoder.width()
        val height get() = decoder.height()
        fun frame(index: Int): Bitmap? = runCatching { decoder.frameAtIndex(index.coerceIn(0, frames - 1)) }.getOrNull()
        fun release() = runCatching { decoder.release() }
    }
}
