package com.base.editor.media

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionPlayer
import com.base.editor.core.IMAGE_DEFAULT_MS
import com.base.editor.core.MediaType
import com.base.editor.core.PickedMedia
import com.base.editor.core.TransitionCatalog
import com.base.editor.domain.TimelineModel
import com.base.editor.domain.TimelineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Управляет дорожками, нарезкой, позиционированием и воспроизведением.
 *
 *  • Модель ([TimelineModel]) меняется мгновенно на главном потоке (это чистая арифметика).
 *  • Тяжёлое — сборка Composition — идёт на Dispatchers.Default; запросы пересборки «склеиваются»
 *    (CONFLATED), в плеер уходит только последняя версия.
 *  • Декодирование, GL-эффекты и вывод кадров выполняет Media3 на собственных потоках;
 *    на главном потоке остаются только неблокирующие команды плееру.
 *
 * Все публичные методы — с главного потока (требование плеера).
 */
@UnstableApi
class TimelineController(
    context: Context,
    private val scope: CoroutineScope,
    catalog: TransitionCatalog,
    private val dispatchers: AppDispatchers = AppDispatchers(),
) : Player.Listener {

    private val model = TimelineModel()
    private val factory = CompositionFactory(context.applicationContext, catalog)

    val player: CompositionPlayer = CompositionPlayer.Builder(context.applicationContext).build().also { it.addListener(this) }

    private val _state = MutableStateFlow(TimelineState(emptyList(), emptyList()))
    val state: StateFlow<TimelineState> = _state.asStateFlow()
    private val _playhead = MutableStateFlow(0L)
    val playheadMs: StateFlow<Long> = _playhead.asStateFlow()
    private val _playing = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _playing.asStateFlow()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()
    /** Срабатывает после каждой завершённой правки — по нему проект сохраняется. */
    private val _committed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val committed: SharedFlow<Unit> = _committed.asSharedFlow()

    var canvas: Size = Size(720, 1280)
        private set
    private var muted = false
    private var safeMode = false
    private var stopAtMs = -1L
    private val rebuildRequests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(dispatchers.default) {                 // конвейер пересборки
            for (ignored in rebuildRequests) {
                val snapshot = _state.value
                val request = CompositionRequest(snapshot, canvas, safeMode = safeMode, onTransitionFallback = { _events.tryEmit(it) })
                val composition = runCatching { factory.build(request) }
                    .onFailure { Log.e(TAG, "не удалось собрать композицию", it); _events.tryEmit("Не удалось подготовить предпросмотр") }
                    .getOrNull()
                withContext(dispatchers.main) { applyComposition(composition) }
            }
        }
        scope.launch {                                      // опрос позиции только пока играет
            while (isActive) {
                delay(POLL_MS)
                if (player.isPlaying) {
                    val pos = player.currentPosition
                    _playhead.value = pos
                    if (stopAtMs in 0..pos) { stopAtMs = -1; pause() }
                }
            }
        }
    }

    // ───────── загрузка ─────────
    fun load(serialized: String?) {
        if (serialized != null) model.load(serialized)
        publish(); requestRebuild()
    }

    fun serialize(): String = model.serialize()

    /** Размер кадра превью/экспорта. Меняется редко (смена пропорций или качества). */
    fun setCanvas(size: Size) {
        if (size == canvas) return
        canvas = size
        requestRebuild()
    }

    // ───────── жесты и правки ─────────
    /** Начало жеста/операции: пауза + точка отката. */
    fun beginEdit() { pause(); model.checkpoint() }

    /** Промежуточные шаги жеста: обновляют только интерфейс, плеер не трогают. */
    fun move(id: Long, startMs: Long, snapMs: Long) { model.moveClip(id, startMs, snapMs, _playhead.value); publish() }
    fun trimStart(id: Long, ms: Long) { model.trimStart(id, ms); publish() }
    fun trimEnd(id: Long, ms: Long) { model.trimEnd(id, ms); publish() }

    /** Конец жеста: фиксируем и пересобираем композицию. */
    fun commitEdit() { model.discardCheckpointIfNoop(); afterStructuralEdit() }

    fun split(id: Long): Boolean {
        pause(); model.checkpoint()
        if (model.split(id, _playhead.value) < 0) { model.discardCheckpointIfNoop(); return false }
        afterStructuralEdit(); return true
    }

    fun remove(id: Long) { pause(); model.checkpoint(); model.remove(id); afterStructuralEdit() }

    fun addMedia(items: List<PickedMedia>) {
        if (items.isEmpty()) return
        pause(); model.checkpoint()
        val firstStart = model.totalMs
        items.forEach { m ->
            val video = m.type == MediaType.VIDEO
            model.addClip(0, m.type, m.uri, if (video) m.durationMs else 0, if (video) m.durationMs else IMAGE_DEFAULT_MS)
        }
        afterStructuralEdit()
        seekTo(firstStart)
    }

    fun undo() { if (model.undo()) afterStructuralEdit() }
    fun redo() { if (model.redo()) afterStructuralEdit() }

    // ───────── переходы ─────────
    fun maxTransitionMs(leftId: Long) = model.maxTransitionMs(leftId)
    fun transitionAfter(leftId: Long) = _state.value.transitions.firstOrNull { it.leftId == leftId }

    /** shaderId == null — снять переход. */
    fun setTransition(leftId: Long, shaderId: String?, durationMs: Long): Boolean {
        pause(); model.checkpoint()
        if (!model.setTransition(leftId, shaderId, durationMs)) { model.discardCheckpointIfNoop(); return false }
        model.discardCheckpointIfNoop()
        afterStructuralEdit()
        return true
    }

    /** Проигрывает окно перехода (с запасом 0.4 с до и после) — «моментальный предпросмотр». */
    fun previewTransition(leftId: Long) {
        val t = transitionAfter(leftId) ?: return
        val junction = _state.value.clips.firstOrNull { it.id == t.rightId }?.startMs ?: return
        seekTo((junction - PREVIEW_PAD_MS).coerceAtLeast(0))
        stopAtMs = junction + t.durationMs + PREVIEW_PAD_MS
        player.play()
    }

    // ───────── воспроизведение ─────────
    fun play() {
        if (_state.value.clips.none { it.row == 0 }) return
        stopAtMs = -1
        if (_playhead.value >= _state.value.totalMs - 50) seekTo(0)
        player.play()
    }

    fun pause() { player.pause() }
    fun toggle() { if (player.isPlaying) pause() else play() }

    fun seekTo(ms: Long) {
        val v = ms.coerceIn(0, _state.value.totalMs)
        _playhead.value = v
        player.seekTo(v)
    }

    fun setMuted(m: Boolean) { muted = m; player.volume = if (m) 0f else 1f }
    val isMuted get() = muted

    fun release() {
        rebuildRequests.close()
        player.removeListener(this)
        player.release()
    }

    // ───────── внутреннее ─────────
    private fun publish() {
        _state.value = model.state()
        _canUndo.value = model.canUndo
        _canRedo.value = model.canRedo
        _playhead.value = _playhead.value.coerceIn(0, _state.value.totalMs)
    }

    private fun afterStructuralEdit() {
        publish(); requestRebuild(); _committed.tryEmit(Unit)
    }

    private fun requestRebuild() { rebuildRequests.trySend(Unit) }

    private fun applyComposition(composition: Composition?) {
        if (composition == null) { player.stop(); return }
        player.setComposition(composition, _playhead.value)
        player.prepare()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) { _playing.value = isPlaying }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) { _playing.value = false; _playhead.value = _state.value.totalMs }
    }

    /**
     * Сбои MediaCodec/GL: первый раз пересобираем композицию без шейдерных эффектов (аварийный режим),
     * повторный — сообщаем пользователю.
     */
    override fun onPlayerError(error: PlaybackException) {
        Log.e(TAG, "ошибка плеера: ${error.errorCodeName}", error)
        val codecRelated = error.errorCode in CODEC_ERRORS
        if (codecRelated && !safeMode) {
            safeMode = true
            _events.tryEmit("Проблема с декодером или GPU — переходы временно отключены")
            requestRebuild()
        } else {
            _events.tryEmit("Не удалось воспроизвести: ${error.errorCodeName}")
        }
    }

    private companion object {
        const val TAG = "BaseTimeline"
        const val POLL_MS = 33L
        const val PREVIEW_PAD_MS = 400L
        val CODEC_ERRORS = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
        )
    }
}
