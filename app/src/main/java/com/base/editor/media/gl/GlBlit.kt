package com.base.editor.media.gl

import android.opengl.GLES20
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi

/** Копирование входной текстуры на текущий FBO — базовый «проходной» режим эффектов. */
@UnstableApi
internal class GlBlit {
    private val program = GlProgram(VERTEX_SHADER, BLIT_FRAGMENT_SHADER).apply {
        setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
    }

    fun draw(texId: Int) {
        program.use()
        program.setSamplerTexIdUniform("uTex", texId, 0)
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() = program.delete()
}
