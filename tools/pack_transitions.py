#!/usr/bin/env python3
"""Упаковщик шейдеров переходов BASE.

Берёт исходную коллекцию .glsl (путь — аргумент), отбирает проверенный список,
превращает `uniform T name; // = V` в константы, проверяет компиляцию как GLSL ES 3.00
и кладёт результат в app/src/main/assets/shaders/transitions + manifest.json.
Заголовки авторов/лицензий внутри файлов сохраняются.
"""
import json, os, re, subprocess, sys, tempfile

SRC = sys.argv[1]
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app/src/main/assets/shaders")
OUT = os.path.join(ASSETS, "transitions")

# id -> подпись в интерфейсе
CATALOG = {
    "fade": "Наплыв", "fadecolor": "Через цвет", "fadegrayscale": "Через ч/б", "dissolve": "Растворение",
    "wipeLeft": "Шторка влево", "wipeRight": "Шторка вправо", "wipeUp": "Шторка вверх", "wipeDown": "Шторка вниз",
    "directionalwarp": "Искажение", "crosswarp": "Кросс-варп", "circleopen": "Круг", "circle": "Круг-вспышка",
    "swap": "Обмен", "cube": "Куб", "pixelize": "Пиксели", "radial": "Радиальный", "Radial": "Радиальный",
    "Dreamy": "Мечта", "DreamyZoom": "Мечтательный зум", "CrossZoom": "Кросс-зум", "SimpleZoom": "Зум",
    "ripple": "Рябь", "WaterDrop": "Капля", "Swirl": "Вихрь", "kaleidoscope": "Калейдоскоп",
    "windowslice": "Жалюзи", "windowblinds": "Планки", "doorway": "Дверь", "squeeze": "Сжатие",
    "hexagonalize": "Соты", "heart": "Сердце", "pinwheel": "Вертушка", "rotate_scale_fade": "Поворот",
    "burn": "Выгорание", "GlitchMemories": "Глитч", "StereoViewer": "Стерео", "Bounce": "Отскок",
    "LinearBlur": "Размытие", "ZoomInCircles": "Круги", "randomsquares": "Квадраты", "squareswire": "Сетка",
    "angular": "Угловой", "colorphase": "Цветовая фаза", "crosshatch": "Штриховка", "flyeye": "Фасетка",
    "directionalwipe": "Косая шторка", "undulatingBurnOut": "Волнистый жар", "x_axis_translation": "Сдвиг по X",
}

UNI = re.compile(r"^\s*uniform\s+(\w+)\s+(\w+)\s*;\s*//\s*=\s*(.+?)\s*$")

def convert(text):
    out = []
    for line in text.splitlines():
        m = UNI.match(line)
        if m:
            t, n, v = m.groups()
            if t == "float" and re.fullmatch(r"-?\d+", v):
                v += ".0"            # в GLSL ES нет неявного int -> float
            out.append(f"const {t} {n} = {v};")
        elif re.match(r"^\s*uniform\s", line):
            return None   # параметр без значения по умолчанию — не берём
        else:
            out.append(line)
    return "\n".join(out) + "\n"

def compiles(body):
    pre = open(os.path.join(ASSETS, "transition_prelude.glsl")).read()
    post = open(os.path.join(ASSETS, "transition_postlude.glsl")).read()
    with tempfile.NamedTemporaryFile("w", suffix=".frag", delete=False) as f:
        f.write(pre + body + post)
    r = subprocess.run(["glslangValidator", f.name], capture_output=True, text=True)
    os.unlink(f.name)
    return r.returncode == 0, (r.stdout + r.stderr)

os.makedirs(OUT, exist_ok=True)
for f in os.listdir(OUT):
    os.remove(os.path.join(OUT, f))
manifest, seen = [], set()
for sid, label in CATALOG.items():
    p = os.path.join(SRC, sid + ".glsl")
    if not os.path.exists(p) or label in seen:
        continue
    body = convert(open(p, encoding="utf-8").read())
    if body is None:
        print("skip (params):", sid); continue
    ok, log = compiles(body)
    if not ok:
        print("skip (GLES fail):", sid, log.strip().splitlines()[-3:]); continue
    open(os.path.join(OUT, sid + ".glsl"), "w", encoding="utf-8").write(body)
    manifest.append({"id": sid, "label": label})
    seen.add(label)
json.dump(manifest, open(os.path.join(OUT, "manifest.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print("packed", len(manifest))
