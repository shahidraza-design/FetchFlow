package app.fetchflow

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class DownloadItem(
    val id: Long,
    val url: String,
    val name: String,
    val state: String = "queued",
    val bytes: Long = 0,
    val total: Long = -1,
    val reason: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val partCount: Int = 8,
    val doneParts: String = "",
    val outputUri: String = "",
    val wifiOnly: Boolean = true,
    val rangeSupported: Boolean = false,
    val eTag: String = "",
    val lastModified: String = "",
    val expectedSha256: String = "",
    val scheduledAt: Long = 0L,
    val customName: Boolean = false
) {
    val progress: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    val completedPartIndexes: Set<Int> get() = doneParts.split(',').mapNotNull { it.toIntOrNull() }.toSet()
}

class DownloadStore private constructor(private val app: Context) {
    private val prefs = app.getSharedPreferences("fetchflow.downloads", Context.MODE_PRIVATE)

    @Synchronized fun items(): List<DownloadItem> = load().sortedByDescending { it.createdAt }

    @Synchronized fun item(id: Long): DownloadItem? = load().firstOrNull { it.id == id }

    @Synchronized fun add(rawUrl: String, requestedName: String, wifiOnly: Boolean, partCount: Int, expectedSha256: String = "", scheduledAt: Long = 0L): DownloadItem {
        val url = rawUrl.trim()
        val uri = Uri.parse(url)
        require(uri.scheme == "https" || uri.scheme == "http") { "Enter a valid http or https link." }
        require(uri.host?.isNotBlank() == true) { "The link needs a valid website address." }
        val hash = expectedSha256.trim().lowercase()
        require(hash.isEmpty() || hash.matches(Regex("[a-f0-9]{64}"))) { "SHA-256 must contain exactly 64 hexadecimal characters." }
        val safeName = sanitizeName(requestedName.ifBlank { nameFromUrl(uri) })
        val existing = load()
        var id = System.currentTimeMillis()
        while (existing.any { it.id == id }) id++
        val isScheduled = scheduledAt > System.currentTimeMillis()
        val item = DownloadItem(id, url, safeName, if (isScheduled) "scheduled" else "queued", partCount = partCount.coerceIn(1, 16), wifiOnly = wifiOnly, expectedSha256 = hash, scheduledAt = if (isScheduled) scheduledAt else 0L, customName = requestedName.isNotBlank())
        save(existing + item)
        if (isScheduled) ScheduledTransferLauncher.enqueue(app, item) else TransferLauncher.start(app, id)
        return item
    }

    @Synchronized fun addBatch(rawUrls: String, wifiOnly: Boolean, partCount: Int, scheduledAt: Long = 0L): List<DownloadItem> {
        val lines = rawUrls.lines().mapIndexedNotNull { index, line ->
            val value = line.trim()
            if (value.isBlank()) null else {
                val uri = Uri.parse(value)
                require(uri.scheme == "https" || uri.scheme == "http") { "Line ${index + 1}: enter an http or https link." }
                require(uri.host?.isNotBlank() == true) { "Line ${index + 1}: the link needs a valid website address." }
                value to uri
            }
        }.distinctBy { it.first }
        require(lines.isNotEmpty()) { "Paste at least one download link." }
        require(lines.size <= 50) { "Add up to 50 links at a time." }

        val existing = load()
        val usedIds = existing.mapTo(mutableSetOf()) { it.id }
        var nextId = System.currentTimeMillis()
        val isScheduled = scheduledAt > System.currentTimeMillis()
        val additions = lines.map { (url, uri) ->
            while (nextId in usedIds) nextId++
            val id = nextId++
            usedIds.add(id)
            DownloadItem(id, url, sanitizeName(nameFromUrl(uri)), if (isScheduled) "scheduled" else "queued", partCount = partCount.coerceIn(1, 16), wifiOnly = wifiOnly, scheduledAt = if (isScheduled) scheduledAt else 0L)
        }
        save(existing + additions)
        additions.forEach { item ->
            if (isScheduled) ScheduledTransferLauncher.enqueue(app, item) else TransferLauncher.start(app, item.id)
        }
        return additions
    }

    @Synchronized fun pause(id: Long) {
        val wasScheduled = item(id)?.state == "scheduled"
        update(id) { it.copy(state = "paused", reason = "Paused by you") }
        ScheduledTransferLauncher.cancel(app, id)
        if (!wasScheduled) TransferLauncher.stop(app, id)
    }

    @Synchronized fun resume(id: Long) {
        val old = item(id) ?: return
        ScheduledTransferLauncher.cancel(app, id)
        val remainsScheduled = old.scheduledAt > System.currentTimeMillis()
        val updated = old.copy(state = if (remainsScheduled) "scheduled" else "queued", reason = "")
        update(id) { updated }
        if (remainsScheduled) ScheduledTransferLauncher.enqueue(app, updated) else TransferLauncher.start(app, id)
    }

