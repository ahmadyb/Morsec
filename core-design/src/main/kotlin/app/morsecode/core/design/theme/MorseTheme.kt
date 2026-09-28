package app.morsecode.core.design.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.morsecode.core.design.tokens.MockupTokens
import app.morsecode.core.design.tokens.MorseColorTokens
import app.morsecode.core.design.tokens.MorseMetrics
import app.morsecode.core.design.tokens.MorseType
import app.morsecode.core.design.tokens.morseColorTokens
import app.morsecode.core.model.Accent
import app.morsecode.core.model.ThemeMode

/** Corner shapes from the mockup's M3 radius scale. */
public val MorseShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(MockupTokens.metrics.getValue("radius.xs").dp),
    small = RoundedCornerShape(MockupTokens.metrics.getValue("radius.sm").dp),
    medium = RoundedCornerShape(MockupTokens.metrics.getValue("radius.md").dp),
    large = RoundedCornerShape(MockupTokens.metrics.getValue("radius.lg").dp),
    extraLarge = RoundedCornerShape(MockupTokens.metrics.getValue("radius.xl").dp),
)

/** Fully rounded (the mockup's `--m3-r-full: 999px`). */
public val MorsePillShape: RoundedCornerShape = RoundedCornerShape(percent = 50)

/**
 * Motion from the mockup: `--m3-motion: cubic-bezier(.2,0,0,1)` for state
 * changes, the swipe deck's `cubic-bezier(.22,.61,.36,1)` for settling, and the
 * documented durations.
 */
@Immutable
public data class MorseMotion(
    val stateEasing: Easing,
    val swipeEasing: Easing,
    val stateMillis: Int,
    val scrubMillis: Int,
    val sheetMillis: Int,
    val swipeSettleMillis: Int,
    val radarSweepMillis: Int,
    val radarBlipMillis: Int,
    val pressedScale: Float,
    /** True when the platform or the user asked for reduced motion. */
    val reduced: Boolean,
) {
    public companion object {
        public val default: MorseMotion = MorseMotion(
            stateEasing = CubicBezierEasing(
                MockupTokens.metrics.getValue("motion.emphasizedControlX1"),
                MockupTokens.metrics.getValue("motion.emphasizedControlY1"),
                MockupTokens.metrics.getValue("motion.emphasizedControlX2"),
                MockupTokens.metrics.getValue("motion.emphasizedControlY2"),
            ),
            swipeEasing = CubicBezierEasing(
                MockupTokens.metrics.getValue("motion.swipeDecelerateX1"),
                MockupTokens.metrics.getValue("motion.swipeDecelerateY1"),
                MockupTokens.metrics.getValue("motion.swipeDecelerateX2"),
                MockupTokens.metrics.getValue("motion.swipeDecelerateY2"),
            ),
            stateMillis = MockupTokens.metrics.getValue("motion.stateDurationMillis").toInt(),
            scrubMillis = MockupTokens.metrics.getValue("motion.scrubDurationMillis").toInt(),
            sheetMillis = MockupTokens.metrics.getValue("motion.sheetDurationMillis").toInt(),
            swipeSettleMillis = MockupTokens.metrics.getValue("motion.swipeSettleDurationMillis").toInt(),
            radarSweepMillis = MockupTokens.metrics.getValue("radar.sweepDurationMillis").toInt(),
            radarBlipMillis = MockupTokens.metrics.getValue("radar.blipPulseDurationMillis").toInt(),
            pressedScale = MockupTokens.metrics.getValue("motion.pressedScale"),
            reduced = false,
        )

        /** Collapses every animation to a single frame, per `prefers-reduced-motion`. */
        public val reducedMotion: MorseMotion = default.copy(
            stateMillis = 1,
            scrubMillis = 1,
            sheetMillis = 1,
            swipeSettleMillis = 1,
            radarSweepMillis = 1,
            radarBlipMillis = 1,
            reduced = true,
        )
    }
}

/**
 * Roboto / system typography only. No font is ever fetched from the network
 * (master prompt §4.1); the monospace face stands in for the mockup's `--mono`.
 */
public val MorseTypography: Typography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = MorseType.screenTitleSize,
        lineHeight = MorseType.screenTitleLineHeight,
        fontWeight = FontWeight.W500,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = MorseType.nestedTitleSize,
        lineHeight = 28.sp,
        fontWeight = FontWeight.W500,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = MorseType.listItemTitleSize,
        fontWeight = FontWeight.W600,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = MorseType.mutedSize,
        lineHeight = MorseType.mutedLineHeight,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = MorseType.buttonSize,
        fontWeight = FontWeight.W600,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = MorseType.metaSize,
        lineHeight = MorseType.metaLineHeight,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = MorseType.buttonSize,
        fontWeight = FontWeight.W600,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = MorseType.bottomNavLabelSize,
        fontWeight = FontWeight.W500,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = MorseType.chipSize,
        fontWeight = FontWeight.W700,
        letterSpacing = MorseType.chipLetterSpacing,
    ),
)

