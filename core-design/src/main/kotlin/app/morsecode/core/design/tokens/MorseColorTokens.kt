package app.morsecode.core.design.tokens

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import app.morsecode.core.model.Accent

/**
 * Resolved colour tokens for one theme + accent combination.
 *
 * The values are computed from [MockupTokens] with the same `color-mix` rules the
 * mockup's CSS uses, which means the running app and the approved reference agree
 * pixel for pixel rather than "approximately".
 */
@Immutable
public data class MorseColorTokens(
    // Surfaces ------------------------------------------------------------
    val background: Color,
    val card: Color,
    val raised: Color,
    val pressed: Color,
    val line: Color,
    val surfaceContainerLow: Color,
    // Text ---------------------------------------------------------------
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    // Accent -------------------------------------------------------------
    val accent: Color,
    val accentDark: Color,
    val onAccent: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    // Status -------------------------------------------------------------
    val ok: Color,
    val warn: Color,
    val error: Color,
    val info: Color,
    val receive: Color,
    val receiveBackground: Color,
    val lime: Color,
    val onOk: Color,
    val onReceive: Color,
    // Derived washes -----------------------------------------------------
    val chipAccentBackground: Color,
    val chipOkBackground: Color,
    val chipWarnBackground: Color,
    val chipErrorBackground: Color,
    val chipOutlineBorder: Color,
    val actionBarPrimaryPill: Color,
    val fileTabSelected: Color,
    val radarRing: Color,
    val radarSweep: Color,
    val listenHalo: Color,
    val playButtonGlow: Color,
    val scrollThumb: Color,
    val scrim: Color,
    val focusRing: Color,
    val stickyHeaderScrim: Color,
    val outline: Color,
    val outlineVariant: Color,
    val toastBackground: Color,
    val toastContent: Color,
    val progressTrack: Color,
    val progressStart: Color,
    val progressEnd: Color,
    // Immersive surfaces -------------------------------------------------
    val viewerBackdrop: Color,
    val viewerIconBackground: Color,
    val viewerIconContent: Color,
    val viewerMetaText: Color,
    val videoControlBar: Color,
    // File-kind colours (the mockup's KIND map) --------------------------
    val kindImage: Color,
    val kindVideo: Color,
    val kindAudio: Color,
    val kindDoc: Color,
    val kindZip: Color,
    val kindApk: Color,
    val kindFolder: Color,
    val kindOther: Color,
    // Onboarding badge gradients (slide order) ---------------------------
    val onboarding1Start: Color,
    val onboarding1End: Color,
    val onboarding2Start: Color,
    val onboarding2End: Color,
    val onboarding3Start: Color,
    val onboarding3End: Color,
    val onboarding4Start: Color,
    val onboarding4End: Color,
    val isDark: Boolean,
) {
    /**
     * The four onboarding badge gradients, each `linear-gradient(140deg, a, b)`
     * in the reference. Kept as a list so the pager indexes them directly.
     */
    public val onboardingBadgeGradients: List<Pair<Color, Color>> = listOf(
        onboarding1Start to onboarding1End,
        onboarding2Start to onboarding2End,
        onboarding3Start to onboarding3End,
        onboarding4Start to onboarding4End,
    )

    /** Wash background behind a file-kind icon: the kind colour at 22% alpha. */
    public fun kindWash(kind: app.morsecode.core.model.MediaKind): Color = when (kind) {
        app.morsecode.core.model.MediaKind.IMAGE -> kindImage.copy(alpha = 0.22f)
        app.morsecode.core.model.MediaKind.VIDEO -> kindVideo.copy(alpha = 0.22f)
        app.morsecode.core.model.MediaKind.AUDIO -> kindAudio.copy(alpha = 0.22f)
        app.morsecode.core.model.MediaKind.DOC -> kindDoc.copy(alpha = 0.22f)
        app.morsecode.core.model.MediaKind.ZIP -> kindZip.copy(alpha = 0.22f)
        app.morsecode.core.model.MediaKind.APK -> kindApk.copy(alpha = 0.22f)
        app.morsecode.core.model.MediaKind.FOLDER -> raised
        app.morsecode.core.model.MediaKind.OTHER -> kindOther.copy(alpha = 0.22f)
    }

    /** Foreground colour for a file-kind icon. */
    public fun kindForeground(kind: app.morsecode.core.model.MediaKind): Color = when (kind) {
        app.morsecode.core.model.MediaKind.FOLDER -> accent
        else -> kindColour(kind)
    }

    public fun kindColour(kind: app.morsecode.core.model.MediaKind): Color = when (kind) {
        app.morsecode.core.model.MediaKind.IMAGE -> kindImage
        app.morsecode.core.model.MediaKind.VIDEO -> kindVideo
        app.morsecode.core.model.MediaKind.AUDIO -> kindAudio
        app.morsecode.core.model.MediaKind.DOC -> kindDoc
        app.morsecode.core.model.MediaKind.ZIP -> kindZip
        app.morsecode.core.model.MediaKind.APK -> kindApk
        app.morsecode.core.model.MediaKind.FOLDER -> accent
        app.morsecode.core.model.MediaKind.OTHER -> kindOther
    }

    /**
     * Maps the token set onto Material 3 roles so that stock M3 components
     * (NavigationBar, Card, LinearProgressIndicator, Switch, sheets) render with
     * the approved palette without per-call overrides.
     */
    public fun materialColorScheme(): ColorScheme {
        val scheme = if (isDark) darkColorScheme() else lightColorScheme()
        return scheme.copy(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = accentDark,
            secondary = receive,
            onSecondary = onReceive,
            secondaryContainer = receiveBackground,
            onSecondaryContainer = receive,
            tertiary = info,
            onTertiary = viewerBackdrop,
            tertiaryContainer = info.copy(alpha = 0.18f),
            onTertiaryContainer = info,
            background = background,
            onBackground = textPrimary,
            surface = background,
            onSurface = textPrimary,
            surfaceVariant = card,
            onSurfaceVariant = textSecondary,
            surfaceTint = accent,
            inverseSurface = textPrimary,
            inverseOnSurface = background,
            error = error,
            onError = viewerBackdrop,
            errorContainer = chipErrorBackground,
            onErrorContainer = error,
            outline = outline,
            outlineVariant = outlineVariant,
            scrim = scrim,
            surfaceBright = raised,
            surfaceDim = background,
            surfaceContainer = card,
            surfaceContainerHigh = raised,
            surfaceContainerHighest = pressed,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainerLowest = background,
        )
    }
}

