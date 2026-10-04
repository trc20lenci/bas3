package com.base.editor.pag

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Шаблоны анимированных титров (.pag). Берутся из двух мест:
 *  • папка assets/pag (файлы .pag) — шаблоны, поставляемые с приложением (если дизайнеры их положили);
 *  • filesDir/pag — шаблоны, которые пользователь импортировал сам.
 * Ссылка на шаблон хранится строкой: "asset:<имя>" или "file:<имя>".
 */
class PagTemplateStore(private val context: Context) {
    private val dir = File(context.filesDir, "pag").apply { mkdirs() }

    data class Template(val ref: String, val title: String)

    fun list(): List<Template> {
        val bundled = runCatching { context.assets.list("pag").orEmpty().filter { it.endsWith(".pag", true) } }.getOrDefault(emptyList())
        val imported = dir.listFiles { f -> f.extension.equals("pag", true) }.orEmpty().map { it.name }
        return bundled.map { Template("asset:$it", it.removeSuffix(".pag")) } + imported.map { Template("file:$it", it.removeSuffix(".pag")) }
    }

    /** Копирует выбранный пользователем файл в хранилище приложения. */
    suspend fun import(uri: Uri): Template? = withContext(Dispatchers.IO) {
        val raw = (Uri.decode(uri.lastPathSegment ?: "title.pag")).substringAfterLast('/').substringAfterLast(':')
        val name = (if (raw.endsWith(".pag", true)) raw else "$raw.pag").replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input -> File(dir, name).outputStream().use { input.copyTo(it) } } ?: return@runCatching null
            Template("file:$name", name.removeSuffix(".pag"))
        }.getOrNull()
    }

    fun absolutePath(ref: String): String? = if (ref.startsWith("file:")) File(dir, ref.removePrefix("file:")).takeIf { it.exists() }?.absolutePath else null
}
