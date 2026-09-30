package com.base.editor.ui.editor

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Redo
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.base.editor.R
import com.base.editor.data.Format
import com.base.editor.ui.theme.BaseColors
import com.base.editor.ui.theme.soon
import kotlin.math.roundToInt

@Composable
fun EditorScreen(onClose: () -> Unit, onAddMedia: () -> Unit, vm: EditorViewModel = viewModel()) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val clips by vm.clips.collectAsStateWithLifecycle()
    val selected by vm.selectedId.collectAsStateWithLifecycle()
    val playhead by vm.playheadMs.collectAsStateWithLifecycle()
    val total by vm.totalMs.collectAsStateWithLifecycle()
    val playing by vm.isPlaying.collectAsStateWithLifecycle()
    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    val canRedo by vm.canRedo.collectAsStateWithLifecycle()
    val zoom by vm.pxPerSec.collectAsStateWithLifecycle()
    val resolution by vm.resolution.collectAsStateWithLifecycle()
    val muted by vm.muted.collectAsStateWithLifecycle()
    val event by vm.events.collectAsStateWithLifecycle()

    SideEffect { vm.onRequestAddMedia = onAddMedia }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.player.pause(); vm.saveNow() }
    LaunchedEffect(event) { event?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); vm.events.value = null } }
    fun close() { vm.saveNow(); onClose() }
    BackHandler { if (selected != null) vm.select(null) else close() }

    Column(Modifier.fillMaxSize().background(BaseColors.DarkBg).systemBarsPadding()) {
        // верхняя панель
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Icons.Rounded.Close, "Выйти", ::close)
            Spacer(Modifier.width(8.dp))
            Image(painterResource(R.drawable.logo_base_white), "BASE", Modifier.height(20.dp))
            Spacer(Modifier.weight(1f))
            var menu by remember { mutableStateOf(false) }
            Box {
                Row(Modifier.clip(RoundedCornerShape(12.dp)).background(BaseColors.DarkPanel).clickable { menu = true }.padding(start = 14.dp, end = 8.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(resolution, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Color.White)
                }
                DropdownMenu(menu, { menu = false }) {
                    listOf("480p", "720p", "1080p", "2K/4K").forEach { r -> DropdownMenuItem(text = { Text(r) }, onClick = { vm.setResolution(r); menu = false }) }
                }
            }
            Spacer(Modifier.width(10.dp))
            Text("Экспорт", Modifier.clip(RoundedCornerShape(12.dp)).background(BaseColors.Cyan).clickable { soon(ctx) }.padding(horizontal = 18.dp, vertical = 10.dp),
                color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }

        // плеер
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            AndroidView(
                factory = { c -> PlayerView(c).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; player = vm.player; setShutterBackgroundColor(android.graphics.Color.BLACK) } },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // время / play / undo-redo
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
            Row(Modifier.align(Alignment.CenterStart)) {
                Text(Format.duration(playhead), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("  /  ${Format.duration(total)}", color = Color.White.copy(alpha = .5f), fontSize = 14.sp)
            }
            RoundIcon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Пауза" else "Воспроизвести", vm::togglePlay, size = 52.dp)
            Row(Modifier.align(Alignment.CenterEnd)) {
                RoundIcon(Icons.Rounded.Undo, "Отменить", vm::undo, enabled = canUndo)
                RoundIcon(Icons.Rounded.Redo, "Повторить", vm::redo, enabled = canRedo)
            }
        }

        // таймлайн
        Box(Modifier.fillMaxWidth().background(BaseColors.DarkBg)) {
            TimelineView(clips, selected, playhead, total, zoom, vm, Modifier.fillMaxWidth())
            // кнопка «звук клипа» слева от нулевой отметки — уезжает вместе со шкалой
            val scrollPx = playhead * zoom * density.density / 1000f
            Column(
                Modifier.offset { IntOffset(-scrollPx.roundToInt(), with(density) { 36.dp.roundToPx() }) }.padding(start = 12.dp).width(64.dp)
                    .clip(RoundedCornerShape(10.dp)).clickable(onClick = vm::toggleMute).padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, null, tint = Color.White, modifier = Modifier.size(26.dp))
                Text(if (muted) "Вкл. звук клипа" else "Выкл. звук клипа", color = Color.White.copy(alpha = .8f), fontSize = 10.sp, textAlign = TextAlign.Center, lineHeight = 12.sp)
            }
        }

        // нижняя панель инструментов
        Crossfade(selected != null, label = "toolbar", modifier = Modifier.fillMaxWidth().background(BaseColors.DarkPanel)) { hasSel ->
            if (!hasSel) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ToolButton(Icons.Rounded.ContentCut, "Изменить") { vm.selectAtPlayhead() }
                    ToolButton(Icons.Rounded.MusicNote, "Звук") { soon(ctx) }
                    ToolButton(Icons.Rounded.TextFields, "Текст") { soon(ctx) }
                    ToolButton(Icons.Rounded.Layers, "Наложение") { soon(ctx) }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    ToolButton(Icons.Rounded.ChevronLeft, "Назад") { vm.select(null) }
                    ToolButton(Icons.Rounded.VerticalSplit, "Разделить", onClick = vm::split)
                    ToolButton(Icons.Rounded.Animation, "Анимации") { soon(ctx) }
                    ToolButton(Icons.Rounded.DeleteOutline, "Удалить", onClick = vm::deleteSelected)
                }
            }
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, desc: String, onClick: () -> Unit, size: androidx.compose.ui.unit.Dp = 44.dp, enabled: Boolean = true) {
    Box(Modifier.size(size).clip(CircleShape).alpha(if (enabled) 1f else .35f).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = Color.White, modifier = Modifier.size(if (size > 48.dp) 34.dp else 26.dp))
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
        Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}
