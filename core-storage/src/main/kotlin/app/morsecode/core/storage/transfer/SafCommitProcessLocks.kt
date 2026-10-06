package app.morsecode.core.storage.transfer

import java.util.HashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Process-local, per-commit serialization shared by commit, recovery and cleanup entrypoints.
 * Entries are reference-counted and removed after the final holder/waiter leaves, so sequential
 * work does not grow a permanent lock table. This is not a cross-process lock.
 *
 * The small registry uses only API-23-safe collection/synchronization operations; map default
 * methods such as ConcurrentHashMap.compute are unavailable on the minimum supported Android API.
 */
internal object SafCommitProcessLocks {
    private class Entry {
        val lock = ReentrantLock()
        var references: Int = 0 // Accessed only while holding registryMonitor.
    }

    private val entries = HashMap<PartialIdentity, Entry>()
    private val registryMonitor = Any()

    fun <T> withCommitLock(commitId: PartialIdentity, action: () -> T): T {
        val entry = synchronized(registryMonitor) {
            (entries[commitId] ?: Entry().also { entries[commitId] = it }).also {
                it.references++
            }
        }
        try {
            entry.lock.lock()
            try {
                return action()
            } finally {
                entry.lock.unlock()
            }
        } finally {
            synchronized(registryMonitor) {
                val current = entries[commitId]
                if (current === entry) {
                    current.references--
                    if (current.references == 0) entries.remove(commitId)
                }
            }
        }
    }

    /** Test seam: only currently held or awaited keys remain in this map. */
    internal fun activeKeyCount(): Int = synchronized(registryMonitor) { entries.size }
}
