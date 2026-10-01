# BASE — мобильный видеоредактор (Android)

Навигация «Дом / Проекты / Я», импорт медиа (Видео + Фото, мультивыбор), редактор с многодорожечным
таймлайном, предпросмотром и шейдерными переходами на стыках клипов, экспорт в MP4.
Только пермиссивные лицензии (Apache-2.0 / MIT).

## Архитектура
```
ui/            Compose-экраны, MVVM (EditorViewModel ↔ TimelineView/EditorScreen)
domain/        TimelineModel — чистый Kotlin: клипы, переходы, move/trim/split/ripple, undo/redo, сохранение
media/         TimelineController  — таймлайн ↔ плеер превью (Media3 CompositionPlayer)
               CompositionFactory  — модель → Composition (общий для превью и экспорта)
               VideoExportManager  — экспорт через Transformer (Flow<ExportState>)
media/gl/      шейдерный мост переходов: TransitionEffect, TailCaptureEffect, TransitionBridge
core/          модели данных, каталог переходов (TransitionCatalog)
data/          проекты (JSON), галерея (MediaStore), миниатюры
```
**Потоки.** Главный поток — только UI и неблокирующие команды плееру. Сборка композиции — `Dispatchers.Default`
(запросы склеиваются: в плеер уходит только последняя версия), файлы/MediaStore — `Dispatchers.IO`,
декодирование/GL/кодирование — внутренние потоки Media3; экспорт живёт на отдельном `HandlerThread`.

**Переходы.** Конвейер Media3 отдаёт эффекту кадры одного клипа, поэтому: `TailCaptureEffect` на уходящем клипе
сохраняет его последний кадр в `TransitionBridge`, а `TransitionEffect` на входящем первые `D` мс рисует
`shader(from = последний кадр уходящего, to = живой кадр входящего, progress 0..1, ratio)`.
Шейдеры — GLSL ES 3.00 из `assets/shaders/transitions` (каталог — `manifest.json`; добавить переход = положить
`.glsl` и строку в манифест, либо прогнать `tools/pack_transitions.py <папка с шейдерами>`, он же проверяет компиляцию).
Если шейдер не собрался — эффект работает как проходной, пользователю приходит сообщение.

**Ошибки MediaCodec.** Превью: сбой декодера/GPU → один раз пересборка без эффектов («безопасный режим»).
Экспорт: сбой кодека/GL → автоматическая повторная попытка (H.264, 0.75× разрешения, без шейдеров);
кодер подбирается с включённым fallback.

## Сборка
Android Studio (Ladybug+), `minSdk 29`, Media3 1.11.1 (`gradle/libs.versions.toml`). NDK не нужен.
Тесты модели: `./gradlew testDebugUnitTest`.

## Что пока заглушка
Аудио/текстовые дорожки (слоты нарисованы), эффекты, анимации, «Избранное/События/Справка».
