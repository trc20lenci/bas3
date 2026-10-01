package com.base.editor.ui.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.base.editor.core.Clip
import com.base.editor.core.PickedMedia
import com.base.editor.core.Transition
import com.base.editor.core.TransitionCatalog
import com.base.editor.data.MediaProbe
import com.base.editor.data.ProjectRepository
import com.base.editor.media.AppDispatchers
import com.base.editor.media.CompositionFactory
import com.base.editor.media.ExportQuality
import com.base.editor.media.ExportRequest
import com.base.editor.media.ExportState
import com.base.editor.media.TimelineController
import com.base.editor.media.VideoExportManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Действия, которые жесты таймлайна вызывают у ViewModel. */
interface TimelineActions {
    fun scrubStart()
    fun scrubTo(ms: Long)
    fun select(id: Long?)
    fun editBegin()
    fun moveClip(id: Long, startMs: Long)
    fun trimStart(id: Long, ms: Long)
    fun trimEnd(id: Long, ms: Long)
    fun editEnd()
    fun setZoom(pxPerSecDp: Float)
    fun addMedia()
    fun addAudio()
    fun addText()
    fun openTransitions(leftId: Long)
}

@OptIn(FlowPreview::class)
@UnstableApi
class EditorViewModel(app: Application, private val handle: SavedStateHandle) : AndroidViewModel(app), TimelineActions {
    private val projectId: String = checkNotNull(handle["projectId"])
    private val repo = ProjectRepository.get(app)
    private val dispatchers = AppDispatchers()
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val catalog = TransitionCatalog(app)
    val controller = TimelineController(app, viewModelScope, catalog, dispatchers)
    private val exporter = VideoExportManager(app, CompositionFactory(app, catalog), dispatchers)

    // состояние таймлайна — прямо из контроллера
    val clips: StateFlow<List<Clip>> = controller.state.map { it.clips }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val transitions: StateFlow<List<Transition>> = controller.state.map { it.transitions }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val totalMs: StateFlow<Long> = controller.state.map { it.totalMs }.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val playheadMs = controller.playheadMs
    val isPlaying = controller.isPlaying
    val canUndo = controller.canUndo
    val canRedo = controller.canRedo

    // состояние интерфейса
    val selectedId = MutableStateFlow<Long?>(null)
    val pxPerSec = MutableStateFlow(60f)                  // масштаб: dp на секунду
    val resolution = MutableStateFlow("720p")
    val muted = MutableStateFlow(false)
    val events = MutableStateFlow<String?>(null)          // одноразовые сообщения для Toast
    val transitionFor = MutableStateFlow<Long?>(null)     // стык, для которого открыта панель переходов
    val transitionMaxMs = MutableStateFlow(0L)
    val exportState = MutableStateFlow<ExportState?>(null)

    var onRequestAddMedia: (() -> Unit)? = null
    private var aspect = 9f / 16f
    private var exportJob: Job? = null

    init {
        viewModelScope.launch {
            val saved = repo.loadTimeline(projectId)
            controller.load(saved)
            withContext(dispatchers.default) { detectAspect() }      // чтение метаданных — не на главном потоке
            applyCanvas()
        }
        viewModelScope.launch { controller.events.collect { events.value = it } }
        viewModelScope.launch { controller.committed.debounce(600).collect { persist() } }
        // результат экрана «добавить медиа» приходит через SavedStateHandle
        viewModelScope.launch {
            handle.getStateFlow<ArrayList<String>?>("added", null).filterNotNull().collect { list ->
                handle["added"] = null
                controller.addMedia(list.map(PickedMedia::decode))
            }
        }
        // выбранный клип / панель переходов не должны ссылаться на исчезнувшие клипы
        viewModelScope.launch {
            controller.state.collect { s ->
                if (selectedId.value != null && s.clips.none { it.id == selectedId.value }) selectedId.value = null
                transitionFor.value?.let { id ->
                    val max = controller.maxTransitionMs(id)
                    if (s.clips.none { it.id == id } || max <= 0) transitionFor.value = null else transitionMaxMs.value = max
                }
            }
        }
    }

