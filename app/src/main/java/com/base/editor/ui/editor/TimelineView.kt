package com.base.editor.ui.editor

import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Constraints
import android.util.Log
import com.base.editor.captions.CaptionItem
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.base.editor.core.Clip
import com.base.editor.core.MediaType
import com.base.editor.core.Transition
import com.base.editor.data.Format
import com.base.editor.data.Thumbs
import com.base.editor.ui.theme.BaseColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Мобильный многодорожечный холст: курсор (playhead) неподвижен по центру, шкала едет под ним.
 *  • сдвиг пальцем по пустому месту/невыбранному клипу — скраб (+ инерция)
 *  • щипок двумя пальцами — зум
 *  • перетаскивание ВЫБРАННОГО клипа — перемещение (с магнитом и «перепрыгиванием» соседей)
 *  • тянуть за белые ручки по краям выбранного клипа — Trim
 */
@Composable
fun TimelineView(
    clips: List<Clip>, transitions: List<Transition>, captions: List<CaptionItem>, selectedId: Long?, playheadMs: Long, totalMs: Long, pxPerSecDp: Float,
    actions: TimelineActions, modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val scope = rememberCoroutineScope()
    var widthPx by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }          // перерисовка после подгрузки миниатюр

    val geo = Geo(density, widthPx.toFloat(), playheadMs, pxPerSecDp)
    val cur by rememberUpdatedState(TlState(geo, clips, transitions, captions, selectedId, totalMs))
    val act by rememberUpdatedState(actions)

    // Очередь миниатюр: draw только регистрирует недостающие ключи, загрузка — здесь.
    val pending = remember { LinkedHashMap<String, ThumbReq>() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60)
            val batch = synchronized(pending) { pending.values.take(6).also { b -> b.forEach { pending.remove(it.key) } } }
            if (batch.isEmpty()) continue
            batch.forEach { Thumbs.load(ctx, it.uri, it.type, it.bucketMs, it.h) }
            tick++
        }
    }

    val flingRef = remember { arrayOfNulls<Job>(1) }

    Canvas(
        modifier.fillMaxWidth().height(TL_HEIGHT_DP.dp)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(Unit) {
                awaitEachGesture {
                    flingRef[0]?.cancel()
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = viewConfiguration.touchSlop
                    val s0 = cur
                    val hit = s0.hit(down.position)
                    var mode = Mode.PENDING
                    var startClip: Clip? = null
                    var ph = s0.geo.playheadMs.toDouble()
                    var pinchLast = 0f
                    val tracker = VelocityTracker().also { it.addPosition(down.uptimeMillis, down.position) }
                    var activeId: PointerId = down.id

                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {                                   // зум
                            val dist = (pressed[0].position - pressed[1].position).getDistance()
                            if (mode != Mode.ZOOM) { mode = Mode.ZOOM; pinchLast = dist; act.scrubStart() }
                            else if (pinchLast > 0f && dist > 0f) { act.setZoom(cur.geo.pxPerSecDp * dist / pinchLast); pinchLast = dist }
                            ev.changes.forEach { it.consume() }
                            continue
                        }
                        val ch = ev.changes.firstOrNull { it.id == activeId } ?: pressed[0].also { activeId = it.id }
                        if (mode == Mode.ZOOM) { ch.consume(); continue }
                        tracker.addPosition(ch.uptimeMillis, ch.position)
                        val total = ch.position - down.position
                        val g = cur.geo

                        if (mode == Mode.PENDING && abs(total.x) > slop) {
                            mode = when (hit) {
                                is Hit.Handle -> if (hit.start) Mode.TRIM_START else Mode.TRIM_END
                                is Hit.Body -> if (hit.clip.id == s0.selectedId) Mode.MOVE else Mode.SCROLL
                                else -> Mode.SCROLL
                            }
                            when (mode) {
                                Mode.SCROLL -> act.scrubStart()
                                else -> { startClip = (hit as? Hit.Body)?.clip ?: (hit as Hit.Handle).clip; act.editBegin() }
                            }
                        }
                        if (mode == Mode.PENDING) continue
                        val dMs = (total.x / g.pxPerMs).toLong()
                        when (mode) {
                            Mode.SCROLL -> {
                                ph = (ph - (ch.position.x - ch.previousPosition.x) / g.pxPerMs).coerceIn(0.0, cur.totalMs.toDouble())
                                act.scrubTo(ph.toLong())
                            }
                            Mode.MOVE -> act.moveClip(startClip!!.id, startClip.startMs + dMs)
                            Mode.TRIM_START -> act.trimStart(startClip!!.id, startClip.startMs + dMs)
                            Mode.TRIM_END -> act.trimEnd(startClip!!.id, startClip.endMs + dMs)
                            else -> Unit
                        }
                        ch.consume()
                    }

                    when (mode) {
                        Mode.PENDING -> when (hit) {                                // тап
                            is Hit.Body -> act.select(hit.clip.id)
                            is Hit.Handle -> act.select(hit.clip.id)
                            is Hit.Junction -> act.openTransitions(hit.leftId)
                            Hit.Plus -> act.addMedia()
                            is Hit.Caption -> act.openCaption(hit.id)
                            Hit.AudioSlot -> act.addAudio()
                            Hit.TextSlot -> act.addText()
                            Hit.None -> act.select(null)
                        }
                        Mode.SCROLL -> {                                            // инерция
                            val vMs = -tracker.calculateVelocity().x / cur.geo.pxPerMs
                            flingRef[0] = scope.launch {
                                animateDecay(ph.toFloat(), vMs.toFloat(), FloatExponentialDecaySpec(frictionMultiplier = 1.6f)) { v, _ -> act.scrubTo(v.toLong()) }
                            }
                        }
                        Mode.MOVE, Mode.TRIM_START, Mode.TRIM_END -> act.editEnd()
                        Mode.ZOOM -> Unit
                    }
                }
            },
    ) {
        tick.let { }                     // подписка на перерисовку
        // zero-crash: ошибка отрисовки не должна ронять приложение при любых зумах/скроллах
        try { drawTimeline(cur, measurer, pending) } catch (e: Exception) { Log.e("BaseTimeline", "drawTimeline", e) }
    }
}

