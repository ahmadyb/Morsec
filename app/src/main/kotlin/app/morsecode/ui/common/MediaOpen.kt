package app.morsecode.ui.common

import app.morsecode.core.model.MediaItem
import app.morsecode.core.model.MediaKind

/**
 * What tapping a file in Files means.
 *
 * One rule, in one place: an image opens in the viewer the app draws (master prompt
 * §4.5), anything else is handed to whatever on the device can open it. Stating it
 * here rather than inside a click lambda means the rule can be asserted in a test
 * instead of only implied by a screen — and when the players land, they join this
 * type rather than growing a second decision in a second file.
 */
public sealed interface MediaOpen {
    /** The image viewer, opened at this item. */
    public data class Viewer(public val item: MediaItem) : MediaOpen

    /** The platform's own handler for this file's type. */
    public data class HandOff(public val item: MediaItem) : MediaOpen
}

/** The destination a tap on [item] means. */
public fun mediaOpenFor(item: MediaItem): MediaOpen =
    if (item.kind == MediaKind.IMAGE && !item.isFolder) {
        MediaOpen.Viewer(item)
    } else {
        MediaOpen.HandOff(item)
    }
