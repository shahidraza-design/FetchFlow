package app.fetchflow

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.min

/** A resumable HTTP(S) engine. Completed ranges are checkpointed; an interrupted range is retried whole. */
internal class TransferEngine(private val context: Context) {
    private val store = DownloadStore.get(context)

    init {
        DownloadThrottle.configure(context.getSharedPreferences("fetchflow.preferences", Context.MODE_PRIVATE).getLong("speed_limit", 0L))
    }

    suspend fun execute(id: Long, progress: suspend (DownloadItem) -> Unit = {}) = withContext(Dispatchers.IO) {
        val initial = store.item(id) ?: return@withContext
        if (initial.state == "paused" || initial.state == "cancelled" || initial.state == "complete") return@withContext
        if (!activeTasks.add(id)) return@withContext
        store.update(id) { it.copy(state = "downloading", reason = "") }
        val taskDir = taskDirectory(context, id).apply { mkdirs() }
        try {
            ensureRunning(id)
            val probe = probe(id, initial.url)
            if (!initial.customName) {
                probe.fileName?.let { suggested -> store.update(id) { if (it.customName) it else it.copy(name = suggested) } }
            }
            val total = probe.total
            val previous = store.item(id)
            val hasPreviousSegments = previous?.completedPartIndexes?.isNotEmpty() == true
            val validatorMatches = when {
                previous?.eTag?.isNotBlank() == true -> previous.eTag == probe.eTag
                previous?.lastModified?.isNotBlank() == true -> previous.lastModified == probe.lastModified
                else -> false
            }
            if (hasPreviousSegments && !validatorMatches) {
                taskDir.listFiles()?.filter { it.name.startsWith("part-") }?.forEach { it.delete() }
            }
            if (probe.rangeSupported && total > 0) {
                store.update(id) {
                    it.copy(total = total, partCount = choosePartCount(total, it.partCount), rangeSupported = true,
                        doneParts = if (hasPreviousSegments && !validatorMatches) "" else it.doneParts,
                        bytes = if (hasPreviousSegments && !validatorMatches) 0 else it.bytes,
                        eTag = probe.eTag, lastModified = probe.lastModified)
                }
            } else {
                taskDir.listFiles()?.filter { it.name.startsWith("part-") }?.forEach { it.delete() }
                store.update(id) { it.copy(total = total, partCount = 1, doneParts = "", bytes = 0, rangeSupported = false) }
            }
            val current = store.item(id) ?: return@withContext
            if (probe.rangeSupported && total > 0) {
                try {
                    downloadRanges(current, total, current.partCount, taskDir, progress)
                } catch (_: RangeUnsupportedException) {
                    taskDir.listFiles()?.filter { it.name.startsWith("part-") }?.forEach { it.delete() }
                    store.update(id) { it.copy(rangeSupported = false, partCount = 1, doneParts = "", bytes = 0) }
                    downloadSingle(store.item(id) ?: current, total, taskDir, progress)
                }
            } else downloadSingle(current, total, taskDir, progress)

            ensureRunning(id)
            val latest = store.item(id) ?: error("Download was removed.")
            val merged = File(taskDir, "merged.tmp")
            mergeParts(latest, taskDir, merged)
            if (latest.expectedSha256.isNotBlank()) {
                val actualHash = sha256(id, merged)
                if (!actualHash.equals(latest.expectedSha256, ignoreCase = true)) {
                    throw IOException("SHA-256 verification failed. The downloaded file does not match the supplied checksum.")
                }
            }
            ensureRunning(id)
            val uri = createDownloadedFile(context, latest, merged)
            store.update(id) { it.copy(state = "complete", bytes = merged.length(), total = merged.length(), outputUri = uri.toString(), reason = "") }
            progress(store.item(id) ?: latest)
            taskDir.deleteRecursively()
        } catch (cancelled: CancellationException) {
            when (store.item(id)?.state) {
                "downloading" -> store.update(id) { it.copy(state = "queued", reason = "Transfer paused by Android; ready to resume") }
                "cancelled" -> taskDir.deleteRecursively()
            }
            throw cancelled
        } catch (failure: Throwable) {
            val message = failure.message?.takeIf { it.isNotBlank() } ?: "The transfer stopped unexpectedly. Retry to continue."
            store.update(id) { if (it.state == "paused" || it.state == "cancelled") it else it.copy(state = "failed", reason = message) }
            if (store.item(id)?.state == "cancelled") taskDir.deleteRecursively()
            progress(store.item(id) ?: initial)
        } finally {
            activeTasks.remove(id)
        }
    }

