package com.base.editor.core

import android.content.Context
import android.util.Log
import org.json.JSONArray

data class TransitionInfo(val id: String, val label: String)

/** Готовый к компиляции шейдер перехода. */
data class TransitionSpec(val id: String, val label: String, val fragmentSource: String)

/**
 * Каталог переходов BASE. Шейдеры лежат в assets/shaders/transitions (+ manifest.json);
 * чтобы добавить переход — положите .glsl и запись в манифест (tools/pack_transitions.py делает это сам).
 */
class TransitionCatalog(private val ctx: Context) {
    private val prelude: String = read("shaders/transition_prelude.glsl")
    private val postlude: String = read("shaders/transition_postlude.glsl")
    val items: List<TransitionInfo> = JSONArray(read("shaders/transitions/manifest.json")).let { a ->
        (0 until a.length()).map { a.getJSONObject(it).let { o -> TransitionInfo(o.getString("id"), o.getString("label")) } }
    }
    private val cache = HashMap<String, TransitionSpec>()

    /** null — перехода нет в каталоге или файл шейдера не читается. */
    @Synchronized
    fun spec(id: String): TransitionSpec? = cache[id] ?: runCatching {
        val info = items.first { it.id == id }
        TransitionSpec(id, info.label, prelude + read("shaders/transitions/$id.glsl") + postlude)
    }.onFailure { Log.w("BaseCatalog", "переход $id недоступен", it) }.getOrNull()?.also { cache[id] = it }

    fun label(id: String) = items.firstOrNull { it.id == id }?.label ?: id

    private fun read(path: String) = ctx.assets.open(path).bufferedReader().use { it.readText() }
}
