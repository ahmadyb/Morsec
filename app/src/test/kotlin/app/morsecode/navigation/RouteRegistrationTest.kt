package app.morsecode.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The graph, held to the vocabulary it declares.
 *
 * [Routes] is a catalogue of strings and [MorseApp] is the graph, and nothing in the type
 * system connects the two: a constant can be declared and never registered, a destination
 * can be registered and never reached, and both failures look exactly like shipping until
 * someone taps the control. These tests read both sources and fail the moment the two
 * disagree — the one assertion that gives its name here, "every declared active route is
 * registered", is the one that turns a dead route constant into a red build.
 *
 * The deliberate exceptions are [Routes.reserved]: a route whose feature is gated to a
 * later milestone is declared but must *not* be registered (a registered destination that
 * has no screen behind it is worse than one honestly missing), and every reserved route
 * must stay unreachable from a navigation call while it is reserved.
 */
class RouteRegistrationTest {

    private val routesSource = source("app/src/main/kotlin/app/morsecode/navigation/Routes.kt")
    private val appSource = source("app/src/main/kotlin/app/morsecode/navigation/MorseApp.kt")
    private val mainSourceRoot = File(sourceRoot("app/src/main/kotlin"))

    /** Every `public const val NAME: String = "value"` in Routes. */
    private val declared: Map<String, String> =
        Regex("""public const val (\w+): String = "([^"]*)"""")
            .findAll(routesSource)
            .associate { it.groupValues[1] to it.groupValues[2] }

    /**
     * The constants that name a navigable route rather than a nav *argument*.
     *
     * `FOLDER_ARG`, `VIEWER_ARG` and friends are values a destination reads out of the back
     * stack entry — `treeUri`, `itemId`, `layout` — and several of them deliberately share a
     * string, so counting them as routes would both demand their registration and report
     * false duplicates. The argument constants are exactly the `_ARG` ones.
     */
    private val routeConstants: Map<String, String> = declared.filterKeys { !it.endsWith("_ARG") }

    /** Every `composable(Routes.X …)` / `composable(route = Routes.X …)` in the graph. */
    private val registered: Set<String> =
        Regex("""composable\(\s*(?:route\s*=\s*)?Routes\.(\w+)""")
            .findAll(appSource)
            .map { it.groupValues[1] }
            .toSet()

    /** The route builders in [Routes]: functions that build a route string from arguments. */
    private val routeBuilders = mapOf(
        "folder" to "FOLDER",
        "viewer" to "VIEWER",
        "music" to "MUSIC",
        "video" to "VIDEO",
        "transfer" to "TRANSFER",
        "broadcastSender" to "BROADCAST_SENDER",
        "broadcastReceiver" to "BROADCAST_RECEIVER",
        "broadcastReceived" to "BROADCAST_RECEIVED",
    )

    private val registeredValues: Set<String> = registered.map { declared.getValue(it) }.toSet()

    @Test
    fun `every declared active route is registered`() {
        val reservedValues = Routes.reserved
        val reservedNames = routeConstants.filterValues { it in reservedValues }.keys
        val missing = routeConstants.keys - reservedNames - registered
        val unknown = registered - routeConstants.keys

        assertEquals("declared in Routes but never registered in the graph", emptySet<String>(), missing)
        assertEquals("registered without being declared", emptySet<String>(), unknown)
        // The exception list itself must name real constants, or a typo would silently
        // exempt nothing.
        val reservedUndeclared = reservedValues - routeConstants.values.toSet()
        assertEquals("reserved routes that no constant declares", emptySet<String>(), reservedUndeclared)
    }

    @Test
    fun `a reserved route is declared but not registered and never navigated to`() {
        assertTrue("the WebShare control is the reserved route", "webshare" in Routes.reserved)

        Routes.reserved.forEach { value ->
            assertFalse("$value must not be registered while its milestone is gated", value in registeredValues)
        }

        val navigated = navigationTargets()
        Routes.reserved.forEach { value ->
            assertFalse("nothing may navigate to the gated $value", value in navigated)
        }
    }

    @Test
    fun `bottom navigation start destinations and settings children are registered`() {
        MorseDestination.ordered.forEach { destination ->
            assertTrue("${destination.route} is a bottom destination and must be registered", destination.route in registeredValues)
        }
        Routes.topLevel.forEach { assertTrue("$it must be registered", it in registeredValues) }
        Routes.settingsChildren.forEach { assertTrue("$it must be registered", it in registeredValues) }
        // The two start destinations MainActivity chooses between.
        listOf(Routes.ONBOARDING, Routes.CONNECT).forEach {
            assertTrue("$it is a start destination and must be registered", it in registeredValues)
        }
    }

    @Test
    fun `every navigation call targets a registered destination`() {
        val targets = navigationTargets()

        targets.forEach { target ->
            assertTrue("navigated to but never registered: $target", target in registeredValues)
            assertFalse("navigated to the gated route $target", target in Routes.reserved)
        }
    }

    @Test
    fun `every registered destination can be reached from the start`() {
        // Reached either by an explicit navigation call, by being one of the four bottom
        // destinations (reached through the method reference `navigateTopLevel`, which no
        // literal scan sees), by being a settings child rendered with the bar, or by being
        // a start destination itself.
        val reachable = navigationTargets() +
            Routes.topLevel +
            Routes.settingsChildren +
            setOf(Routes.ONBOARDING, Routes.CONNECT)

        val unreachable = registeredValues - reachable
        assertEquals("registered but nothing navigates to it", emptySet<String>(), unreachable)
    }

    @Test
    fun `no route in the catalogue is a queue a manual address or a pairing screen`() {
        val banned = listOf("queue", "manual", "qr", "pair")
        routeConstants.forEach { (name, value) ->
            banned.forEach { word ->
                assertFalse(
                    "Routes.$name = \"$value\" looks like a $word route, which is removed",
                    (name + value).lowercase().contains(word),
                )
            }
        }
    }

    @Test
    fun `no two route constants share a value`() {
        val duplicates = routeConstants.values.groupBy { it }.filterValues { it.size > 1 }
        assertEquals("two constants declaring the same path would match ambiguously", emptyMap<String, List<String>>(), duplicates)
    }

    @Test
    fun `the destination enum and the navigation graph declare the same four bar routes`() {
        assertEquals(
            "the bar is CONNECT · FILES · HISTORY · SETTINGS",
            listOf("connect", "files", "history", "settings"),
            MorseDestination.ordered.map { it.route },
        )
        assertEquals(Routes.topLevel, MorseDestination.ordered.map { it.route }.toSet())
        assertTrue("settings children keep the bar with Settings lit", Routes.settingsChildren == setOf(Routes.LOGS, Routes.CRASHES, Routes.DOCTOR, Routes.HELP))
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Every route a navigation call actually asks for, as constant *values*.
     *
     * Both shapes count: `navigateSimple(Routes.CONNECT)` and the builders
     * `navigateSimple(Routes.folder(uri))`, the latter resolved through [routeBuilders] so a
     * new builder cannot appear without this map being taught about it.
     */
    private fun navigationTargets(): Set<String> {
        val targets = mutableSetOf<String>()
        val navigate = Regex("""navigate\w*\(\s*Routes\.(\w+)""")
        val popUpTo = Regex("""popUpTo\(Routes\.(\w+)""")
        val start = Regex("""startDestination\s*=\s*[^)\n]*Routes\.(\w+)""")
        val destinationArg = Regex("""navigateTopLevel\(MorseDestination\.(\w+)""")

        mainSourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = file.readText()
                navigate.findAll(text).forEach { match ->
                    val name = match.groupValues[1]
                    val isBuilder = text.getOrNull(match.range.last + 1) == '('
                    if (isBuilder) {
                        val constant = routeBuilders[name]
                            ?: error("Routes.$name is a new route builder — teach this test what it builds")
                        targets += declared.getValue(constant)
                    } else {
                        targets += declared.getValue(name)
                    }
                }
                popUpTo.findAll(text).forEach { targets += declared.getValue(it.groupValues[1]) }
                start.findAll(text).forEach { targets += declared.getValue(it.groupValues[1]) }
                destinationArg.findAll(text).forEach { targets += declared.getValue(it.groupValues[1]) }
            }
        return targets
    }

    private fun sourceRoot(path: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, path)
            if (candidate.isDirectory) return candidate.path
            dir = dir.parentFile
        }
        error("Cannot find $path above ${System.getProperty("user.dir")}")
    }

    private fun source(path: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, path)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        error("Cannot find $path above ${System.getProperty("user.dir")}")
    }
}
