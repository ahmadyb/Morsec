package app.morsecode.navigation

import android.net.Uri
import app.morsecode.core.model.SortDirection
import app.morsecode.core.model.SortKey
import app.morsecode.core.model.SortOrder

/**
 * Navigation graph routes.
 *
 * String routes (rather than the serialisable type-safe API) keep the graph
 * readable on navigation-compose 2.9 and make deep links trivial to add later.
 * Argument values that can contain a scheme separator — SAF tree uris — are
 * passed URL-encoded and decoded by the destination.
 */
public object Routes {
    public const val ONBOARDING: String = "onboarding"
    public const val CONNECT: String = "connect"
    public const val FILES: String = "files"
    public const val HISTORY: String = "history"
    public const val SETTINGS: String = "settings"

    public const val LOGS: String = "logs"
    public const val CRASHES: String = "crashes"
    public const val DOCTOR: String = "doctor"
    public const val HELP: String = "help"
    public const val WEBSHARE: String = "webshare"

    /** Argument name for [FOLDER]; the folder browser reads it from its SavedStateHandle. */
    public const val FOLDER_ARG: String = "treeUri"
    public const val FOLDER: String = "folder/{$FOLDER_ARG}"

    /** Route for one granted SAF folder, ready to hand to `navigate`. */
    public fun folder(encodedTreeUri: String): String = "folder/$encodedTreeUri"

    /** Argument names for [VIEWER]. */
    public const val VIEWER_ARG: String = "itemId"
    public const val VIEWER_SORT_ARG: String = "sort"
    public const val VIEWER: String = "viewer/{$VIEWER_ARG}?$VIEWER_SORT_ARG={$VIEWER_SORT_ARG}"

    /**
     * Route for one image in the viewer.
     *
     * The id says which photo was tapped; the sort token says which order the grid
     * was showing it in, so "3 of 15" in the viewer is the same 3 of 15 the user
     * counted on screen rather than a second, differently ordered list.
     */
    public fun viewer(encodedItemId: String, sort: SortOrder): String =
        "viewer/$encodedItemId?$VIEWER_SORT_ARG=${sortToken(sort)}"

    /** The tree uri a [FOLDER] destination was opened with. */
    public fun decodeFolderArg(value: String?): String = decodedArg(value)

    /** The media id a [VIEWER] destination was opened with. */
    public fun decodeViewerArg(value: String?): String = decodedArg(value)

    /** Argument names for [MUSIC]. */
    public const val MUSIC_ARG: String = "itemId"
    public const val MUSIC_SORT_ARG: String = "sort"
    public const val MUSIC: String = "music/{$MUSIC_ARG}?$MUSIC_SORT_ARG={$MUSIC_SORT_ARG}"

    /**
     * Route for one track in the music player.
     *
     * The same contract as [viewer]: the id says which track was tapped and the sort
     * token says which order the Music tab was showing, so the player's queue is the
     * list the user tapped into rather than a second, differently ordered one.
     */
    public fun music(encodedItemId: String, sort: SortOrder): String =
        "music/$encodedItemId?$MUSIC_SORT_ARG=${sortToken(sort)}"

    /** The media id a [MUSIC] destination was opened with. */
    public fun decodeMusicArg(value: String?): String = decodedArg(value)

    /** Argument name for [VIDEO]. */
    public const val VIDEO_ARG: String = "itemId"
    public const val VIDEO: String = "video/{$VIDEO_ARG}"

    /**
     * Route for one clip in the video player.
     *
     * Unlike [viewer] and [music] this carries no sort token, and the difference is the
     * screen's rather than an oversight: a video player shows one clip and has no queue,
     * so there is no second list whose order could disagree with the one Files was
     * showing. The id alone says everything the destination needs.
     */
    public fun video(encodedItemId: String): String = "video/$encodedItemId"

    /** The media id a [VIDEO] destination was opened with. */
    public fun decodeVideoArg(value: String?): String = decodedArg(value)

    /**
     * The argument that says which of the two duplex views to draw.
     *
     * One destination, two layouts: "Sending + receiving" and "Receiving + sending back" are
     * one session read from each end, so the route carries which end rather than there being
     * two screens that could drift apart.
     */
    public const val TRANSFER_ARG: String = "layout"
    public const val TRANSFER: String = "session/{$TRANSFER_ARG}"

    /** The route for one of the two duplex transfer views. */
    public fun transfer(layoutId: String): String = "session/$layoutId"

    /**
     * Navigation decodes a path argument on the way in, but a restored or
     * deep-linked value can still carry the encoding, so the rule lives here
     * rather than being re-derived in each destination: a value that is already a
     * uri is used as it is, anything else is decoded once.
     */
    private fun decodedArg(value: String?): String {
        val text = value.orEmpty()
        return if (text.startsWith("content://")) text else Uri.decode(text)
    }

    /** A sort order as one route-safe token: `date.desc`, `name.asc`. */
    public fun sortToken(order: SortOrder): String = "${order.key.id}.${order.direction.id}"

    /** The order a token names, or the app's default when it is absent or malformed. */
    public fun parseSortToken(value: String?): SortOrder {
        val parts = value.orEmpty().split('.')
        if (parts.size != 2) return SortOrder()
        val key = SortKey.entries.firstOrNull { it.id == parts[0] } ?: return SortOrder()
        val direction = SortDirection.entries.firstOrNull { it.id == parts[1] } ?: return SortOrder()
        return SortOrder(key, direction)
    }

    /** Routes that render the bottom navigation bar, and which item is lit. */
    public val topLevel: Set<String> = setOf(CONNECT, FILES, HISTORY, SETTINGS)

    /** Nested screens keep the bar visible with Settings selected, as the reference does. */
    public val settingsChildren: Set<String> = setOf(LOGS, CRASHES, DOCTOR, HELP)

    /**
     * Routes that are reserved but deliberately not registered in the graph yet:
     * their feature is still gated (WebShare lands in milestone 11). Declaring the
     * route here keeps the graph's vocabulary complete without pretending the
     * destination exists.
     */
    public val reserved: Set<String> = setOf(WEBSHARE)
}
