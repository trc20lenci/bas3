package com.base.editor.pag

import android.content.Context
import android.graphics.Bitmap
import org.libpag.PAGFile
import org.libpag.PAGPlayer
import org.libpag.PAGSurface

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

    /** Кадры шаблона для экспорта: офскрин-рендер PAGPlayer → Bitmap по доле длительности (0..1). */
    fun frameRenderer(file: PAGFile, targetWidth: Int): PagFrames? = runCatching {
        val w = targetWidth.coerceAtLeast(2)
        val h = (w.toFloat() * file.height() / file.width().coerceAtLeast(1)).toInt().coerceAtLeast(2)
        val surface = PAGSurface.MakeOffscreen(w, h) ?: return null
        val player = PAGPlayer().apply { this.surface = surface; composition = file }
        PagFrames(player, surface)
    }.getOrNull()

    class PagFrames(private val player: PAGPlayer, private val surface: PAGSurface) {
        fun frame(progress: Double): Bitmap? = runCatching {
            player.progress = progress.coerceIn(0.0, 1.0)
            player.flush()
            surface.makeSnapshot()
        }.getOrNull()

        fun release() { runCatching { player.release() } }
    }
}
