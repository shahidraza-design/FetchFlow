package app.fetchflow

import kotlinx.coroutines.CompletableDeferred
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/** Process-wide FIFO gate for whole-file downloads; segment connections remain parallel inside a slot. */
internal object DownloadConcurrency {
    private val lock = Any()
    private val waiters = ArrayDeque<CompletableDeferred<Unit>>()
    private var active = 0
    private var limit = 2

    fun configure(maxActiveDownloads: Int) {
        synchronized(lock) {
            limit = maxActiveDownloads.coerceIn(1, 4)
            drainWaiters()
        }
    }

    suspend fun <T> withSlot(block: suspend () -> T): T {
        val slot = acquire()
        try {
            return block()
        } finally {
            slot.release()
        }
    }

    private suspend fun acquire(): Slot {
        val signal = CompletableDeferred<Unit>()
        val grantedImmediately = synchronized(lock) {
            if (active < limit && waiters.isEmpty()) {
                active++
                true
            } else {
                waiters.addLast(signal)
                false
            }
        }
        if (!grantedImmediately) {
            try {
                signal.await()
            } catch (cancelled: Throwable) {
                synchronized(lock) {
                    if (!waiters.remove(signal) && signal.isCompleted) active--
                    drainWaiters()
                }
                throw cancelled
            }
        }
        return Slot()
    }

    private fun releaseSlot() {
        synchronized(lock) {
            active = (active - 1).coerceAtLeast(0)
            drainWaiters()
        }
    }

    private fun drainWaiters() {
        while (active < limit && waiters.isNotEmpty()) {
            val waiter = waiters.removeFirst()
            active++
            waiter.complete(Unit)
        }
    }

    private class Slot {
        private val released = AtomicBoolean(false)
        fun release() {
            if (released.compareAndSet(false, true)) releaseSlot()
        }
    }
}
