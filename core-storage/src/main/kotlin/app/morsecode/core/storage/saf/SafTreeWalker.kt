package app.morsecode.core.storage.saf

import app.morsecode.core.model.MediaItem
import java.util.ArrayDeque

/**
 * Breadth-first SAF tree traversal, kept independent of Android for focused tests.
 *
 * A provider can expose the same directory through overlapping grants, or return a
 * cyclic folder reference. Each folder URI is visited at most once; the starting
 * folder itself is not included in the result.
 */
internal object SafTreeWalker {

    fun descendants(
        rootUri: String,
        childrenOf: (String) -> List<MediaItem>,
    ): List<MediaItem> {
        val pending = ArrayDeque<String>()
        val visitedFolders = mutableSetOf(rootUri)
        val descendants = mutableListOf<MediaItem>()
        pending.addLast(rootUri)

        while (pending.isNotEmpty()) {
            val parentUri = pending.removeFirst()
            childrenOf(parentUri).forEach { item ->
                descendants += item
                if (item.isFolder) {
                    val childUri = item.uriString
                    if (childUri != null && visitedFolders.add(childUri)) {
                        pending.addLast(childUri)
                    }
                }
            }
        }
        return descendants
    }
}
