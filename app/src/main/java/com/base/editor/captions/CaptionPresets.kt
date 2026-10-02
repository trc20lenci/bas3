package com.base.editor.captions

/**
 * Готовые стили BASE. Идея набора — популярные форматы коротких видео:
 * жирная подсветка слова, караоке-заливка, «поп» активного слова, плашка, неон.
 */
object CaptionPresets {
    private fun c(v: Long) = v.toInt()

    val all: List<CaptionStyle> = listOf(
        CaptionStyle(
            id = "business", name = "Бизнес", font = CaptionFont.MONTSERRAT, fontWeight = 900,
            textColor = c(0xFFFFFFFF), activeColor = c(0xFF00E5FF), strokeEm = 0.11f,
            shadowColor = c(0x99000000), animation = WordAnimation.HIGHLIGHT,
        ),
        CaptionStyle(
            id = "impact", name = "Жёлтый", font = CaptionFont.OSWALD, fontWeight = 700, sizeFrac = 0.062f,
            textColor = c(0xFFFFEB3B), activeColor = c(0xFFFF6D00), strokeEm = 0.16f,
            shadowColor = c(0x00000000), animation = WordAnimation.HIGHLIGHT,
        ),
        CaptionStyle(
            id = "karaoke", name = "Караоке", font = CaptionFont.MONTSERRAT, fontWeight = 900,
            textColor = c(0xFFFFFFFF), activeColor = c(0xFF2979FF), strokeEm = 0.09f,
            animation = WordAnimation.KARAOKE,
        ),
        CaptionStyle(
            id = "minimal", name = "Минимал", font = CaptionFont.RUBIK, fontWeight = 700, italic = true,
            uppercase = false, sizeFrac = 0.044f, textColor = c(0xFFFFFFFF), activeColor = c(0xFFF5F5F5),
            strokeEm = 0.07f, letterSpacingEm = 0.02f, animation = WordAnimation.POP, activeScale = 1.12f,
        ),
        CaptionStyle(
            id = "bounce", name = "Отскок", font = CaptionFont.RUSSO, fontWeight = 400, sizeFrac = 0.052f,
            textColor = c(0xFF00FF88), activeColor = c(0xFFFF00FF), strokeEm = 0.12f,
            shadowColor = c(0x00000000), animation = WordAnimation.BOUNCE, activeScale = 1.2f,
        ),
        CaptionStyle(
            id = "box", name = "Плашка", font = CaptionFont.RUBIK, fontWeight = 800, uppercase = false,
            sizeFrac = 0.044f, textColor = c(0xFFFFFFFF), activeColor = c(0xFFFFD600), strokeEm = 0f,
            backgroundColor = c(0xCC000000), shadowColor = c(0x00000000),
            animation = WordAnimation.POP, activeScale = 1.08f, positionY = 0.78f,
        ),
        CaptionStyle(
            id = "neon", name = "Неон", font = CaptionFont.RUBIK, fontWeight = 800, sizeFrac = 0.048f,
            textColor = c(0xFFFFFFFF), activeColor = c(0xFF18FFFF), strokeEm = 0f,
            shadowColor = c(0xFFFF2DF1), shadowBlurEm = 0.45f, shadowDyEm = 0f, animation = WordAnimation.HIGHLIGHT,
        ),
        CaptionStyle(
            id = "script", name = "Рукопись", font = CaptionFont.PACIFICO, fontWeight = 400, uppercase = false,
            sizeFrac = 0.058f, textColor = c(0xFFFFFFFF), activeColor = c(0xFFFFC107), strokeEm = 0.08f,
            animation = WordAnimation.POP, activeScale = 1.12f,
        ),
    )

    val default: CaptionStyle = all.first()
    fun byId(id: String): CaptionStyle? = all.firstOrNull { it.id == id }
}
