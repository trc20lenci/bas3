package com.base.editor.core

/** Тонкая обёртка над C++ движком таймлайна (libbase_core.so). */
class NativeTimeline : AutoCloseable {
    private var handle: Long = nCreate()

    fun addClip(row: Int, type: MediaType, uri: String, srcDurMs: Long, lengthMs: Long) =
        nAddClip(handle, row, type.code, uri, srcDurMs, lengthMs)

    fun move(id: Long, startMs: Long, snapThresholdMs: Long, extraSnapMs: Long) = nMove(handle, id, startMs, snapThresholdMs, extraSnapMs)
    fun trimStart(id: Long, ms: Long) = nTrimStart(handle, id, ms)
    fun trimEnd(id: Long, ms: Long) = nTrimEnd(handle, id, ms)
    fun split(id: Long, atMs: Long) = nSplit(handle, id, atMs)
    fun remove(id: Long) = nRemove(handle, id)

    fun checkpoint() = nCheckpoint(handle)
    fun discardIfNoop() = nDiscardIfNoop(handle)
    fun undo() = nUndo(handle)
    fun redo() = nRedo(handle)
    val canUndo get() = nCanUndo(handle)
    val canRedo get() = nCanRedo(handle)
    val totalMs get() = nTotalMs(handle)

    fun serialize(): String = nSerialize(handle)
    fun load(data: String) = nDeserialize(handle, data)

    fun clips(): List<Clip> = parse(serialize())

    override fun close() {
        if (handle != 0L) { nDestroy(handle); handle = 0 }
    }

    private external fun nCreate(): Long
    private external fun nDestroy(h: Long)
    private external fun nAddClip(h: Long, row: Int, type: Int, uri: String, srcDur: Long, len: Long): Long
    private external fun nMove(h: Long, id: Long, start: Long, thr: Long, extra: Long): Boolean
    private external fun nTrimStart(h: Long, id: Long, ms: Long): Boolean
    private external fun nTrimEnd(h: Long, id: Long, ms: Long): Boolean
    private external fun nSplit(h: Long, id: Long, at: Long): Long
    private external fun nRemove(h: Long, id: Long): Boolean
    private external fun nCheckpoint(h: Long)
    private external fun nDiscardIfNoop(h: Long)
    private external fun nUndo(h: Long): Boolean
    private external fun nRedo(h: Long): Boolean
    private external fun nCanUndo(h: Long): Boolean
    private external fun nCanRedo(h: Long): Boolean
    private external fun nTotalMs(h: Long): Long
    private external fun nSerialize(h: Long): String
    private external fun nDeserialize(h: Long, data: String): Boolean

    companion object {
        init { System.loadLibrary("base_core") }

        fun parse(data: String): List<Clip> =
            data.lineSequence().drop(1).filter { it.isNotBlank() }.mapNotNull { line ->
                val f = line.split('\t', limit = 8)
                if (f.size < 8) null else Clip(
                    id = f[0].toLong(), row = f[1].toInt(), type = MediaType.of(f[2].toInt()),
                    startMs = f[3].toLong(), endMs = f[4].toLong(), srcInMs = f[5].toLong(),
                    srcDurMs = f[6].toLong(), uri = f[7],
                )
            }.toList()
    }
}
