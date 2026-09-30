package com.base.editor.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.base.editor.core.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DeviceMedia(val uri: Uri, val type: MediaType, val durationMs: Long)

object MediaRepository {
    suspend fun query(ctx: Context, video: Boolean, limit: Int = 3000): List<DeviceMedia> = withContext(Dispatchers.IO) {
        val coll = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val proj = if (video) arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DURATION) else arrayOf(MediaStore.Images.Media._ID)
        val out = ArrayList<DeviceMedia>()
        runCatching {
            ctx.contentResolver.query(coll, proj, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val id = c.getLong(0)
                    val dur = if (video) c.getLong(1) else 0L
                    if (video && dur <= 0) continue
                    out += DeviceMedia(ContentUris.withAppendedId(coll, id), if (video) MediaType.VIDEO else MediaType.IMAGE, dur)
                }
            }
        }
        out
    }
}
