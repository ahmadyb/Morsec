package app.morsecode.core.storage.transfer

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Process-local, per-commit serialization shared by commit, recovery and cleanup entrypoints.
 * Entries are reference-counted and removed after the final holder/waiter leaves, so sequential
 * work does not grow a permanent lock table. This is not a cross-process lock.
 */
internal object SafCommitProcessLocks {
    private class Entry {
        val lock = ReentrantLock()
        var references: Int = 0 // Accessed only inside ConcurrentHashMap.compute*.
    }

    private val entries = ConcurrentHashMap<PartialIdentity, Entry>()

    fun <T> withCommitLock(commitId: PartialIdentity, action: () -> T): T {
        val entry = entries.compute(commitId) { _, existing ->
            (existing ?: Entry()).also { it.references++ }
        } ?: error("commit lock entry unavailable")
        try {
            entry.lock.lock()
            try {
                return action()
            } finally {
                entry.lock.unlock()
            }
        } finally {
            entries.computeIfPresent(commitId) { _, current ->
                if (current !== entry) {
                    current
                } else {
                    current.references--
                    if (current.references == 0) null else current
                }
            }
        }
    }

    /** Test seam: only currently held or awaited keys remain in this map. */
    internal fun activeKeyCount(): Int = entries.size
}
