package app.fetchflow

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** One process-wide token bucket, so segmented connections share a single aggregate cap. */
internal object DownloadThrottle {
    private val mutex = Mutex()
    @Volatile private var configuredBytesPerSecond = 0L
    private var observedBytesPerSecond = 0L
    private var tokens = 0.0
    private var lastRefillNanos = System.nanoTime()

    fun configure(bytesPerSecond: Long) {
        configuredBytesPerSecond = bytesPerSecond.coerceAtLeast(0L)
    }

    suspend fun acquire(byteCount: Int) {
        if (byteCount <= 0) return
        while (true) {
            val waitMillis = mutex.withLock {
                val rate = configuredBytesPerSecond
                if (rate <= 0L) return@withLock 0L
                val now = System.nanoTime()
                val capacity = max(rate.toDouble(), byteCount.toDouble())
                if (observedBytesPerSecond != rate) {
                    observedBytesPerSecond = rate
                    tokens = 0.0
                    lastRefillNanos = now
                } else {
                    val elapsed = (now - lastRefillNanos).coerceAtLeast(0L) / 1_000_000_000.0
                    tokens = min(capacity, tokens + elapsed * rate)
                    lastRefillNanos = now
                }
                if (tokens >= byteCount) {
                    tokens -= byteCount
                    0L
                } else {
                    ceil((byteCount - tokens) * 1000.0 / rate).toLong().coerceAtLeast(1L)
                }
            }
            if (waitMillis == 0L) return
            delay(waitMillis)
        }
    }
}
