#version 300 es
precision highp float;
precision highp int;
uniform sampler2D from;
uniform sampler2D to;
uniform float progress;   // 0.0 .. 1.0
uniform float ratio;      // ширина / высота кадра
in vec2 vUv;              // (0,0) — левый нижний угол, как в конвейере кадров
out vec4 outColor;
vec4 getFromColor(vec2 uv) { return texture(from, uv); }
vec4 getToColor(vec2 uv)   { return texture(to, uv); }
