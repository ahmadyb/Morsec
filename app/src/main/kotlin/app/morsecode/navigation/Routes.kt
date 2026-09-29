package app.morsecode.navigation

import android.net.Uri

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

    /**
     * The tree uri a [FOLDER] destination was opened with.
     *
     * Navigation decodes a path argument on the way in, but a restored or
     * deep-linked value can still carry the encoding, so the rule lives here
     * rather than being re-derived in each destination: a value that is already a
     * uri is used as it is, anything else is decoded once.
     */
    public fun decodeFolderArg(value: String?): String {
        val text = value.orEmpty()
        return if (text.startsWith("content://")) text else Uri.decode(text)
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
