package com.base.editor.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings
import com.base.editor.captions.CaptionItem
import com.base.editor.captions.CaptionOps
import com.base.editor.captions.CaptionRenderer
import com.base.editor.captions.CaptionRenderer.drawCaption
import com.base.editor.captions.CaptionStyle
import kotlin.math.max
import kotlin.math.min

/** Субтитры для экспорта: список карточек + стиль. */
data class CaptionTrack(val items: List<CaptionItem>, val style: CaptionStyle)

/**
 * Мост экспорта: на каждом кадре рисует активный субтитр (тем же [CaptionRenderer], что и превью)
 * в прозрачный Bitmap-«полосу» шириной в кадр, а Media3 накладывает её на видео перед кодированием.
 * Полоса вместо полного кадра — чтобы не гонять мегабайты на каждом кадре.
 * Bitmap переиспользуется; Media3 замечает смену содержимого по generationId и загружает текстуру заново.
 */
@UnstableApi
class CaptionBitmapOverlay(context: Context, private val track: CaptionTrack, private val canvas: android.util.Size) : BitmapOverlay() {
    private val frameW = max(2, canvas.width)
    private val frameH = max(2, canvas.height)
    private val bandH = max(2, min(frameH, (frameH * BAND_FRAC).toInt()))
    private val desiredCenter = track.style.positionY.coerceIn(0.05f, 0.95f) * frameH
    private val bandTop = max(0f, min(desiredCenter - bandH / 2f, (frameH - bandH).toFloat()))

    private val measurer = TextMeasurer(createFontFamilyResolver(context.applicationContext), Density(1f), LayoutDirection.Ltr, 64)
    private val bitmap: Bitmap = Bitmap.createBitmap(frameW, bandH, Bitmap.Config.ARGB_8888)
    private val blank: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)   // нет субтитра — прозрачная точка
    private val drawScope = CanvasDrawScope()
    private val imageBitmap = bitmap.asImageBitmap()
    private val composeCanvas = Canvas(imageBitmap)
    private var lastKey = Long.MIN_VALUE

    private val settings: OverlaySettings = StaticOverlaySettings.Builder()
        .setOverlayFrameAnchor(0f, 0f)
        // центр полосы в координатах кадра: y вверх от −1 до 1
        .setBackgroundFrameAnchor(0f, 1f - 2f * (bandTop + bandH / 2f) / frameH)
        .build()

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val timeMs = presentationTimeUs / 1000
        val item = CaptionOps.captionAt(track.items, timeMs) ?: return blank
        val frame = timeMs * FPS / 1000                                  // анимации обновляем покадрово
        val key = (item.id.hashCode().toLong() shl 32) xor frame
        if (key != lastKey) {
            lastKey = key
            bitmap.eraseColor(Color.TRANSPARENT)
            drawScope.draw(Density(1f), LayoutDirection.Ltr, composeCanvas, Size(frameW.toFloat(), bandH.toFloat())) {
                drawCaption(measurer, item, track.style, timeMs, centerY = desiredCenter - bandTop, viewportHeight = frameH.toFloat())
            }
        }
        return bitmap
    }

    private companion object { const val BAND_FRAC = 0.38f; const val FPS = 30L }
}
