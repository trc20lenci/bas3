package com.base.editor.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Format {
    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }
    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1fGB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1L shl 20 -> "${bytes shr 20}MB"
        else -> "${bytes shr 10}KB"
    }
    fun date(ms: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(ms))
    fun projects(n: Int): String {
        val m10 = n % 10; val m100 = n % 100
        val w = when { m100 in 11..14 -> "проектов"; m10 == 1 -> "проект"; m10 in 2..4 -> "проекта"; else -> "проектов" }
        return "$n $w"
    }
}
