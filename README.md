# BASE — мобильный видеоредактор (Android)

Каркас приложения: навигация «Дом / Проекты / Я», импорт медиа (Видео + Фото, мультивыбор),
редактор с плеером и многодорожечным таймлайном (move / trim / split / delete / undo / redo / zoom).

## Архитектура
| Слой | Что | Где |
|---|---|---|
| Core (C++17) | модель таймлайна (rows→clips, как в react-timeline-editor), move с магнитом и «перепрыгиванием», trim, split, ripple-delete, undo/redo, (де)сериализация | `app/src/main/cpp` |
| JNI | тонкая обёртка `NativeTimeline` | `core/NativeTimeline.kt`, `cpp/jni_bridge.cpp` |
| Превью | Media3 ExoPlayer (внутри — MediaCodec, аппаратный декод); плейлист собирается из клипов основной дорожки с `ClippingConfiguration` | `ui/editor/EditorViewModel.kt` |
| UI | Jetpack Compose; таймлайн — один `Canvas` + свои жесты (playhead по центру, шкала едет под ним) | `ui/*` |
| Данные | проекты — JSON в `filesDir/projects`, галерея — MediaStore | `data/*` |

## Сборка
Открыть в Android Studio (Ladybug+), NDK и CMake ставятся из SDK Manager. `minSdk 29`.
Версии в `gradle/libs.versions.toml` консервативные — можно поднимать.

## Что пока заглушка
Экспорт, аудио/текстовые дорожки (слоты нарисованы), эффекты, анимации, обложка, «Избранное/События/Справка».
Следующий шаг по ядру: нативный рендер-пайплайн (MediaCodec + EGL/GLES) для экспорта и композитинга слоёв.