// ───────────────────────── геометрия и хит-тест ─────────────────────────

private const val TL_HEIGHT_DP = 214
private enum class Mode { PENDING, SCROLL, MOVE, TRIM_START, TRIM_END, ZOOM }
private class ThumbReq(val key: String, val uri: String, val type: MediaType, val bucketMs: Long, val h: Int)

private sealed interface Hit {
    class Body(val clip: Clip) : Hit
    class Handle(val clip: Clip, val start: Boolean) : Hit
    class Junction(val leftId: Long) : Hit
    data object Plus : Hit
    class Caption(val id: String) : Hit
    data object AudioSlot : Hit
    data object TextSlot : Hit
    data object None : Hit
}

/** Всё в пикселях. x(t) = центр + (t − playhead)·pxPerMs. */
class Geo(private val d: Density, val width: Float, val playheadMs: Long, val pxPerSecDp: Float) {
    val pxPerMs = with(d) { pxPerSecDp.dp.toPx() } / 1000f
    val centerX = width / 2f
    fun x(t: Long) = centerX + (t - playheadMs) * pxPerMs
    private fun dp(v: Int) = with(d) { v.dp.toPx() }
    val rulerH = dp(28); val mainTop = dp(36); val mainH = dp(64)
    val audioTop = mainTop + mainH + dp(10); val slotH = dp(44)
    val textTop = audioTop + slotH + dp(8)
    val junctionR = dp(15); val handleW = dp(14); val handleSlop = dp(14); val plusSize = dp(48); val corner = dp(8)
    val total: Float get() = textTop + slotH
}

