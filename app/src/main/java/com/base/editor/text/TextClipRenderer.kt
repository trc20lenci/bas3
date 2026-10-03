package com.base.editor.text

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import kotlin.math.max
import kotlin.math.min

/**
 * Рисовальщик текстовых слоёв — общий для превью и экспорта. Исключения наружу не выходят.
 */
object TextClipRenderer {
    /** Высота кадра (в «sp»), для которой fontSizeSp = реальный размер шрифта. */
    const val REFERENCE_HEIGHT = 640f

    fun DrawScope.drawTextClip(measurer: TextMeasurer, clip: TextClip) {
        if (clip.text.isBlank() || size.width < 8f || size.height < 8f) return
        try {
            val fontPx = max(6f, clip.fontSizeSp / REFERENCE_HEIGHT * size.height)
            val maxW = max(8f, size.width * 0.90f)
            val padX = if (clip.hasBackground) fontPx * 0.45f else 0f
            val padY = if (clip.hasBackground) fontPx * 0.25f else 0f
            val layout = measurer.measure(
                AnnotatedString(clip.text),
                TextStyle(fontSize = fontPx.toSp(), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                softWrap = true,
                constraints = Constraints(maxWidth = max(1, (maxW - padX * 2).toInt())),
                layoutDirection = layoutDirection, density = this,
            )
            val w = layout.size.width.toFloat(); val h = layout.size.height.toFloat()
            // центр в долях кадра, но блок целиком остаётся в кадре (без coerceIn с перевёрнутыми границами)
            val boxW = w + padX * 2; val boxH = h + padY * 2
            val left = max(0f, min(clip.positionX * size.width - boxW / 2, size.width - boxW))
            val top = max(0f, min(clip.positionY * size.height - boxH / 2, size.height - boxH))
            if (clip.hasBackground) {
                drawRoundRect(Color(clip.backgroundColor), Offset(left, top), Size(boxW, boxH), CornerRadius(fontPx * 0.3f))
            }
            val shadow = if (clip.hasBackground) null else Shadow(Color(0xAA000000), Offset(0f, fontPx * 0.05f), fontPx * 0.12f)
            drawText(layout, color = Color(clip.textColor), topLeft = Offset(left + padX, top + padY), shadow = shadow)
        } catch (_: Exception) {
            // zero-crash
        }
    }
}
