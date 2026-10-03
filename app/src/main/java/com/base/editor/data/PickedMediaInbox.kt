package com.base.editor.data

import com.base.editor.core.PickedMedia
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap

/**
 * Почтовый ящик результатов галереи для открытых проектов: экран выбора кладёт сюда выбранные файлы,
 * редактор забирает и добавляет в проект. Не зависит от навигационного стека, а очередь не теряет
 * результат, даже если редактор в этот момент не на экране.
 */
object PickedMediaInbox {
    private val boxes = ConcurrentHashMap<String, Channel<List<PickedMedia>>>()
    fun channel(projectId: String): Channel<List<PickedMedia>> = boxes.getOrPut(projectId) { Channel(Channel.UNLIMITED) }
    fun post(projectId: String, items: List<PickedMedia>) { channel(projectId).trySend(items) }
}