private class TlState(val geo: Geo, val clips: List<Clip>, val transitions: List<Transition>, val captions: List<CaptionItem>, val selectedId: Long?, val totalMs: Long) {
    /** Стыки соседних клипов основной дорожки: (левый клип, правый клип). Кнопки скрыты у выбранного клипа. */
    fun junctions(): List<Pair<Clip, Clip>> {
        val main = clips.filter { it.row == 0 }
        return main.mapNotNull { l ->
            val r = main.firstOrNull { it.startMs == l.endMs && it.id != l.id } ?: return@mapNotNull null
            if (l.id == selectedId || r.id == selectedId) null else l to r
        }
    }

    fun hit(p: Offset): Hit {
        val g = geo
        if (p.y in g.mainTop..(g.mainTop + g.mainH)) {
            junctions().forEach { (l, _) ->
                if (abs(p.x - g.x(l.endMs)) <= g.junctionR + g.handleSlop / 2) return Hit.Junction(l.id)
            }
            clips.filter { it.row == 0 }.forEach { c ->
                val l = g.x(c.startMs); val r = g.x(c.endMs)
                if (c.id == selectedId) {
                    val hw = min(g.handleW, (r - l) / 4)
                    if (p.x in (l - g.handleSlop)..(l + hw + g.handleSlop / 2)) return Hit.Handle(c, true)
                    if (p.x in (r - hw - g.handleSlop / 2)..(r + g.handleSlop)) return Hit.Handle(c, false)
                }
                if (p.x in l..r) return Hit.Body(c)
            }
            val plusL = g.x(clips.filter { it.row == 0 }.maxOfOrNull { it.endMs } ?: 0) + g.handleSlop
            if (p.x in plusL..(plusL + g.plusSize + g.handleSlop)) return Hit.Plus
        }
        if (p.y in g.audioTop..(g.audioTop + g.slotH) && p.x >= g.x(0)) return Hit.AudioSlot
        if (p.y in g.textTop..(g.textTop + g.slotH)) {
            captions.firstOrNull { p.x in g.x(it.startMs)..g.x(it.endMs) }?.let { return Hit.Caption(it.id) }
            if (p.x >= g.x(0)) return Hit.TextSlot
        }
        return Hit.None
    }
}

// ───────────────────────── отрисовка ─────────────────────────

