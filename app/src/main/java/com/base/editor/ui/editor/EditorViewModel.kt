package com.base.editor.ui.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.base.editor.core.Clip
import com.base.editor.core.IMAGE_DEFAULT_MS
import com.base.editor.core.MediaType
import com.base.editor.core.NativeTimeline
import com.base.editor.core.PickedMedia
import com.base.editor.data.ProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

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
}

class EditorViewModel(app: Application, private val handle: SavedStateHandle) : AndroidViewModel(app), TimelineActions {
    private val projectId: String = checkNotNull(handle["projectId"])
    private val repo = ProjectRepository.get(app)
    private val engine = NativeTimeline()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val player: ExoPlayer = ExoPlayer.Builder(app).build()

    private val _clips = MutableStateFlow<List<Clip>>(emptyList())
    val clips: StateFlow<List<Clip>> = _clips
    val selectedId = MutableStateFlow<Long?>(null)
    val playheadMs = MutableStateFlow(0L)
    val totalMs = MutableStateFlow(0L)
    val isPlaying = MutableStateFlow(false)
    val canUndo = MutableStateFlow(false)
    val canRedo = MutableStateFlow(false)
    val pxPerSec = MutableStateFlow(60f)          // масштаб: dp на секунду
    val resolution = MutableStateFlow("1080p")
    val muted = MutableStateFlow(false)
    val loaded = MutableStateFlow(false)
    val events = MutableStateFlow<String?>(null)  // одноразовые сообщения для Toast

