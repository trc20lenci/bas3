package com.base.editor.ui.theme

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

object BaseColors {
    val Cyan = Color(0xFF00CCDD)
    val Ink = Color(0xFF111318)
    val Muted = Color(0xFF8A8F98)
    val Line = Color(0xFFE6E8EC)
    val SkyTop = Color(0xFFA9DBFF)
    val DarkBg = Color(0xFF0F0F11)
    val DarkPanel = Color(0xFF1B1C20)
    val DarkSlot = Color(0xFF26272C)
    val TileEmpty = Color(0xFF2E2F35)
}

@Composable
fun BaseTheme(dark: Boolean, content: @Composable () -> Unit) {
    val scheme = if (dark) darkColorScheme(
        primary = BaseColors.Cyan, onPrimary = Color.Black,
        background = BaseColors.DarkBg, onBackground = Color.White,
        surface = BaseColors.DarkPanel, onSurface = Color.White,
    ) else lightColorScheme(
        primary = BaseColors.Cyan, onPrimary = Color.Black,
        background = Color.White, onBackground = BaseColors.Ink,
        surface = Color.White, onSurface = BaseColors.Ink,
    )
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val w = (view.context as Activity).window
        WindowCompat.getInsetsController(w, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

fun soon(ctx: Context) = Toast.makeText(ctx, "Скоро появится", Toast.LENGTH_SHORT).show()