private fun DrawScope.drawTimeline(s: TlState, measurer: TextMeasurer, pending: LinkedHashMap<String, ThumbReq>) {
    val g = s.geo
    if (g.width <= 0f) return
    val small = TextStyle(color = Color.White.copy(alpha = .6f), fontSize = 11.sp)

    // линейка
    val steps = listOf(0.5f, 1f, 2f, 5f, 10f, 30f, 60f, 300f)
    val stepSec = steps.firstOrNull { it * g.pxPerMs * 1000f >= 70.dp.toPx() } ?: steps.last()
    val stepMs = (stepSec * 1000).toLong()
    val firstT = max(0L, ((g.playheadMs - (g.centerX / g.pxPerMs).toLong()) / stepMs) * stepMs)
    var t = firstT
    var guard = 0
    while (g.x(t) < g.width + 60f && guard++ < 600) {
        val x = g.x(t)
        if (x > -60f) {
            val label = Format.duration(t)
            safeText(measurer, label, Offset(x - 16.dp.toPx(), 2.dp.toPx()), small)
            drawCircle(Color.White.copy(alpha = .35f), 1.5.dp.toPx(), Offset(g.x(t + stepMs / 2), 22.dp.toPx()))
        }
        t += stepMs
    }

    // слоты аудио / текст
    val slotFrom = g.x(0)
    listOf(g.audioTop to "+  Добавить аудио", g.textTop to "+  Добавить текст").forEach { (top, label) ->
        drawRoundRect(BaseColors.DarkSlot, Offset(max(slotFrom, -g.corner), top), Size(g.width - max(slotFrom, -g.corner) + g.corner, g.slotH), CornerRadius(g.corner))
        val isText = top == g.textTop
        if (!(isText && s.captions.isNotEmpty()))
            safeText(measurer, label, Offset(max(slotFrom, 0f) + 16.dp.toPx(), top + 13.dp.toPx()), TextStyle(color = Color.White.copy(alpha = .85f), fontSize = 15.sp))
    }

    // блоки субтитров в нижней строке
    s.captions.forEach { c ->
        val l = g.x(c.startMs); val r = g.x(c.endMs)
        if (r < -20f || l > g.width + 20f || r - l < 2f) return@forEach
        drawRoundRect(BaseColors.Cyan.copy(alpha = .35f), Offset(l, g.textTop + 4.dp.toPx()), Size(max(2f, r - l - 2f), g.slotH - 8.dp.toPx()), CornerRadius(6.dp.toPx()))
        // текст рисуем только если блок достаточно широк и хоть частично виден
        if (r - l > 36.dp.toPx() && r > 0f && l < g.width) {
            clipRect(left = max(l, 0f), top = g.textTop, right = min(r, g.width), bottom = g.textTop + g.slotH) {
                safeText(measurer, c.text, Offset(max(l, 0f) + 6.dp.toPx(), g.textTop + 13.dp.toPx()), TextStyle(color = Color.White, fontSize = 12.sp))
            }
        }
    }

    // клипы основной дорожки
    val tileW = g.mainH
    val h = g.mainH.roundToInt()
    s.clips.filter { it.row == 0 }.forEach { c ->
        val l = g.x(c.startMs); val r = g.x(c.endMs)
        if (r < -20f || l > g.width + 20f) return@forEach
        val rect = Rect(l, g.mainTop, r, g.mainTop + g.mainH)
        val path = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(rect, CornerRadius(g.corner))) }
        clipPath(path) {
            drawRect(BaseColors.TileEmpty, rect.topLeft, rect.size)
            var tx = l + floorTo(max(0f, -l), tileW)
            while (tx < min(r, g.width) ) {
                val srcMs = c.srcInMs + ((tx - l) / g.pxPerMs).toLong()
                val bucket = if (c.type == MediaType.VIDEO) (srcMs / 1000) * 1000 else 0L
                val key = Thumbs.key(c.uri, bucket, h)
                val th = Thumbs.peek(key)
                val w = min(tileW, r - tx)
                if (th != null) {
                    val bw = th.image.width; val bh = th.image.height
                    val side = min(bw, bh)
                    val srcW = (side * (w / tileW)).toInt().coerceAtLeast(1)
                    drawImage(th.image, IntOffset((bw - side) / 2, (bh - side) / 2), IntSize(srcW, side),
                        IntOffset(tx.roundToInt(), g.mainTop.roundToInt()), IntSize(w.roundToInt().coerceAtLeast(1), h))
                } else synchronized(pending) { if (!pending.containsKey(key)) pending[key] = ThumbReq(key, c.uri, c.type, bucket, h) }
                tx += tileW
            }
        }
        // тонкий разделитель между стыкующимися клипами
        drawLine(Color.Black.copy(alpha = .6f), Offset(l, g.mainTop), Offset(l, g.mainTop + g.mainH), 1.dp.toPx())

        if (c.id == s.selectedId) {
            val hw = min(g.handleW, (r - l) / 4)
            val stroke = 2.5.dp.toPx()
            drawRoundRect(Color.White, rect.topLeft, rect.size, CornerRadius(g.corner), Stroke(stroke))
            drawRoundRect(Color.White, Offset(l, g.mainTop), Size(hw, g.mainH), CornerRadius(g.corner))
            drawRoundRect(Color.White, Offset(r - hw, g.mainTop), Size(hw, g.mainH), CornerRadius(g.corner))
            val grip = Color(0xFF333333)
            drawLine(grip, Offset(l + hw / 2, g.mainTop + 22.dp.toPx()), Offset(l + hw / 2, g.mainTop + g.mainH - 22.dp.toPx()), 2.dp.toPx())
            drawLine(grip, Offset(r - hw / 2, g.mainTop + 22.dp.toPx()), Offset(r - hw / 2, g.mainTop + g.mainH - 22.dp.toPx()), 2.dp.toPx())
            // длительность
            val label = "%.1fs".format(c.lengthMs / 1000f)
            val m = measurer.measure(label, TextStyle(color = Color.White, fontSize = 12.sp))
            val bx = max(l + hw + 6.dp.toPx(), 6.dp.toPx()).coerceAtMost(r - hw - m.size.width - 14.dp.toPx())
            if (bx > l) {
                drawRoundRect(Color.Black.copy(alpha = .55f), Offset(bx, g.mainTop + 5.dp.toPx()), Size(m.size.width + 10.dp.toPx(), m.size.height + 4.dp.toPx()), CornerRadius(5.dp.toPx()))
                drawText(m, topLeft = Offset(bx + 5.dp.toPx(), g.mainTop + 7.dp.toPx()))
            }
        }
    }

    // точки добавления перехода на стыках
    s.junctions().forEach { (l, _) ->
        val x = g.x(l.endMs)
        if (x < -20f || x > g.width + 20f) return@forEach
        val has = s.transitions.any { it.leftId == l.id }
        val cy = g.mainTop + g.mainH / 2
        val r = g.junctionR
        drawCircle(if (has) BaseColors.Cyan else Color.White, r, Offset(x, cy))
        val k = 5.dp.toPx(); val ink = Color(0xFF111318)
        val left = Path().apply { moveTo(x - k * 1.6f, cy - k); lineTo(x - k * .15f, cy); lineTo(x - k * 1.6f, cy + k); close() }
        val right = Path().apply { moveTo(x + k * 1.6f, cy - k); lineTo(x + k * .15f, cy); lineTo(x + k * 1.6f, cy + k); close() }
        drawPath(left, ink); drawPath(right, ink)
    }

    // кнопка «+» в конце дорожки
    val end = g.x(s.clips.filter { it.row == 0 }.maxOfOrNull { it.endMs } ?: 0) + g.handleSlop
    if (end < g.width) {
        val top = g.mainTop + (g.mainH - g.plusSize) / 2
        drawRoundRect(Color.White, Offset(end, top), Size(g.plusSize, g.plusSize), CornerRadius(10.dp.toPx()))
        val cx = end + g.plusSize / 2; val cy = top + g.plusSize / 2; val a = 9.dp.toPx()
        drawLine(Color.Black, Offset(cx - a, cy), Offset(cx + a, cy), 2.5.dp.toPx())
        drawLine(Color.Black, Offset(cx, cy - a), Offset(cx, cy + a), 2.5.dp.toPx())
    }

    // курсор
    drawLine(Color.White, Offset(g.centerX, 0f), Offset(g.centerX, g.total + 6.dp.toPx()), 2.dp.toPx())
}

private fun floorTo(v: Float, step: Float) = (v / step).toInt() * step

/**
 * Безопасный вывод текста. Стандартный drawText(measurer, …) считает maxWidth = ширина холста − topLeft.x
 * и падает с IllegalArgumentException, когда подпись начинается правее видимой области.
 * Здесь размер текста меряется без ограничений, а невидимые подписи пропускаются.
 */
private fun DrawScope.safeText(measurer: TextMeasurer, text: String, topLeft: Offset, style: TextStyle) {
    if (text.isEmpty() || !topLeft.x.isFinite() || !topLeft.y.isFinite()) return
    if (size.width <= 0f || size.height <= 0f || topLeft.x > size.width || topLeft.y > size.height) return
    val layout: TextLayoutResult = try {
        measurer.measure(text, style, softWrap = false, maxLines = 1, constraints = Constraints())
    } catch (e: Exception) { return }
    if (topLeft.x + layout.size.width < 0f) return
    drawText(layout, topLeft = topLeft)
}
