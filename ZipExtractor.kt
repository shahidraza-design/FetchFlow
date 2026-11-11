package app.fetchflow

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipInputStream

internal data class ZipExtractionResult(val fileCount: Int, val bytesWritten: Long, val folderName: String)

/** Extracts ZIP files into a contained MediaStore folder, with conservative size and entry limits. */
internal object ZipExtractor {
    private const val MAX_ENTRIES = 500
    private const val MAX_FILE_BYTES = 256L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 512L * 1024 * 1024

    suspend fun extract(context: Context, source: Uri, archiveName: String): ZipExtractionResult {
        val resolver = context.contentResolver
        val folderName = safeFolderName(archiveName.substringBeforeLast('.', archiveName))
        val relativeRoot = Environment.DIRECTORY_DOWNLOADS + "/FetchFlow/Extracted/$folderName/"
        val createdUris = mutableListOf<Uri>()
        val names = mutableSetOf<String>()
        var entryCount = 0
        var fileCount = 0
        var totalWritten = 0L
        try {
            val input = resolver.openInputStream(source) ?: throw IOException("Android could not open this ZIP file.")
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > MAX_ENTRIES) throw IOException("This archive has too many entries (limit: $MAX_ENTRIES).")
                    val parts = safeEntryParts(entry.name)
                    if (entry.isDirectory || parts.isEmpty()) {
                        zip.closeEntry()
                        continue
                    }
                    val relativeFile = parts.joinToString("/")
                    if (!names.add(relativeFile.lowercase(Locale.ROOT))) throw IOException("The archive contains duplicate file paths.")
                    if (entry.size > MAX_FILE_BYTES) throw IOException("An archive entry exceeds the 256 MB file limit.")

                    val destinationPath = relativeRoot + parts.dropLast(1).joinToString("/").let { if (it.isBlank()) "" else "$it/" }
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, parts.last())
                        put(MediaStore.MediaColumns.MIME_TYPE, java.net.URLConnection.guessContentTypeFromName(parts.last()) ?: "application/octet-stream")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, destinationPath)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val outputUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IOException("Android could not create an extracted file.")
                    createdUris.add(outputUri)
                    var entryWritten = 0L
                    val output = resolver.openOutputStream(outputUri, "w") ?: throw IOException("Android could not write an extracted file.")
                    output.buffered().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = zip.read(buffer)
                            if (read < 0) break
                            entryWritten += read
                            totalWritten += read
                            if (entryWritten > MAX_FILE_BYTES || totalWritten > MAX_TOTAL_BYTES) {
                                throw IOException("Archive extraction stopped at the 512 MB total safety limit.")
                            }
                            out.write(buffer, 0, read)
                        }
                    }
                    if (entry.size >= 0 && entryWritten != entry.size) throw IOException("An archive entry ended before its declared size.")
                    resolver.update(outputUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    fileCount++
                    zip.closeEntry()
                }
            }
            if (fileCount == 0) throw IOException("This ZIP archive contains no files to extract.")
            return ZipExtractionResult(fileCount, totalWritten, folderName)
        } catch (failure: Throwable) {
            createdUris.asReversed().forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
            throw failure
        }
    }

    private fun safeEntryParts(rawName: String): List<String> {
        val normalized = rawName.replace('\\', '/')
        if (normalized.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
            throw IOException("The ZIP contains an unsafe absolute path.")
        }
        val rawParts = normalized.split('/')
        if (rawParts.any { it == ".." }) throw IOException("The ZIP contains a path-traversal entry.")
        return rawParts.filter { it.isNotBlank() && it != "." }.map { part ->
            part.replace(Regex("[\\u0000-\\u001F<>:\"|?*]"), "_").trim('.', ' ').take(120)
                .ifBlank { throw IOException("The ZIP contains an invalid file name.") }
        }
    }

    private fun safeFolderName(value: String): String = value
        .replace(Regex("[\\u0000-\\u001F<>:\"/\\\\|?*]"), "_")
        .trim('.', ' ').take(80).ifBlank { "Extracted files" }
}