/** Resolves the raw + derived tokens for one theme and accent. */
public fun morseColorTokens(darkTheme: Boolean, accent: Accent): MorseColorTokens {
    val themeScope = if (darkTheme) "dark" else "light"

    fun raw(name: String, scope: String = themeScope): Color =
        Color(MockupTokens.argb(MockupTokens.rawColor(name, scope)))

    fun base(name: String): Color =
        MockupTokens.mixBases[name]?.let { Color(MockupTokens.argb(it)) } ?: raw(name)

    // Colour lookup used by the mix formulas. Accent-dependent names resolve
    // against the selected accent, everything else against the theme.
    fun lookup(name: String): Color = when (name) {
        "acc" -> raw("acc", accent.id)
        "acc2" -> raw("acc2", accent.id)
        "accInk" -> raw("accInk", accent.id)
        else -> base(name)
    }

    val derived: Map<String, Color> = MockupTokens.derivedMixes.associate { mix ->
        mix.name to when (mix.kind) {
            MixKind.SOLID -> mixSolid(lookup(mix.base), lookup(requireNotNull(mix.with) { mix.name }), mix.fraction)
            MixKind.ALPHA -> lookup(mix.base).copy(alpha = mix.fraction)
        }
    }

    fun d(name: String): Color = derived.getValue(name)

    return MorseColorTokens(
        background = raw("bg"),
        card = raw("card"),
        raised = raw("raised"),
        pressed = raw("pressed"),
        line = raw("line"),
        surfaceContainerLow = d("surfaceContainerLow"),
        textPrimary = raw("t1"),
        textSecondary = raw("t2"),
        textTertiary = raw("t3"),
        accent = raw("acc", accent.id),
        accentDark = raw("acc2", accent.id),
        onAccent = raw("accInk", accent.id),
        primaryContainer = d("primaryContainer"),
        onPrimaryContainer = raw("t1"),
        ok = raw("ok", "root"),
        warn = raw("warn", "root"),
        error = raw("err", "root"),
        info = raw("info", "root"),
        receive = raw("recv", "root"),
        receiveBackground = raw("recvBg", "root"),
        lime = raw("lime", "root"),
        onOk = Color(MockupTokens.argb("#06240F")),
        onReceive = raw("recv", "root"),
        chipAccentBackground = d("chipAccentBackground"),
        chipOkBackground = d("chipOkBackground"),
        chipWarnBackground = d("chipWarnBackground"),
        chipErrorBackground = d("chipErrorBackground"),
        chipOutlineBorder = d("chipOutlineBorder"),
        actionBarPrimaryPill = d("actionBarPrimaryPill"),
        fileTabSelected = d("fileTabSelected"),
        radarRing = d("radarRing"),
        radarSweep = d("radarSweep"),
        listenHalo = d("listenHalo"),
        playButtonGlow = d("playButtonGlow"),
        scrollThumb = d("scrollThumb"),
        scrim = if (darkTheme) d("scrimDark") else d("scrimLight"),
        focusRing = d("focusRing"),
        stickyHeaderScrim = d("stickyHeaderScrim"),
        outline = d("outline"),
        outlineVariant = raw("line"),
        // .toast{background:var(--t1);color:var(--bg)} in the M3 layer.
        toastBackground = raw("t1"),
        toastContent = raw("bg"),
        progressTrack = raw("pressed"),
        progressStart = raw("acc", accent.id),
        progressEnd = raw("lime", "root"),
        viewerBackdrop = Color(MockupTokens.argb(MockupTokens.rawColor("viewerBackdrop", "root"))),
        viewerIconBackground = Color(MockupTokens.argb(MockupTokens.rawColor("viewerIconBackground", "root"))),
        viewerIconContent = Color(MockupTokens.argb(MockupTokens.rawColor("viewerIconContent", "root"))),
        viewerMetaText = Color(MockupTokens.argb(MockupTokens.rawColor("viewerMetaText", "root"))),
        videoControlBar = Color(MockupTokens.argb(MockupTokens.rawColor("videoControlBar", "root"))),
        kindImage = base("kindImage"),
        kindVideo = base("kindVideo"),
        kindAudio = base("kindAudio"),
        kindDoc = base("kindDoc"),
        kindZip = base("kindZip"),
        kindApk = base("kindApk"),
        kindFolder = raw("raised"),
        kindOther = raw("t3"),
        onboarding1Start = raw("onb1a", "root"),
        onboarding1End = raw("onb1b", "root"),
        onboarding2Start = raw("onb2a", "root"),
        onboarding2End = raw("onb2b", "root"),
        onboarding3Start = raw("onb3a", "root"),
        onboarding3End = raw("onb3b", "root"),
        onboarding4Start = raw("onb4a", "root"),
        onboarding4End = raw("onb4b", "root"),
        isDark = darkTheme,
    )
}

/**
 * `color-mix(in srgb, first fraction%, second)`.
 *
 * CSS mixes the sRGB-encoded components directly for `in srgb`, so this is a
 * straight per-channel interpolation — no linearisation.
 */
public fun mixSolid(first: Color, second: Color, fractionOfFirst: Float): Color {
    val t = fractionOfFirst.coerceIn(0f, 1f)
    return Color(
        red = first.red * t + second.red * (1f - t),
        green = first.green * t + second.green * (1f - t),
        blue = first.blue * t + second.blue * (1f - t),
        alpha = first.alpha * t + second.alpha * (1f - t),
    )
}