    private suspend fun downloadRanges(item: DownloadItem, total: Long, count: Int, dir: File, progress: suspend (DownloadItem) -> Unit) = coroutineScope {
        val ranges = (0 until count).map { index ->
            val start = total * index / count
            val end = (total * (index + 1) / count) - 1
            Range(index, start, end)
        }
        val done = item.completedPartIndexes.toMutableSet()
        done.retainAll(ranges.map { it.index }.toSet())
        ranges.forEach { range ->
            val file = partFile(dir, range.index)
            if (range.index !in done || file.length() != range.length) {
                file.delete(); done.remove(range.index)
            }
        }
        persistDone(item.id, done, total, dir, progress)
        ranges.filterNot { it.index in done }.map { range ->
            async(Dispatchers.IO) {
                ensureRunning(item.id)
                fetchRange(item.id, item.url, range, total, partFile(dir, range.index), item.eTag, item.lastModified, progress)
                synchronized(done) { done.add(range.index) }
                persistDone(item.id, done, total, dir, progress)
            }
        }.awaitAll()
    }

    private suspend fun fetchRange(taskId: Long, url: String, range: Range, total: Long, file: File, eTag: String, lastModified: String, progress: suspend (DownloadItem) -> Unit) {
        var last: Throwable? = null
        for (attempt in 0..2) {
            ensureRunning(taskId)
            val connection = open(url)
            register(taskId, connection)
            try {
                connection.setRequestProperty("Range", "bytes=${range.start}-${range.end}")
                (eTag.takeIf { it.isNotBlank() } ?: lastModified.takeIf { it.isNotBlank() })?.let { connection.setRequestProperty("If-Range", it) }
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_PARTIAL) throw RangeUnsupportedException("The server stopped honoring byte ranges (HTTP $status). Falling back to one connection.")
                val actual = Regex("^bytes ([0-9]+)-([0-9]+)/([0-9]+)$", RegexOption.IGNORE_CASE)
                    .matchEntire(connection.getHeaderField("Content-Range").orEmpty())
                val actualStart = actual?.groupValues?.getOrNull(1)?.toLongOrNull()
                val actualEnd = actual?.groupValues?.getOrNull(2)?.toLongOrNull()
                val actualTotal = actual?.groupValues?.getOrNull(3)?.toLongOrNull()
                if (actualStart != range.start || actualEnd != range.end || actualTotal != total) {
                    throw RangeUnsupportedException("The server returned a different byte range. Falling back to one connection.")
                }
                val expected = range.length
                file.parentFile?.mkdirs()
                var written = 0L
                try {
                    BufferedInputStream(connection.inputStream).use { input ->
                        FileOutputStream(file, false).buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                            var sinceUpdate = 0L
                            while (true) {
                                ensureRunning(taskId)
                                val read = input.read(buffer)
                                if (read < 0) break
                                DownloadThrottle.acquire(read)
                                written += read
                                if (written > expected) throw IOException("The server returned more bytes than requested.")
                                output.write(buffer, 0, read)
                                sinceUpdate += read
                                if (sinceUpdate >= 512 * 1024) {
                                    output.flush()
                                    sinceUpdate = 0
                                    val downloaded = file.parentFile?.listFiles()?.filter { it.name.startsWith("part-") }?.sumOf { it.length() } ?: written
                                    store.update(taskId) { it.copy(bytes = downloaded) }
                                    val updated = store.item(taskId)
                                    if (updated != null) progress(updated)
                                }
                            }
                        }
                    }
                } catch (error: Throwable) {
                    file.delete()
                    throw error
                }
                if (written != expected) throw IOException("This segment ended early ($written of $expected bytes). It will be retried.")
                return
            } catch (failure: Throwable) {
                file.delete()
                last = failure
                if (failure is CancellationException) throw failure
                if (failure is RangeUnsupportedException) throw failure
                if (store.item(taskId)?.state != "downloading") throw CancellationException("Transfer was stopped")
                if (attempt < 2) delay(700L * (attempt + 1))
            } finally {
                unregister(taskId, connection)
                connection.disconnect()
            }
        }
        throw IOException(last?.message ?: "A download segment failed after three attempts.", last)
    }

    private suspend fun downloadSingle(item: DownloadItem, total: Long, dir: File, progress: suspend (DownloadItem) -> Unit) {
        val file = File(dir, "single.part")
        // Without byte ranges, a partial stream cannot safely be resumed. Start this one from byte zero.
        file.delete()
        val connection = open(item.url)
        register(item.id, connection)
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException(httpReason(status))
            val actualTotal = connection.getHeaderFieldLong("Content-Length", total)
            store.update(item.id) { it.copy(total = actualTotal) }
            var readTotal = 0L
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(file).buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                    var sinceUpdate = 0L
                    while (true) {
                        ensureRunning(item.id)
                        val read = input.read(buffer)
                        if (read < 0) break
                        DownloadThrottle.acquire(read)
                        output.write(buffer, 0, read)
                        readTotal += read
                        sinceUpdate += read
                        if (sinceUpdate >= 512 * 1024) {
                            sinceUpdate = 0
                            store.update(item.id) { it.copy(bytes = readTotal, total = actualTotal) }
                            progress(store.item(item.id) ?: item)
                        }
                    }
                }
            }
            if (actualTotal > 0 && readTotal != actualTotal) throw IOException("The server closed the connection early ($readTotal of $actualTotal bytes). Retry the download.")
        } finally {
            unregister(item.id, connection)
            connection.disconnect()
        }
    }

    private suspend fun persistDone(id: Long, done: Set<Int>, total: Long, dir: File, progress: suspend (DownloadItem) -> Unit) {
        val bytes = dir.listFiles()?.filter { it.name.startsWith("part-") }?.sumOf { it.length() } ?: 0L
        val encoded = synchronized(done) { done.sorted().joinToString(",") }
        store.update(id) { it.copy(bytes = bytes, total = total, doneParts = encoded) }
        progress(store.item(id) ?: return)
    }

    private fun mergeParts(item: DownloadItem, dir: File, output: File) {
        output.delete()
        FileOutputStream(output).buffered().use { target ->
            if (item.total > 0 && item.completedPartIndexes.isNotEmpty()) {
                val count = item.partCount.coerceAtLeast(1)
                repeat(count) { index ->
                    val part = partFile(dir, index)
                    if (!part.exists()) throw IOException("A completed segment is missing. Retry the download.")
                    part.inputStream().buffered().use { it.copyTo(target) }
                }
            } else {
                val single = File(dir, "single.part")
                if (!single.exists()) throw IOException("The temporary download file is missing.")
                single.inputStream().buffered().use { it.copyTo(target) }
            }
        }
        if (item.total > 0 && output.length() != item.total) throw IOException("The downloaded file size does not match the server's size.")
    }

    private suspend fun sha256(taskId: Long, file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                ensureRunning(taskId)
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private fun probe(taskId: Long, url: String): Probe {
        val connection = open(url)
        register(taskId, connection)
        return try {
            connection.setRequestProperty("Range", "bytes=0-0")
            val status = connection.responseCode
            if (status !in 200..299 && status != 416) {
                throw IOException(httpReason(status))
            }
            val contentRange = connection.getHeaderField("Content-Range")
            val parsedTotal = Regex("/([0-9]+)$").find(contentRange.orEmpty())?.groupValues?.getOrNull(1)?.toLongOrNull() ?: -1L
            val firstRange = Regex("^bytes 0-0/([0-9]+)$", RegexOption.IGNORE_CASE).matchEntire(contentRange.orEmpty())
            val contentLength = connection.getHeaderFieldLong("Content-Length", -1L)
            Probe(status == HttpURLConnection.HTTP_PARTIAL && firstRange != null && parsedTotal > 0,
                if (parsedTotal > 0) parsedTotal else contentLength,
                connection.getHeaderField("ETag").orEmpty(), connection.getHeaderField("Last-Modified").orEmpty(),
                contentDispositionName(connection.getHeaderField("Content-Disposition")))
        } catch (error: IOException) {
            throw IOException("Could not inspect the download link: ${error.message ?: "network error"}", error)
        } finally {
            unregister(taskId, connection)
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 45_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "FetchFlow/0.9.0 Android")
        setRequestProperty("Accept-Encoding", "identity")
    }

    private fun contentDispositionName(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val encoded = Regex("filename\\*\\s*=\\s*UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.getOrNull(1)?.trim()?.trim('"')
        val basic = Regex("filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]+))", RegexOption.IGNORE_CASE)
            .find(header)?.let { it.groupValues[1].ifBlank { it.groupValues[2] }.trim() }
        val raw = encoded?.let { runCatching { java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrNull() } ?: basic
        val clean = raw?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.replace(Regex("[\\u0000-\\u001F<>:\"|?*]"), "_")?.trim('.', ' ')
            ?.take(150)?.takeIf { it.isNotBlank() }
        return clean
    }

    private fun ensureRunning(id: Long) {
        val item = store.item(id)
        val state = item?.state
        if (state == "downloading" && item.wifiOnly && !isUnmetered()) {
            store.update(id) { it.copy(state = "paused", reason = "Waiting for an unmetered connection. Resume when Wi-Fi is available.") }
            throw CancellationException("Waiting for an unmetered network")
        }
        if (state != "downloading") throw CancellationException("Transfer is $state")
    }

    private fun isUnmetered(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    companion object {
        private val connections = ConcurrentHashMap<Long, MutableSet<HttpURLConnection>>()
        private val activeTasks = ConcurrentHashMap.newKeySet<Long>()
        fun isActive(id: Long): Boolean = id in activeTasks
        fun cancel(id: Long) {
            connections[id]?.toList()?.forEach { runCatching { it.disconnect() } }
        }
        fun deleteParts(context: Context, id: Long) { taskDirectory(context, id).deleteRecursively() }
        private fun register(id: Long, connection: HttpURLConnection) {
            connections.computeIfAbsent(id) { Collections.synchronizedSet(mutableSetOf()) }.add(connection)
        }
        private fun unregister(id: Long, connection: HttpURLConnection) { connections[id]?.remove(connection) }
        private fun taskDirectory(context: Context, id: Long) = File(context.filesDir, "fetchflow-parts/$id")
        private fun partFile(dir: File, index: Int) = File(dir, "part-$index.bin")
        private fun choosePartCount(total: Long, requested: Int): Int = min(requested.coerceIn(1, 16), ceil(total / (2.0 * 1024 * 1024)).toInt().coerceAtLeast(1))
        private fun httpReason(code: Int) = when (code) {
            401, 403 -> "The server requires a sign-in or refused this link (HTTP $code)."
            404 -> "The file was not found (HTTP 404)."
            else -> "The server returned HTTP $code."
        }
    }
}

private data class Range(val index: Int, val start: Long, val end: Long) { val length: Long get() = end - start + 1 }
private data class Probe(val rangeSupported: Boolean, val total: Long, val eTag: String, val lastModified: String, val fileName: String?)
private class RangeUnsupportedException(message: String) : IOException(message)
