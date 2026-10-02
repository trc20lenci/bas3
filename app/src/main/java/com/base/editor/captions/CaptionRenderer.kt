package com.base.editor.captions

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Constraints
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Единственный рисовальщик субтитров BASE: им пользуются и оверлей превью (Canvas),
 * и экспорт (рисование в Bitmap). Поэтому в готовом видео субтитры выглядят так же, как в редакторе.
 *
 * Всё считается в пикселях области рисования; размеры стиля — доли высоты кадра [DrawScope.size].
 * Никаких исключений наружу: любой сбой разметки превращается в пропуск кадра.
 */
object CaptionRenderer {
    private class Placed(val layout: TextLayoutResult, val index: Int, var x: Float = 0f, var y: Float = 0f)
    private class Line(val words: MutableList<Placed> = mutableListOf(), var width: Float = 0f, var height: Float = 0f)

    /**
     * @param centerY вертикальный центр блока в локальных координатах (px).
     * @param viewportHeight высота полного кадра — от неё считается размер шрифта
     *   (нужна, когда рисуем только «полосу» кадра).
     */
    fun DrawScope.drawCaption(
        measurer: TextMeasurer,
        item: CaptionItem,
        style: CaptionStyle,
        timeMs: Long,
        centerY: Float = size.height * style.positionY,
        viewportHeight: Float = size.height,
    ) {
        if (size.width < 8f || size.height < 8f || item.words.isEmpty()) return
        try {
            drawInternal(measurer, item, style, timeMs, centerY, viewportHeight)
        } catch (_: Exception) {
            // zero-crash: субтитры не должны ронять ни превью, ни экспорт
        }
    }

    private fun DrawScope.drawInternal(measurer: TextMeasurer, item: CaptionItem, style: CaptionStyle, timeMs: Long, centerY: Float, viewportHeight: Float) {
        val fontPx = max(6f, style.sizeFrac * viewportHeight)
        val maxW = max(fontPx, size.width * style.maxWidthFrac.coerceIn(0.3f, 1f))
        val base = TextStyle(
            fontFamily = CaptionFonts.family(style.font, style.fontWeight, style.italic),
            fontSize = fontPx.toSp(),
            letterSpacing = (style.letterSpacingEm * fontPx).toSp(),
        )

        // ── разметка: слова → строки ──
        val spaceW = fontPx * 0.28f
        val lines = mutableListOf(Line())
        item.words.forEachIndexed { i, w ->
            val text = if (style.uppercase) w.word.uppercase() else w.word
            if (text.isBlank()) return@forEachIndexed
            val layout = measurer.measure(AnnotatedString(text), base, softWrap = false, maxLines = 1,
                constraints = Constraints(), layoutDirection = layoutDirection, density = this)
            var line = lines.last()
            val needed = layout.size.width + if (line.words.isEmpty()) 0f else spaceW
            if (line.words.isNotEmpty() && line.width + needed > maxW) { line = Line(); lines += line }
            val x = line.width + if (line.words.isEmpty()) 0f else spaceW
            line.words += Placed(layout, i, x)
            line.width = x + layout.size.width
            line.height = max(line.height, layout.size.height.toFloat())
        }
        lines.removeAll { it.words.isEmpty() }
        if (lines.isEmpty()) return

        val pad = if (style.hasBackground) style.backgroundPaddingEm * fontPx else 0f
        val lineGap = fontPx * 0.08f + pad * 0.6f
        val blockH = lines.sumOf { it.height.toDouble() }.toFloat() + lineGap * (lines.size - 1)
        // блок целиком внутри области (без coerceIn с перевёрнутыми границами)
        var top = centerY - blockH / 2
        top = min(top, size.height - blockH - 2f)
        top = max(top, 2f)

        // ── анимации ──
        val enter = ((timeMs - item.startMs) / 140f).coerceIn(0f, 1f)           // появление карточки
        val alpha = easeOut(enter)
        val active = CaptionOps.activeWordIndex(item, timeMs)

        var y = top
        for (line in lines) {
            val left = (size.width - line.width) / 2
            if (style.hasBackground) {
                drawRoundRect(
                    color = Color(style.backgroundColor).withAlpha(alpha),
                    topLeft = Offset(left - pad, y - pad * 0.5f),
                    size = Size(line.width + pad * 2, line.height + pad),
                    cornerRadius = CornerRadius(style.backgroundCornerEm * fontPx),
                )
            }
            for (p in line.words) {
                val w = item.words[p.index]
                val isActive = p.index == active
                val past = p.index < active
                val color = when (style.animation) {
                    WordAnimation.NONE -> style.textColor
                    WordAnimation.KARAOKE -> if (past || isActive) style.activeColor else style.textColor
                    else -> if (isActive) style.activeColor else style.textColor
                }
                val sinceStart = (timeMs - w.startMs).coerceAtLeast(0)
                var scale = 1f
                var dy = 0f
                if (isActive) when (style.animation) {
                    WordAnimation.POP -> scale = 1f + (style.activeScale - 1f) * popCurve(sinceStart / 150f)
                    WordAnimation.BOUNCE -> {
                        val t = (sinceStart / 260f).coerceIn(0f, 1f)
                        scale = 1f + (style.activeScale - 1f) * popCurve(sinceStart / 150f)
                        dy = -fontPx * 0.22f * sin(PI.toFloat() * t)
                    }
                    else -> Unit
                }
                val topLeft = Offset(left + p.x, y + (line.height - p.layout.size.height) / 2 + dy)
                val pivot = Offset(topLeft.x + p.layout.size.width / 2f, topLeft.y + p.layout.size.height / 2f)
                withTransform({ scale(scale, scale, pivot) }) { drawWord(p.layout, style, color, topLeft, fontPx, alpha) }
            }
            y += line.height + lineGap
        }
    }

    private fun DrawScope.drawWord(layout: TextLayoutResult, style: CaptionStyle, fill: Int, topLeft: Offset, fontPx: Float, alpha: Float) {
        // 1) тень / свечение
        if ((style.shadowColor ushr 24) > 0 && style.shadowBlurEm > 0f || (style.shadowColor ushr 24) > 0 && style.shadowDyEm != 0f) {
            val sh = Shadow(Color(style.shadowColor).withAlpha(alpha), Offset(0f, style.shadowDyEm * fontPx), max(0.1f, style.shadowBlurEm * fontPx))
            drawText(layout, color = Color(fill).withAlpha(alpha), topLeft = topLeft, shadow = sh)
        }
        // 2) обводка
        if (style.strokeEm > 0f) {
            drawText(layout, color = Color(style.strokeColor).withAlpha(alpha), topLeft = topLeft,
                drawStyle = Stroke(width = style.strokeEm * fontPx * 2f, join = StrokeJoin.Round))
        }
        // 3) заливка
        drawText(layout, color = Color(fill).withAlpha(alpha), topLeft = topLeft)
    }

    private fun Color.withAlpha(a: Float) = copy(alpha = alpha * a)
    private fun easeOut(t: Float) = 1f - (1f - t).pow(3)

    /** 0 → 1 с небольшим «перелётом» (эффект pop). */
    private fun popCurve(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        val c1 = 1.70158f; val c3 = c1 + 1f
        val back = 1f + c3 * (x - 1f).pow(3) + c1 * (x - 1f).pow(2)
        return if (x >= 1f) 1f else abs(back).coerceAtMost(1.4f)
    }
}
