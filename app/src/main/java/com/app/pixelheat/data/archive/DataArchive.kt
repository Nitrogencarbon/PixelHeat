package com.app.pixelheat.data.archive

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.app.pixelheat.data.ThermalRepository
import com.app.pixelheat.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Reads and writes the measurement backup. Exports and imports never run at the same time. */
class DataArchive(
    context: Context,
    private val repository: ThermalRepository,
    private val prefs: AppPreferences,
    private val store: BackupStore = BackupStore(context),
) {
    private val resolver = context.applicationContext.contentResolver
    private val gate = Mutex()

    /** Streams the database page by page into the backup file. Returns the number of samples written. */
    suspend fun export(): Int = gate.withLock {
        withContext(Dispatchers.IO) {
            val exportedAt = System.currentTimeMillis()
            var samples = 0
            store.save { out ->
                val text = out.bufferedWriter(Charsets.UTF_8)
                val writer = ArchiveWriter(text)
                writer.begin(exportedAt)
                writer.beginArray("samples")
                samples = paged({ after -> repository.samplesPage(after, PAGE) }, { it.id }, writer::sample)
                writer.endArray()
                writer.beginArray("events")
                paged({ after -> repository.eventsPage(after, PAGE) }, { it.id }, writer::event)
                writer.endArray()
                writer.beginArray("appUsage")
                paged({ after -> repository.appUsagePage(after, PAGE) }, { it.id }, writer::usage)
                writer.endArray()
                writer.end(repository.lifetime(), prefs.collectedEstimate())
                text.flush()
            }
            prefs.markExported(exportedAt)
            samples
        }
    }

    /** Decodes a file the user picked, without changing anything yet. */
    suspend fun preview(uri: Uri): ArchiveContents = withContext(Dispatchers.IO) {
        val size = sizeOf(uri)
        if (size != null && size > MAX_IMPORT_BYTES) {
            throw ArchiveFormatException("This file is too large to be a Pixel Heat backup.")
        }
        val input = resolver.openInputStream(uri) ?: throw ArchiveFormatException("Couldn't open that file.")
        input.use { ArchiveCodec.decode(it) }
    }

    /** Settings stay as they are, including the outside-temperature correction the user chose. */
    suspend fun restore(contents: ArchiveContents) = gate.withLock {
        withContext(Dispatchers.IO) {
            repository.replaceCollected(contents.samples, contents.events, contents.appUsage, contents.lifetime)
            prefs.restoreCollectedEstimate(contents.estimate.copy(ambientBiasC = prefs.ambientBiasC))
        }
    }

    suspend fun clear() = gate.withLock {
        withContext(Dispatchers.IO) {
            repository.clearCollected()
            prefs.clearCollectedEstimate()
        }
    }

    private suspend fun <T> paged(
        page: suspend (afterId: Long) -> List<T>,
        idOf: (T) -> Long,
        write: (T) -> Unit,
    ): Int {
        var after = 0L
        var count = 0
        while (true) {
            val rows = page(after)
            rows.forEach(write)
            count += rows.size
            if (rows.size < PAGE) return count
            after = idOf(rows.last())
        }
    }

    private fun sizeOf(uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull()

    private companion object {
        const val PAGE = 2_000

        /** Far above 30 days of dense sampling; anything bigger is not a backup this app wrote. */
        const val MAX_IMPORT_BYTES = 512L * 1024 * 1024
    }
}
