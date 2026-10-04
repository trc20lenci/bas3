package com.base.editor.ui.editor

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.base.editor.pag.PagTemplateStore
import com.base.editor.pag.PagTitles
import com.base.editor.text.TextClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.libpag.PAGFile
import org.libpag.PAGView

/**
 * Анимированный титр (.pag) поверх плеера. Время анимации привязано к курсору таймлайна:
 * progress = (playhead − start) / duration, поэтому скраб и воспроизведение совпадают, а видео не отстаёт.
 */
@Composable
fun PagTitleOverlay(clip: TextClip, playheadMs: Long, videoAspect: Float, modifier: Modifier = Modifier) {
    val ref = clip.pagTemplate ?: return
    val ctx: Context = LocalContext.current
    val store = remember { PagTemplateStore(ctx) }
    val file by produceState<PAGFile?>(null, ref, clip.text) {
        value = withContext(Dispatchers.IO) { PagTitles.load(ctx, store, ref, clip.text.ifBlank { " " }) }
    }
    val f = file ?: return
    val progress = ((playheadMs - clip.startMs).toDouble() / clip.durationMs.coerceAtLeast(1)).coerceIn(0.0, 1.0)

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { c -> PAGView(c).apply { setRepeatCount(1) } },
            update = { v ->
                if (v.composition !== f) v.composition = f
                v.setProgress(progress)               // кадр шаблона по положению курсора
                v.flush()
            },
            onRelease = { v -> runCatching { v.stop() } },
            modifier = Modifier.aspectRatio(videoAspect.coerceIn(0.2f, 5f)),
        )
    }
}
