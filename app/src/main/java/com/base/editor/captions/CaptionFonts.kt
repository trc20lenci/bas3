package com.base.editor.captions

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.base.editor.R

/** Семейства шрифтов BASE. Вариативные шрифты настраиваются по оси насыщенности. */
@OptIn(ExperimentalTextApi::class)
object CaptionFonts {
    private val cache = HashMap<String, FontFamily>()

    fun family(font: CaptionFont, weight: Int, italic: Boolean): FontFamily =
        cache.getOrPut("$font/$weight/$italic") {
            val w = weight.coerceIn(100, 900)
            val style = if (italic) FontStyle.Italic else FontStyle.Normal
            fun variable(res: Int, min: Int, max: Int) = FontFamily(
                Font(res, FontWeight(w.coerceIn(min, max)), style, variationSettings = FontVariation.Settings(FontVariation.weight(w.coerceIn(min, max)))),
            )
            when (font) {
                CaptionFont.MONTSERRAT -> variable(R.font.montserrat, 100, 900)
                CaptionFont.OSWALD -> variable(R.font.oswald, 200, 700)
                CaptionFont.RUBIK -> variable(R.font.rubik, 300, 900)
                CaptionFont.RUSSO -> FontFamily(Font(R.font.russo_one, FontWeight.Normal, style))
                CaptionFont.PACIFICO -> FontFamily(Font(R.font.pacifico, FontWeight.Normal, style))
            }
        }

    /** Допустимый диапазон насыщенности для слайдера (у статических шрифтов — нет выбора). */
    fun weightRange(font: CaptionFont): IntRange? = when (font) {
        CaptionFont.MONTSERRAT -> 500..900
        CaptionFont.OSWALD -> 400..700
        CaptionFont.RUBIK -> 400..900
        else -> null
    }
}
