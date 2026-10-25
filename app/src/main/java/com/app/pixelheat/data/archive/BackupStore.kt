package com.app.pixelheat.data.archive

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.edit
import androidx.core.net.toUri
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Writes the backup into the shared Downloads folder, which Android keeps when the app is uninstalled.
 * The previous file is kept beside the new one, so a failed write does not leave the phone with nothing.
 */
class BackupStore(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    /**
     * Where each file was created. After a reinstall the app cannot see files it made before, so
     * Android names the new one `pixel-heat (1).json`. Remembering the exact entry keeps later
     * exports and the rotation on that file instead of adding a new copy each time.
     */
    private val locations = context.applicationContext.getSharedPreferences("backup_files", Context.MODE_PRIVATE)

    /** [write] fills the new current file. The old one moves to the previous slot only if it is complete. */
    suspend fun save(write: suspend (OutputStream) -> Unit) {
        val current = find(CURRENT)
        if (current != null && looksComplete(current)) {
            writeTo(PREVIOUS) { out -> resolver.openInputStream(current)?.use { it.copyTo(out) } }
        }
        writeTo(CURRENT, write)
    }

    private suspend fun writeTo(name: String, write: suspend (OutputStream) -> Unit) {
        val existing = find(name)
        if (existing != null) {
            val out = resolver.openOutputStream(existing, "wt")
                ?: throw IllegalStateException("Couldn't update $name in Downloads.")
            out.use { write(it) }
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, DIRECTORY)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Couldn't create $name in Downloads.")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IllegalStateException("Couldn't write $name in Downloads.")
            out.use { write(it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            locations.edit { putString(name, uri.toString()) }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** A cheap check that the file was written to the end: a JSON object that is closed. */
    private fun looksComplete(uri: Uri): Boolean = runCatching {
        val pfd = resolver.openFileDescriptor(uri, "r") ?: return false
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            val channel = input.channel
            val size = channel.size()
            if (size < 2) return false
            val head = ByteBuffer.allocate(minOf(size, 32L).toInt())
            channel.read(head, 0)
            val tail = ByteBuffer.allocate(minOf(size, 32L).toInt())
            channel.read(tail, size - tail.capacity())
            val start = String(head.array(), Charsets.UTF_8).trimStart()
            val end = String(tail.array(), Charsets.UTF_8).trimEnd()
            start.startsWith("{") && end.endsWith("}")
        }
    }.getOrDefault(false)

    private fun find(name: String): Uri? {
        locations.getString(name, null)?.toUri()?.let { remembered ->
            if (exists(remembered)) return remembered
            locations.edit { remove(name) }
        }
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?"
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            arrayOf(name, DIRECTORY),
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val id = cursor.getLong(0)
            val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
            locations.edit { putString(name, uri.toString()) }
            return uri
        }
        return null
    }

    private fun exists(uri: Uri): Boolean = runCatching {
        resolver.query(uri, arrayOf(MediaStore.Downloads._ID), null, null, null)?.use { it.moveToFirst() } == true
    }.getOrDefault(false)

    companion object {
        val DIRECTORY = "${Environment.DIRECTORY_DOWNLOADS}/PixelHeat/"
        const val CURRENT = "pixel-heat.json"
        const val PREVIOUS = "pixel-heat-previous.json"
        const val LOCATION = "Downloads/PixelHeat"
    }
}
