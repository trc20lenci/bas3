package com.base.editor.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.rememberTextMeasurer
import com.base.editor.captions.CaptionItem
import com.base.editor.captions.CaptionRenderer.drawCaption
import com.base.editor.captions.CaptionStyle

/**
 * Субтитры поверх плеера. Область рисования повторяет границы кадра (letterbox), поэтому размеры и позиция
 * совпадают с готовым видео. Позиция берётся из [timeMs] — текущего положения курсора.
 */
@Composable
fun CaptionOverlay(item: CaptionItem?, style: CaptionStyle, timeMs: Long, videoAspect: Float, modifier: Modifier = Modifier) {
    if (item == null) return
    val measurer = rememberTextMeasurer(cacheSize = 64)
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.aspectRatio(videoAspect.coerceIn(0.2f, 5f))) {
            drawCaption(measurer, item, style, timeMs)
        }
    }
}