/** Named text styles the screens use directly, so the scale stays consistent. */
public object MorseTextStyles {
    public val screenTitle: TextStyle = TextStyle(
        fontSize = MorseType.screenTitleSize,
        lineHeight = MorseType.screenTitleLineHeight,
        fontWeight = FontWeight.W500,
    )
    public val nestedTitle: TextStyle = TextStyle(
        fontSize = MorseType.nestedTitleSize,
        lineHeight = 28.sp,
        fontWeight = FontWeight.W500,
    )
    public val sectionHeader: TextStyle = TextStyle(
        fontSize = MorseType.sectionSize,
        fontWeight = FontWeight.W600,
        letterSpacing = MorseType.sectionLetterSpacing,
    )
    public val meta: TextStyle = TextStyle(
        fontSize = MorseType.metaSize,
        lineHeight = MorseType.metaLineHeight,
    )
    public val muted: TextStyle = TextStyle(
        fontSize = MorseType.mutedSize,
        lineHeight = MorseType.mutedLineHeight,
    )
    public val listTitle: TextStyle = TextStyle(
        fontSize = MorseType.listItemTitleSize,
        fontWeight = FontWeight.W600,
    )
    public val chip: TextStyle = TextStyle(
        fontSize = MorseType.chipSize,
        fontWeight = FontWeight.W700,
        letterSpacing = MorseType.chipLetterSpacing,
    )
    public val button: TextStyle = TextStyle(
        fontSize = MorseType.buttonSize,
        fontWeight = FontWeight.W600,
    )
    public val buttonSmall: TextStyle = TextStyle(
        fontSize = MorseType.buttonSmallSize,
        fontWeight = FontWeight.W600,
    )
    public val navLabel: TextStyle = TextStyle(fontSize = MorseType.bottomNavLabelSize)
    public val navLabelSelected: TextStyle = TextStyle(
        fontSize = MorseType.bottomNavLabelSize,
        fontWeight = FontWeight.W700,
    )
    public val actionBarLabel: TextStyle = TextStyle(fontSize = MorseType.actionBarLabelSize)
    public val tabLabel: TextStyle = TextStyle(
        fontSize = MorseType.tabLabelSize,
        fontWeight = FontWeight.W500,
        textAlign = TextAlign.Center,
    )
    public val tabLabelSelected: TextStyle = TextStyle(
        fontSize = MorseType.tabLabelSize,
        fontWeight = FontWeight.W800,
        textAlign = TextAlign.Center,
    )
    public val tileValue: TextStyle = TextStyle(
        fontSize = MorseType.tileValueSize,
        fontWeight = FontWeight.W700,
    )
    public val tileLabel: TextStyle = TextStyle(
        fontSize = MorseType.tileLabelSize,
        letterSpacing = MorseType.tileLabelLetterSpacing,
    )
    public val monospacedMeta: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = MorseType.metaSize,
        lineHeight = MorseType.metaLineHeight,
    )
    public val monospacedAddress: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = MorseType.addressBarNestedSize,
    )
    public val logRow: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = MorseType.logRowSize,
        lineHeight = 14.sp,
    )
    public val nowPlayingLabel: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = MorseType.nowPlayingLabelSize,
        letterSpacing = MorseType.nowPlayingLetterSpacing,
        textAlign = TextAlign.Center,
    )
    public val onboardingTitle: TextStyle = TextStyle(
        fontSize = MorseType.onboardingTitleSize,
        lineHeight = MorseType.onboardingTitleLineHeight,
        fontWeight = FontWeight.W600,
        textAlign = TextAlign.Center,
    )
}

private val LocalMorseColors = staticCompositionLocalOf<MorseColorTokens> {
    error("MorseTheme missing: wrap the tree in MorseTheme { }")
}
private val LocalMorseMetrics = staticCompositionLocalOf { MorseMetrics.default }
private val LocalMorseMotion = staticCompositionLocalOf { MorseMotion.default }

/** Access point for the resolved design tokens inside a [MorseTheme]. */
public object MorseTheme {
    public val colors: MorseColorTokens
        @Composable @ReadOnlyComposable get() = LocalMorseColors.current

    public val metrics: MorseMetrics
        @Composable @ReadOnlyComposable get() = LocalMorseMetrics.current

    public val motion: MorseMotion
        @Composable @ReadOnlyComposable get() = LocalMorseMotion.current
}

/** Resolves the user's [ThemeMode] against the platform setting. */
public fun ThemeMode.resolveDarkTheme(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.FOLLOW_SYSTEM -> systemDark
}

/**
 * Application theme.
 *
 * Supplies both the Material 3 [ColorScheme]/[Typography]/[Shapes] (so stock
 * components inherit the approved palette) and the extended Morsecode tokens the
 * screens need for chips, chips washes, radar gradients and immersive players.
 *
 * @param reducedMotion mirrors the platform animator scale / accessibility
 *   setting; when true every animation collapses to one frame (§11).
 */
@Composable
public fun MorseTheme(
    themeMode: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    accent: Accent = Accent.default,
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = themeMode.resolveDarkTheme(systemDark)
    val colors = morseColorTokens(darkTheme = dark, accent = accent)
    val motion = if (reducedMotion) MorseMotion.reducedMotion else MorseMotion.default

    CompositionLocalProvider(
        LocalMorseColors provides colors,
        LocalMorseMetrics provides MorseMetrics.default,
        LocalMorseMotion provides motion,
    ) {
        MaterialTheme(
            colorScheme = colors.materialColorScheme(),
            typography = MorseTypography,
            shapes = MorseShapes,
            content = content,
        )
    }
}

/** Convenience for previews and tests: dark theme with the default accent. */
public val PreviewColorsDark: MorseColorTokens = morseColorTokens(darkTheme = true, accent = Accent.SUNFLOWER)

/** Convenience for previews and tests: light theme with the default accent. */
public val PreviewColorsLight: MorseColorTokens = morseColorTokens(darkTheme = false, accent = Accent.SUNFLOWER)

/** The true-black backdrop used by the immersive photo viewer and video player. */
public val ImmersiveBackdrop: Color = PreviewColorsDark.viewerBackdrop
