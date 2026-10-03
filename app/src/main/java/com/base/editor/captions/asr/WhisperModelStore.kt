package com.base.editor.captions.asr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Локальное хранилище модели распознавания (filesDir/asr). Скачивание происходит тихо при первой генерации,
 * дальше модель берётся из кэша мгновенно. Файл проверяется по размеру и SHA-256.
 */
class WhisperModelStore(context: Context, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val dir = File(context.applicationContext.filesDir, "asr").apply { mkdirs() }
    private val model = File(dir, FILE_NAME)
    private val ok = File(dir, "$FILE_NAME.ok")

    private fun cached() = ok.exists() && model.exists() && model.length() == SIZE

    /** Возвращает файл модели; если его ещё нет — скачивает (до 3 попыток). */
    suspend fun ensure(): File = withContext(io) {
        if (cached()) return@withContext model
        var last: Exception? = null
        repeat(ATTEMPTS) { attempt ->
            try { download(); return@withContext model } catch (e: IOException) {
                last = e; Log.w(TAG, "загрузка, попытка ${attempt + 1}", e); delay(1500L * (attempt + 1))
            }
        }
        throw last ?: IOException("Не удалось подготовить распознавание")
    }

    private suspend fun download() {
        val part = File(dir, "$FILE_NAME.part").apply { delete() }
        ok.delete()
        val conn = (URL(URL_PINNED).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 30_000 }
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val md = MessageDigest.getInstance("SHA-256")
            conn.inputStream.buffered(128 * 1024).use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); md.update(buf, 0, n)
                    }
                }
            }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            if (part.length() != SIZE || sha != SHA256) { part.delete(); throw IOException("Файл повреждён") }
            model.delete()
            if (!part.renameTo(model)) throw IOException("Не удалось сохранить файл")
            ok.writeText(sha)
        } finally { conn.disconnect(); if (part.exists() && !model.exists()) part.delete() }
    }

    private companion object {
        const val TAG = "BaseAsr"
        const val FILE_NAME = "speech-tiny.tflite"
        const val SIZE = 69_370_320L
        const val SHA256 = "31679095a44c7278db4ad828d1c885805f8df66fa31fb8a2bf0f0851a6d0a380"
        const val ATTEMPTS = 3
        // зафиксированная версия файла: содержимое по этому адресу не меняется
        const val URL_PINNED = "https://raw.githubusercontent.com/nyadla-sys/whisper.tflite/064c2e1538cb59dcc3c3cb7c8fce2b8d39c6fc19/whisper_android/app/src/main/assets/whisper-tiny.tflite"
    }
}
