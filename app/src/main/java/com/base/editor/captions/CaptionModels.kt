package com.base.editor.captions

/** Слово с таймингом на шкале проекта (миллисекунды). */
data class WordTimestamp(val word: String, val startMs: Long, val endMs: Long)

/** Одна «карточка» субтитров: текст целиком + пословные тайминги для подсветки. */
data class CaptionItem(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<WordTimestamp>,
) {
    val durationMs get() = endMs - startMs
}

/** Шрифты из набора BASE (все с кириллицей). */
enum class CaptionFont(val label: String) {
    MONTSERRAT("Montserrat"), OSWALD("Oswald"), RUBIK("Rubik"), RUSSO("Russo One"), PACIFICO("Pacifico")
}

/** Что происходит со словом, которое звучит прямо сейчас. */
enum class WordAnimation(val label: String) {
    NONE("Без анимации"),
    HIGHLIGHT("Подсветка"),
    KARAOKE("Караоке"),
    POP("Увеличение"),
    BOUNCE("Прыжок"),
}

/**
 * Настройки внешнего вида. Размеры заданы в долях высоты кадра / размера шрифта,
 * поэтому один и тот же стиль одинаково выглядит в превью и в экспорте любого разрешения.
 * Цвета — ARGB (Int).
 */
data class CaptionStyle(
    val id: String = "business",
    val name: String = "Бизнес",
    val font: CaptionFont = CaptionFont.MONTSERRAT,
    val fontWeight: Int = 900,
    val italic: Boolean = false,
    val uppercase: Boolean = true,
    /** Размер шрифта как доля высоты кадра. */
    val sizeFrac: Float = 0.050f,
    val letterSpacingEm: Float = 0f,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val activeColor: Int = 0xFF00E5FF.toInt(),
    val strokeColor: Int = 0xFF000000.toInt(),
    /** Толщина обводки в долях размера шрифта (0 — без обводки). */
    val strokeEm: Float = 0.10f,
    /** Фоновая плашка; прозрачный (alpha = 0) — без плашки. */
    val backgroundColor: Int = 0x00000000,
    val backgroundPaddingEm: Float = 0.28f,
    val backgroundCornerEm: Float = 0.30f,
    /** Тень/свечение: свечение = тень без смещения и яркий цвет. */
    val shadowColor: Int = 0x80000000.toInt(),
    val shadowBlurEm: Float = 0.10f,
    val shadowDyEm: Float = 0.06f,
    val animation: WordAnimation = WordAnimation.HIGHLIGHT,
    /** Масштаб активного слова (для POP/BOUNCE). */
    val activeScale: Float = 1.15f,
    /** Максимальная ширина блока, доля ширины кадра. */
    val maxWidthFrac: Float = 0.88f,
    /** Вертикальный центр блока, доля высоты кадра (0 — верх, 1 — низ). */
    val positionY: Float = 0.72f,
) {
    val hasBackground get() = (backgroundColor ushr 24) > 0
}
