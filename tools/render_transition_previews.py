#!/usr/bin/env python3
"""Генерация живых превью переходов BASE.

Каждый шейдер из assets/shaders/transitions рисуется программно (headless GL) на двух демонстрационных кадрах,
результат — короткая зацикленная анимация WebP: assets/shaders/transitions/previews/<id>.webp.
Запуск:  python3 tools/render_transition_previews.py   (нужны moderngl, pillow, numpy)
"""
import json, math, os, re
import numpy as np
import moderngl
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app/src/main/assets/shaders")
TR = os.path.join(ASSETS, "transitions")
OUT = os.path.join(TR, "previews")
W, H = 192, 108
FRAMES_MOVE, FRAMES_HOLD = 22, 8        # 30 fps → ≈ 1 с движения + пауза

def font(size):
    for f in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"):
        if os.path.exists(f): return ImageFont.truetype(f, size)
    return ImageFont.load_default()

def scene(top, bottom, letter, shapes):
    img = Image.new("RGB", (W, H)); px = np.zeros((H, W, 3), np.float32)
    t = np.linspace(0, 1, H)[:, None, None]; px[:] = (np.array(top) * (1 - t) + np.array(bottom) * t)
    img = Image.fromarray(px.astype("uint8")); d = ImageDraw.Draw(img)
    for kind, box, col in shapes:
        (d.ellipse if kind == "o" else d.rectangle)(box, fill=col)
    f = font(64); bb = d.textbbox((0, 0), letter, font=f)
    d.text(((W - (bb[2] - bb[0])) / 2 - bb[0], (H - (bb[3] - bb[1])) / 2 - bb[1]), letter, font=f, fill=(255, 255, 255))
    return img

A = scene((255, 140, 66), (190, 40, 90), "A", [("o", (130, 6, 186, 60), (255, 220, 120)), ("r", (0, 84, 192, 108), (110, 20, 60))])
B = scene((30, 120, 230), (10, 30, 90), "B", [("o", (10, 10, 60, 60), (200, 235, 255)), ("r", (0, 84, 192, 108), (8, 20, 70))])

VERT = """#version 330
in vec2 aFramePosition; out vec2 vUv;
void main(){ gl_Position = vec4(aFramePosition,0.0,1.0); vUv = aFramePosition*0.5+0.5; }"""

def to_desktop(src):
    src = src.replace("#version 300 es", "#version 330")
    src = re.sub(r"precision\s+\w+\s+\w+;\n", "", src)
    return src

pre = open(os.path.join(ASSETS, "transition_prelude.glsl")).read()
post = open(os.path.join(ASSETS, "transition_postlude.glsl")).read()
manifest = json.load(open(os.path.join(TR, "manifest.json")))
os.makedirs(OUT, exist_ok=True)
ctx = moderngl.create_standalone_context(backend="egl")
fbo = ctx.simple_framebuffer((W, H))
vbo = ctx.buffer(np.array([-1, -1, 1, -1, -1, 1, 1, 1], "f4").tobytes())

def tex(img):
    t = ctx.texture((W, H), 3, img.tobytes()); t.repeat_x = t.repeat_y = False; return t
# в конвейере кадров (0,0) — левый нижний угол: GL-текстуры принимают строки снизу вверх, как и должно быть
ta, tb = tex(A.transpose(Image.FLIP_TOP_BOTTOM)), tex(B.transpose(Image.FLIP_TOP_BOTTOM))

def ease(p): return p * p * (3 - 2 * p)
ok = 0
for item in manifest:
    body = open(os.path.join(TR, item["id"] + ".glsl")).read()
    try:
        prog = ctx.program(vertex_shader=VERT, fragment_shader=to_desktop(pre + body + post))
    except Exception as e:
        print("skip", item["id"], str(e).splitlines()[-1][:100]); continue
    vao = ctx.vertex_array(prog, [(vbo, "2f", "aFramePosition")])
    fbo.use(); frames = []
    seq = [ease(i / (FRAMES_MOVE - 1)) for i in range(FRAMES_MOVE)] + [1.0] * FRAMES_HOLD
    for p in seq:
        ta.use(0); tb.use(1)
        if "from" in prog: prog["from"].value = 0
        if "to" in prog: prog["to"].value = 1
        if "progress" in prog: prog["progress"].value = p
        if "ratio" in prog: prog["ratio"].value = W / H
        fbo.clear(0, 0, 0, 1); vao.render(moderngl.TRIANGLE_STRIP)
        frames.append(Image.frombytes("RGB", (W, H), fbo.read(components=3)).transpose(Image.FLIP_TOP_BOTTOM))
    durations = [33] * FRAMES_MOVE + [40] * (FRAMES_HOLD - 1) + [450]
    frames[0].save(os.path.join(OUT, item["id"] + ".webp"), format="WEBP", save_all=True, append_images=frames[1:],
                   duration=durations, loop=0, quality=62, method=4)
    ok += 1
print("previews:", ok, "of", len(manifest))
