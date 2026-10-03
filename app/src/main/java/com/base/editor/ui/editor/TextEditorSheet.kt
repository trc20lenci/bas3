package com.base.editor.ui.editor

import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.base.editor.text.TextClip
import com.base.editor.ui.theme.BaseColors

private val TextPalette = listOf(
    0xFFFFFFFFL, 0xFF000000L, 0xFFFFEB3BL, 0xFFFF6D00L, 0xFFFF1744L, 0xFFFF2DF1L,
    0xFF7C4DFFL, 0xFF2979FFL, 0xFF00E5FFL, 0xFF00E676L,
)

/**
 * Редактор текстового слоя в нижнем окне. Это отдельное окно (Dialog): клавиатура и анимация появления
 * не пересчитывают размеры экрана редактора, а видео остаётся видимым над панелью (затемнения нет),
 * так что изменения цвета, размера и положения видны сразу.
 */
@Composable
fun TextEditorSheet(
    clip: TextClip, isNew: Boolean,
    onChange: ((TextClip) -> TextClip) -> Unit,
    onDone: () -> Unit, onDelete: () -> Unit, onCancel: () -> Unit,
) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) {
            window?.apply {
                setGravity(Gravity.BOTTOM)
                setDimAmount(0f)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { if (isNew) runCatching { focus.requestFocus() } }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(BaseColors.DarkPanel)
                .imePadding().navigationBarsPadding().heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            OutlinedTextField(
                value = clip.text, onValueChange = { v -> onChange { it.copy(text = v) } },
                modifier = Modifier.fillMaxWidth().focusRequester(focus), placeholder = { Text("Введите текст") }, minLines = 1, maxLines = 3,
            )

            Label("Цвет текста")
            ColorChips(clip.textColor) { c -> onChange { it.copy(textColor = c) } }

            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Фон под текстом", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Switch(clip.hasBackground, { on -> onChange { it.copy(backgroundColor = if (on) 0xCC000000 else 0L) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = BaseColors.Cyan, checkedThumbColor = Color.Black))
            }
            if (clip.hasBackground) ColorChips(clip.backgroundColor or 0xFF000000) { c -> onChange { it.copy(backgroundColor = 0xCC000000 or (c and 0xFFFFFF)) } }

            SliderRow("Размер", clip.fontSizeSp, 12f..96f, "${clip.fontSizeSp.toInt()}") { v -> onChange { it.copy(fontSizeSp = v) } }
            SliderRow("Длительность", clip.durationMs.toFloat(), TextClip.MIN_DURATION_MS.toFloat()..10_000f, "%.1f с".format(clip.durationMs / 1000f)) { v -> onChange { it.copy(durationMs = (v / 100).toLong() * 100) } }
            SliderRow("По горизонтали", clip.positionX, 0f..1f, "${(clip.positionX * 100).toInt()}%") { v -> onChange { it.copy(positionX = v) } }
            SliderRow("По вертикали", clip.positionY, 0f..1f, "${(clip.positionY * 100).toInt()}%") { v -> onChange { it.copy(positionY = v) } }

            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!isNew) Text("Удалить", Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onDelete).padding(horizontal = 14.dp, vertical = 10.dp), color = Color(0xFFFF8A80), fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                Text("Отмена", Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onCancel).padding(horizontal = 14.dp, vertical = 10.dp), color = Color.White.copy(alpha = .8f), fontSize = 15.sp)
                Text("Готово", Modifier.padding(start = 6.dp).clip(RoundedCornerShape(10.dp)).background(BaseColors.Cyan).clickable(onClick = onDone).padding(horizontal = 22.dp, vertical = 10.dp),
                    color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable private fun Label(t: String) = Text(t, color = Color.White.copy(alpha = .6f), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))

@Composable
private fun ColorChips(selected: Long, onPick: (Long) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(TextPalette) { c ->
            val sel = (c and 0xFFFFFF) == (selected and 0xFFFFFF)
            Box(Modifier.size(30.dp).clip(CircleShape).background(Color(c)).border(BorderStroke(if (sel) 3.dp else 1.dp, if (sel) BaseColors.Cyan else Color.White.copy(alpha = .25f)), CircleShape).clickable { onPick(c) })
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White.copy(alpha = .75f), fontSize = 13.sp, modifier = Modifier.width(112.dp))
        Slider(value.coerceIn(range.start, range.endInclusive), onChange, valueRange = range, modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = BaseColors.Cyan, activeTrackColor = BaseColors.Cyan))
        Text(shown, color = Color.White, fontSize = 12.sp, modifier = Modifier.width(48.dp))
    }
}
