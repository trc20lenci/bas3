package com.base.editor.media.gl

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.util.Size
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Ставится на клип, ПОСЛЕ которого есть переход. Пропускает кадры без изменений
 * и копирует каждый кадр в [bridge] — к концу клипа там лежит его последний кадр.
 */
@UnstableApi
class TailCaptureEffect(private val bridge: TransitionBridge) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        try { TailCaptureProgram(bridge, useHdr) } catch (e: GlUtil.GlException) { throw VideoFrameProcessingException(e) }
}

@UnstableApi
private class TailCaptureProgram(private val bridge: TransitionBridge, useHdr: Boolean) :
    BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {
    private val blit = GlBlit()
    private var w = 0
    private var h = 0

    override fun configure(inputWidth: Int, inputHeight: Int): Size { w = inputWidth; h = inputHeight; return Size(inputWidth, inputHeight) }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            blit.draw(inputTexId)                         // кадр уходит дальше как есть
            bridge.ensure(w, h)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bridge.textureId)
            GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h)   // из текущего FBO (результат blit)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            GlUtil.checkGlError()
            bridge.hasFrame = true
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun release() {
        super.release()
        runCatching { blit.release() }   // текстуру моста НЕ удаляем: она нужна входящему клипу
    }
}
