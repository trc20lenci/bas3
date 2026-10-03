package com.base.editor.captions

/**
 * Стили BASE в духе коротких вертикальных видео: ЗАГЛАВНЫЕ жирные буквы, толстая чёрная обводка,
 * яркий цвет слова, которое звучит сейчас, и «выпрыгивание» слова на пружине.
 */
object CaptionPresets {
    private fun c(v: Long) = v.toInt()
    private val white = c(0xFFFFFFFF)
    private val black = c(0xFF000000)

    private val base = CaptionStyle(
        font = CaptionFont.MONTSERRAT, fontWeight = 900, uppercase = true, sizeFrac = 0.0625f,
        textColor = white, strokeColor = black, strokeEm = 0.083f, maxWidthFrac = 0.90f, positionY = 0.78f,
    )

    val all: List<CaptionStyle> = listOf(
        base.copy(id = "tiktok", name = "TikTok", activeColor = c(0xFF39E508), animation = WordAnimation.POP, activeScale = 1.18f),
        base.copy(id = "contrast", name = "Контраст", activeColor = c(0xFFFFE600), sizeFrac = 0.058f, strokeEm = 0.095f, animation = WordAnimation.HIGHLIGHT),
        base.copy(id = "karaoke", name = "Караоке", activeColor = c(0xFF00E5FF), animation = WordAnimation.KARAOKE),
        base.copy(id = "pulse", name = "Пульс", activeColor = c(0xFFFF2D95), animation = WordAnimation.POP, activeScale = 1.3f),
    )

    val default: CaptionStyle = all.first()
    fun byId(id: String): CaptionStyle? = all.firstOrNull { it.id == id }
}
