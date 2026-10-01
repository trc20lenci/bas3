package com.base.editor.media.gl

import android.util.Log
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi

/**
 * Мост между клипами на одном стыке. Конвейер Media3 отдаёт эффекту кадры ОДНОГО клипа,
 * поэтому последний кадр уходящего клипа сохраняется сюда (TailCaptureEffect),
 * а входящий клип (TransitionEffect) использует его как текстуру `from`.
 *
 * Владеет одной GL-текстурой. Все обращения — из GL-потока конвейера (контекст общий).
 */
@UnstableApi
class TransitionBridge {
    var textureId = 0
        private set
    var width = 0
        private set
    var height = 0
        private set
    @Volatile var hasFrame = false

    fun ensure(w: Int, h: Int) {
        if (textureId != 0 && w == width && h == height) return
        release()
        textureId = GlUtil.createTexture(w, h, /* useHighPrecisionColorComponents= */ false)
        width = w; height = h; hasFrame = false
    }

    fun release() {
        if (textureId != 0) {
            runCatching { GlUtil.deleteTexture(textureId) }.onFailure { Log.w(TAG, "deleteTexture", it) }
            textureId = 0
        }
        hasFrame = false
    }

    private companion object { const val TAG = "BaseBridge" }
}
