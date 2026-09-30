package app.morsecode.ui.common

import app.morsecode.core.model.MediaItem
import app.morsecode.core.model.MediaKind

/**
 * What tapping a file in Files means.
 *
 * One rule, in one place: a photograph opens in the viewer the app draws (master
 * prompt §4.5), a track opens in the player the app draws (§4.6), and anything else
 * is handed to whatever on the device can open it. Stating it here rather than inside
 * a click lambda means the rule can be asserted in a test instead of only implied by
 * a screen — and the video player joins this type when it lands, rather than growing
 * a second decision in a second file.
 */
public sealed interface MediaOpen {
    /** The image viewer, opened at this item. */
    public data class Viewer(public val item: MediaItem) : MediaOpen

    /** The music player, opened at this track. */
    public data class MusicPlayer(public val item: MediaItem) : MediaOpen

    /** The platform's own handler for this file's type. */
    public data class HandOff(public val item: MediaItem) : MediaOpen
}

/**
 * The destination a tap on [item] means.
 *
 * A folder always hands off, whatever kind its rows happen to be: the app's players
 * open one photograph or one track at a time, and a container is the platform's file
 * manager's business.
 */
public fun mediaOpenFor(item: MediaItem): MediaOpen = when {
    item.isFolder -> MediaOpen.HandOff(item)
    item.kind == MediaKind.IMAGE -> MediaOpen.Viewer(item)
    item.kind == MediaKind.AUDIO -> MediaOpen.MusicPlayer(item)
    else -> MediaOpen.HandOff(item)
}