    @Synchronized fun cancel(id: Long) {
        val oldState = item(id)?.state ?: return
        update(id) { it.copy(state = "cancelled", reason = "Cancelled by you") }
        ScheduledTransferLauncher.cancel(app, id)
        if (oldState == "downloading" || oldState == "queued") TransferLauncher.stop(app, id)
        if (!TransferEngine.isActive(id)) TransferEngine.deleteParts(app, id)
    }

    @Synchronized fun retry(id: Long): DownloadItem? {
        val old = item(id) ?: return null
        update(id) { it.copy(state = "queued", reason = "") }
        ScheduledTransferLauncher.cancel(app, id)
        TransferLauncher.start(app, id)
        return old.copy(state = "queued", reason = "")
    }

    @Synchronized fun recoverLegacyTransfers(): List<Long> {
        val interrupted = load().filter { it.state == "queued" || it.state == "downloading" }
        if (interrupted.any { it.state == "downloading" }) {
            val ids = interrupted.mapTo(mutableSetOf()) { it.id }
            save(load().map { item ->
                if (item.id in ids && item.state == "downloading") item.copy(state = "queued", reason = "Resuming an interrupted transfer") else item
            })
        }
        return interrupted.map { it.id }
    }

    @Synchronized fun update(id: Long, transform: (DownloadItem) -> DownloadItem) {
        save(load().map { if (it.id == id) transform(it) else it })
    }

    fun openUri(item: DownloadItem): Uri? = item.outputUri.takeIf { it.isNotBlank() }?.let(Uri::parse)

    private fun load(): List<DownloadItem> = runCatching {
        val array = JSONArray(prefs.getString("items", "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            DownloadItem(
                id = o.getLong("id"), url = o.getString("url"), name = o.getString("name"),
                state = o.optString("state", "queued"), bytes = o.optLong("bytes", 0),
                total = o.optLong("total", -1), reason = o.optString("reason", ""),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                partCount = o.optInt("partCount", 8), doneParts = o.optString("doneParts", ""),
                outputUri = o.optString("outputUri", ""), wifiOnly = o.optBoolean("wifiOnly", true),
                rangeSupported = o.optBoolean("rangeSupported", false), eTag = o.optString("eTag", ""),
                lastModified = o.optString("lastModified", ""), expectedSha256 = o.optString("expectedSha256", ""),
                scheduledAt = o.optLong("scheduledAt", 0L), customName = o.optBoolean("customName", false)
            )
        }
    }.getOrDefault(emptyList())

    private fun save(items: List<DownloadItem>) {
        val array = JSONArray()
        items.takeLast(300).forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id); put("url", item.url); put("name", item.name)
                put("state", item.state); put("bytes", item.bytes); put("total", item.total)
                put("reason", item.reason); put("createdAt", item.createdAt)
                put("partCount", item.partCount); put("doneParts", item.doneParts)
                put("outputUri", item.outputUri); put("wifiOnly", item.wifiOnly); put("rangeSupported", item.rangeSupported)
                put("eTag", item.eTag); put("lastModified", item.lastModified)
                put("expectedSha256", item.expectedSha256)
                put("scheduledAt", item.scheduledAt)
                put("customName", item.customName)
            })
        }
        prefs.edit().putString("items", array.toString()).apply()
    }

    private fun nameFromUrl(uri: Uri): String {
        val segment = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        val decoded = runCatching { URLDecoder.decode(segment ?: "", StandardCharsets.UTF_8.name()) }.getOrDefault("")
        return decoded.ifBlank { "download-${System.currentTimeMillis()}" }
    }

    private fun sanitizeName(value: String): String {
        val name = value.trim().substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001F<>:\"|?*]"), "_")
            .trim('.', ' ')
        return name.take(150).ifBlank { "download-${System.currentTimeMillis()}" }
    }

    companion object {
        @Volatile private var instance: DownloadStore? = null
        fun get(context: Context): DownloadStore = instance ?: synchronized(this) {
            instance ?: DownloadStore(context.applicationContext).also { instance = it }
        }
    }
}

internal fun createDownloadedFile(context: Context, item: DownloadItem, mergedFile: java.io.File): Uri {
    val resolver = context.contentResolver
    val mime = java.net.URLConnection.guessContentTypeFromName(item.name) ?: "application/octet-stream"
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FetchFlow")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: error("Android could not create the file in Downloads.")
    try {
        resolver.openOutputStream(uri, "w")?.use { out -> mergedFile.inputStream().use { it.copyTo(out, 1024 * 1024) } }
            ?: error("Android could not open the destination file.")
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    } catch (failure: Throwable) {
        resolver.delete(uri, null, null)
        throw failure
    }
}
