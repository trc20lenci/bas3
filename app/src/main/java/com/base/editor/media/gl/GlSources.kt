package com.base.editor.media.gl

/** Общая вершинная программа: полноэкранный прямоугольник, uv с началом в левом нижнем углу. */
internal const val VERTEX_SHADER = """#version 300 es
layout(location = 0) in vec4 aFramePosition;
out vec2 vUv;
void main() {
  gl_Position = aFramePosition;
  vUv = aFramePosition.xy * 0.5 + 0.5;
}
"""

internal const val BLIT_FRAGMENT_SHADER = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
in vec2 vUv;
out vec4 outColor;
void main() { outColor = texture(uTex, vUv); }
"""