    // ───────── проект ─────────
    private suspend fun detectAspect() {
        val first = controller.state.value.clips.filter { it.row == 0 }.minByOrNull { it.startMs } ?: return
        MediaProbe.displaySize(getApplication(), first.uri, first.type)?.let { (w, h) -> aspect = w.toFloat() / h }
    }

    private fun shortSide() = when (resolution.value) { "480p" -> 480; "1080p" -> 1080; else -> 720 }
    private fun applyCanvas() = controller.setCanvas(CompositionFactory.canvasFor(aspect, shortSide()))
    fun setResolution(r: String) { resolution.value = r; applyCanvas() }

    private fun persist() {
        val data = controller.serialize(); val clips = controller.state.value.clips
        persistScope.launch { repo.save(projectId, data, clips) }
    }
    fun saveNow() = persist()

    // ───────── воспроизведение ─────────
    fun togglePlay() = controller.toggle()
    fun toggleMute() { muted.value = !muted.value; controller.setMuted(muted.value) }
    fun undo() = controller.undo()
    fun redo() = controller.redo()

    // ───────── TimelineActions ─────────
    override fun scrubStart() = controller.pause()
    override fun scrubTo(ms: Long) = controller.seekTo(ms)
    override fun select(id: Long?) { selectedId.value = id }
    override fun editBegin() = controller.beginEdit()
    override fun moveClip(id: Long, startMs: Long) = controller.move(id, startMs, (8f / pxPerSec.value * 1000).toLong())
    override fun trimStart(id: Long, ms: Long) = controller.trimStart(id, ms)
    override fun trimEnd(id: Long, ms: Long) = controller.trimEnd(id, ms)
    override fun editEnd() = controller.commitEdit()
    override fun setZoom(pxPerSecDp: Float) { pxPerSec.value = pxPerSecDp.coerceIn(12f, 400f) }
    override fun addMedia() { controller.pause(); onRequestAddMedia?.invoke() }
    override fun addAudio() { events.value = "Аудиодорожка появится на следующем этапе" }
    override fun addText() { events.value = "Текстовые слои появятся на следующем этапе" }

    fun split() {
        val id = selectedId.value ?: return
        if (!controller.split(id)) events.value = "Поставьте курсор внутрь клипа"
    }

    fun deleteSelected() { selectedId.value?.let { controller.remove(it); selectedId.value = null } }

    fun selectAtPlayhead() {
        val t = playheadMs.value
        selectedId.value = clips.value.firstOrNull { it.row == 0 && t >= it.startMs && t < it.endMs }?.id
    }

    // ───────── переходы ─────────
    override fun openTransitions(leftId: Long) {
        controller.pause()
        selectedId.value = null
        transitionMaxMs.value = controller.maxTransitionMs(leftId)
        transitionFor.value = leftId
        clips.value.firstOrNull { it.id == leftId }?.let { controller.seekTo(it.endMs) }   // курсор на стык
    }

    fun closeTransitions() { transitionFor.value = null }
    fun currentTransition(leftId: Long): Transition? = transitions.value.firstOrNull { it.leftId == leftId }

    /** shaderId == null — убрать переход. Сразу проигрывает окно перехода в плеере. */
    fun applyTransition(leftId: Long, shaderId: String?, durationMs: Long) {
        if (controller.setTransition(leftId, shaderId, durationMs) && shaderId != null) controller.previewTransition(leftId)
    }

    // ───────── экспорт ─────────
    fun startExport(quality: ExportQuality = ExportQuality.P1080) {
        if (exportJob?.isActive == true) return
        controller.pause()
        val request = ExportRequest(controller.state.value, aspect, quality, removeAudio = muted.value)
        exportJob = viewModelScope.launch {
            exporter.export(request).collect { s ->
                exportState.value = s
                if (s is ExportState.Done) {
                    runCatching { exporter.saveToGallery(s.file) }
                        .onFailure { exportState.value = ExportState.Failed("Не удалось сохранить в галерею", it) }
                        .onSuccess { events.value = "Видео сохранено в Movies/BASE" }
                }
            }
        }
    }

    fun cancelExport() { exportJob?.cancel(); exportState.value = null }
    fun dismissExport() { exportState.value = null }

    override fun onCleared() {
        persist()
        controller.release()
    }
}
