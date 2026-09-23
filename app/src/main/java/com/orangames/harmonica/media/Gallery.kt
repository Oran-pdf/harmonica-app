package com.orangames.harmonica.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object Gallery {
    fun publish(context: Context, video: File, title: String, existing: String?): Uri {
        val current = existing?.let { Uri.parse(it) }
        if (current != null && overwrite(context, current, video)) return current
        return insert(context, video, title)
    }

    fun exists(context: Context, uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        val uri = Uri.parse(uriString)
        return try {
            if (uri.scheme == "file") {
                val path = uri.path ?: return false
                File(path).exists() && File(path).length() > 0L
            } else {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize > 0L } ?: false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun delete(context: Context, uriString: String?) {
        if (uriString.isNullOrBlank()) return
        val uri = Uri.parse(uriString)
        try {
            if (uri.scheme == "file") {
                uri.path?.let { File(it).delete() }
            } else {
                context.contentResolver.delete(uri, null, null)
            }
        } catch (_: Exception) {
        }
    }

    fun thumbnail(context: Context, uriString: String?): Bitmap? {
        if (uriString.isNullOrBlank()) return null
        val retriever = MediaMetadataRetriever()
        return try {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "file") {
                retriever.setDataSource(uri.path)
            } else {
                retriever.setDataSource(context, uri)
            }
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val scale = 480f / frame.width.coerceAtLeast(1)
            if (scale >= 1f) frame else Bitmap.createScaledBitmap(
                frame,
                480,
                (frame.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun overwrite(context: Context, uri: Uri, video: File): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                video.inputStream().use { it.copyTo(out) }
            } != null
        } catch (_: Exception) {
            false
        }
    }

    private fun insert(context: Context, video: File, title: String): Uri {
        val name = title.replace(Regex("[\\\\/:*?\"<>|]"), "-").take(80).ifBlank { "Harmonica" } + ".mp4"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Harmonica")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not save the video on this phone")
            context.contentResolver.openOutputStream(uri)?.use { out ->
                video.inputStream().use { it.copyTo(out) }
            } ?: error("Could not write the video")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            return uri
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Harmonica")
        if (!dir.exists()) dir.mkdirs()
        val dest = File(dir, name)
        video.copyTo(dest, overwrite = true)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, dest.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATA, dest.absolutePath)
        }
        return context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: Uri.fromFile(dest)
    }
}