    /** Клипы основной дорожки в порядке воспроизведения (соответствуют элементам плейлиста). */
    private var playlist: List<Clip> = emptyList()
    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            repo.loadTimeline(projectId)?.let { engine.load(it) }
            refresh(); rebuildPlaylist(); loaded.value = true
        }
        // Результат экрана «добавить медиа» приходит через SavedStateHandle
        viewModelScope.launch {
            handle.getStateFlow<ArrayList<String>?>("added", null).filterNotNull().collect { list ->
                handle["added"] = null
                appendMedia(list.map(PickedMedia::decode))
            }
        }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying.value = playing }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) { player.pause(); playheadMs.value = totalMs.value }
            }
        })
        viewModelScope.launch {   // синхронизация плейхеда с плеером во время воспроизведения
            while (true) { delay(33); if (player.isPlaying) syncPlayhead() }
        }
    }

    // ---------- состояние ----------
    private fun refresh() {
        _clips.value = engine.clips()
        totalMs.value = engine.totalMs
        canUndo.value = engine.canUndo
        canRedo.value = engine.canRedo
        if (selectedId.value != null && _clips.value.none { it.id == selectedId.value }) selectedId.value = null
        playheadMs.value = playheadMs.value.coerceIn(0, totalMs.value)
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch { delay(600); persist() }
    }

    private fun persist() {
        val data = engine.serialize(); val clips = engine.clips()
        appScope.launch { repo.save(projectId, data, clips) }
    }

    fun saveNow() { saveJob?.cancel(); persist() }

    // ---------- плеер ----------
    private fun mediaItem(c: Clip): MediaItem {
        val b = MediaItem.Builder().setUri(c.uri)
        if (c.type == MediaType.IMAGE) {
            getApplication<Application>().contentResolver.getType(android.net.Uri.parse(c.uri))?.let { b.setMimeType(it) }
            b.setImageDurationMs(c.lengthMs)
        } else {
            b.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(c.srcInMs).setEndPositionMs(c.srcInMs + c.lengthMs).build()
            )
        }
        return b.build()
    }

    private fun locate(t: Long): Pair<Int, Long> {
        if (playlist.isEmpty()) return 0 to 0L
        val i = playlist.indexOfFirst { t >= it.startMs && t < it.endMs }
        if (i >= 0) return i to (t - playlist[i].startMs)
        val next = playlist.indexOfFirst { it.startMs > t }          // попали в пустоту
        return if (next >= 0) next to 0L else playlist.lastIndex to (playlist.last().lengthMs - 1).coerceAtLeast(0)
    }

    /** Полная пересборка плейлиста — после структурных правок (не на каждом кадре жеста). */
    private fun rebuildPlaylist() {
        playlist = _clips.value.filter { it.row == 0 }.sortedBy { it.startMs }
        if (playlist.isEmpty()) { player.clearMediaItems(); return }
        val (idx, off) = locate(playheadMs.value)
        player.setMediaItems(playlist.map(::mediaItem), idx, off)
        player.prepare()
    }

    private fun syncPlayhead() {
        val i = player.currentMediaItemIndex
        if (i in playlist.indices) playheadMs.value = playlist[i].startMs + player.currentPosition
    }

    fun togglePlay() {
        if (player.isPlaying) { player.pause(); return }
        if (playlist.isEmpty()) return
        if (playheadMs.value >= totalMs.value - 50) { playheadMs.value = 0; seekPlayer(0) }
        player.play()
    }

    private fun seekPlayer(ms: Long) {
        if (playlist.isEmpty()) return
        val (i, off) = locate(ms)
        player.seekTo(i, off)
    }

    fun toggleMute() { muted.value = !muted.value; player.volume = if (muted.value) 0f else 1f }
    fun setResolution(r: String) { resolution.value = r }   // пригодится экспорту; превью не меняет

    // ---------- TimelineActions ----------
    override fun scrubStart() { player.pause() }
    override fun scrubTo(ms: Long) {
        val v = ms.coerceIn(0, totalMs.value)
        if (v == playheadMs.value) return
        playheadMs.value = v; seekPlayer(v)
    }
    override fun select(id: Long?) { selectedId.value = id }
    override fun editBegin() { player.pause(); engine.checkpoint() }

    override fun moveClip(id: Long, startMs: Long) {
        val thr = (8f / pxPerSec.value * 1000).toLong()
        engine.move(id, startMs, thr, playheadMs.value)
        refresh()
    }
    override fun trimStart(id: Long, ms: Long) { engine.trimStart(id, ms); refresh() }
    override fun trimEnd(id: Long, ms: Long) { engine.trimEnd(id, ms); refresh() }
    override fun editEnd() { engine.discardIfNoop(); refresh(); rebuildPlaylist(); scheduleSave() }

    override fun setZoom(pxPerSecDp: Float) { pxPerSec.value = pxPerSecDp.coerceIn(12f, 400f) }
    override fun addMedia() { player.pause(); onRequestAddMedia?.invoke() }
    override fun addAudio() { events.value = "Аудиодорожка появится на следующем этапе" }
    override fun addText() { events.value = "Текстовые слои появятся на следующем этапе" }
    var onRequestAddMedia: (() -> Unit)? = null

    // ---------- операции над выбранным клипом ----------
    fun split() {
        val id = selectedId.value ?: return
        engine.checkpoint()
        if (engine.split(id, playheadMs.value) < 0) { engine.discardIfNoop(); events.value = "Поставьте курсор внутрь клипа" ; return }
        refresh(); rebuildPlaylist(); scheduleSave()
    }

    fun deleteSelected() {
        val id = selectedId.value ?: return
        engine.checkpoint(); engine.remove(id); selectedId.value = null
        refresh(); rebuildPlaylist(); scheduleSave()
    }

    fun selectAtPlayhead() {
        selectedId.value = _clips.value.firstOrNull { it.row == 0 && playheadMs.value >= it.startMs && playheadMs.value < it.endMs }?.id
    }

    fun undo() { if (engine.undo()) { refresh(); rebuildPlaylist(); scheduleSave() } }
    fun redo() { if (engine.redo()) { refresh(); rebuildPlaylist(); scheduleSave() } }

    private fun appendMedia(items: List<PickedMedia>) {
        if (items.isEmpty()) return
        engine.checkpoint()
        val firstStart = engine.totalMs
        items.forEach { m ->
            val video = m.type == MediaType.VIDEO
            engine.addClip(0, m.type, m.uri, if (video) m.durationMs else 0, if (video) m.durationMs else IMAGE_DEFAULT_MS)
        }
        refresh(); rebuildPlaylist(); scheduleSave()
        playheadMs.value = firstStart; seekPlayer(firstStart)
    }

    override fun onCleared() {
        persist()
        player.release()
        engine.close()
    }
}
