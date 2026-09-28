package app.morsecode.core.design.tokens

/**
 * The approved visual reference, transcribed.
 *
 * Every value in this file comes from `doc/morsecode_material3_mockup.html`
 * (Material 3 interactive mockup v2.4). The three marked regions are read back
 * by `tools/verify` which re-parses the mockup's own CSS and fails the build on
 * any drift, so the design system cannot quietly diverge from the reference.
 *
 * Cascade note: the mockup layers a "MATERIAL 3 REDESIGN — v2.0" override block
 * plus v2.2/v2.3/v2.4 refinement blocks over the original prototype CSS. Where a
 * variable or rule is redeclared, the LAST declaration wins — the values below
 * are always the effective ones, i.e. the M3 layer, not the prototype layer.
 */
public object MockupTokens {

    // MOCKUP-RAW-BEGIN
    /**
     * Colour custom properties keyed by "cssName|scope".
     *
     * Scopes are `dark`, `light`, `root` (theme independent) and one per accent
     * (`sunflower`, `leaf`, `ember`, `violet`, `sky`).
     */
    public val rawColors: Map<String, String> = mapOf(
        // --- status colours (:root, theme independent) -----------------------
        "ok|root" to "#22C55E",
        "recv|root" to "#A78BFA",
        "recvBg|root" to "#412D6D",
        "warn|root" to "#F59E0B",
        "err|root" to "#EF4444",
        "info|root" to "#0EA5E9",
        "lime|root" to "#84CC16",

        // --- accent defaults (:root = sunflower) ----------------------------
        "acc|root" to "#FACC15",
        "acc2|root" to "#EAB308",
        "accInk|root" to "#1A1400",

        // --- per accent overrides -------------------------------------------
        "acc|sunflower" to "#FACC15",
        "acc2|sunflower" to "#EAB308",
        "accInk|sunflower" to "#1A1400",
        "acc|leaf" to "#84CC16",
        "acc2|leaf" to "#65A30D",
        "accInk|leaf" to "#1A1400",
        "acc|ember" to "#EA580C",
        "acc2|ember" to "#C2410C",
        "accInk|ember" to "#FFFFFF",
        "acc|violet" to "#8B5CF6",
        "acc2|violet" to "#7C3AED",
        "accInk|violet" to "#FFFFFF",
        "acc|sky" to "#0EA5E9",
        "acc2|sky" to "#0284C7",
        "accInk|sky" to "#1A1400",

        // --- dark theme (M3 override layer) ---------------------------------
        "bg|dark" to "#10100D",
        "card|dark" to "#1B1B17",
        "raised|dark" to "#26251F",
        "pressed|dark" to "#302F28",
        "line|dark" to "#33322B",
        "t1|dark" to "#E8E5DB",
        "t2|dark" to "#CAC6BA",
        "t3|dark" to "#918F86",
        "deskBg|dark" to "#0C0C0A",
        "deskPanel|dark" to "#151511",
        "bezel|dark" to "#090A08",
        "bezelLine|dark" to "#2E2E28",

        // --- light theme (M3 override layer) --------------------------------
        "bg|light" to "#FFFBF2",
        "card|light" to "#F7F2E8",
        "raised|light" to "#EEE9DF",
        "pressed|light" to "#E7E1D5",
        "line|light" to "#D9D3C7",
        "t1|light" to "#201E18",
        "t2|light" to "#555148",
        "t3|light" to "#777269",
        "deskBg|light" to "#ECE8DF",
        "deskPanel|light" to "#FFFBF2",
        "bezel|light" to "#171713",
        "bezelLine|light" to "#30302A",

        // --- immersive surfaces (photo viewer / video player) ---------------
        "viewerBackdrop|root" to "#000000",
        "viewerIconBackground|root" to "#1A1A1A",
        "viewerIconContent|root" to "#DDDDDD",
        "viewerMetaText|root" to "#8A8A8A",
        "videoControlBar|root" to "#0B0B0B",

        // --- onboarding slide badges (the JS `slides` array, not CSS vars) ---
        "onb1a|root" to "#F59E0B",
        "onb1b|root" to "#EA580C",
        "onb2a|root" to "#22C55E",
        "onb2b|root" to "#16A34A",
        "onb3a|root" to "#0EA5E9",
        "onb3b|root" to "#0284C7",
        "onb4a|root" to "#8B5CF6",
        "onb4b|root" to "#7C3AED",
    )
    // MOCKUP-RAW-END

    // MOCKUP-DERIVED-BEGIN
    /**
     * Colours the mockup computes with `color-mix(in srgb, ...)`.
     *
     * Two kinds exist in the reference:
     *  - [MixKind.SOLID]  `color-mix(in srgb, A p%, B)`  — sRGB interpolation of
     *    two opaque colours, A weighted by `fraction`.
     *  - [MixKind.ALPHA]  `color-mix(in srgb, A p%, transparent)` — the same
     *    colour at `fraction` alpha (CSS premultiplies, so it does not darken).
     *
     * `cssProperty` records where the formula appears in the mockup so the
     * verification harness can check the formula, not just the result.
     */
    public val derivedMixes: List<DerivedMix> = listOf(
        DerivedMix("primaryContainer", MixKind.SOLID, "acc", 0.20f, "card", "--m3-primary-container"),
        DerivedMix("surfaceContainerLow", MixKind.SOLID, "card", 0.72f, "bg", "--m3-surface-container-low"),
        DerivedMix("outline", MixKind.ALPHA, "t2", 0.42f, null, "--m3-outline"),
        DerivedMix("chipAccentBackground", MixKind.ALPHA, "acc", 0.18f, null, ".chip.acc"),
        DerivedMix("chipOkBackground", MixKind.ALPHA, "ok", 0.18f, null, ".chip.ok"),
        DerivedMix("chipWarnBackground", MixKind.ALPHA, "warn", 0.18f, null, ".chip.warn"),
        DerivedMix("chipErrorBackground", MixKind.ALPHA, "err", 0.18f, null, ".chip.err"),
        DerivedMix("chipOutlineBorder", MixKind.ALPHA, "acc", 0.45f, null, ".chip.line"),
        DerivedMix("actionBarPrimaryPill", MixKind.ALPHA, "acc", 0.14f, null, ".ab button.pri .pill"),
        DerivedMix("fileTabSelected", MixKind.ALPHA, "acc", 0.08f, null, ".files-tabs button.on"),
        DerivedMix("radarRing", MixKind.ALPHA, "acc", 0.25f, null, ".radar::before"),
        DerivedMix("radarSweep", MixKind.ALPHA, "acc", 0.42f, null, ".radar::after"),
        DerivedMix("listenHalo", MixKind.ALPHA, "acc", 0.22f, null, ".receive-listen"),
        DerivedMix("playButtonGlow", MixKind.ALPHA, "acc", 0.45f, null, ".music-play-glow"),
        DerivedMix("scrollThumb", MixKind.SOLID, "acc", 0.62f, "t3", ".phone .scr::-webkit-scrollbar-thumb"),
        DerivedMix("scrimDark", MixKind.ALPHA, "scrimDarkBase", 0.62f, null, ".scrim"),
        DerivedMix("scrimLight", MixKind.ALPHA, "scrimLightBase", 0.38f, null, ".scrim(light)"),
        DerivedMix("focusRing", MixKind.ALPHA, "acc", 0.52f, null, ":focus-visible"),
        DerivedMix("stickyHeaderScrim", MixKind.ALPHA, "bg", 0.95f, null, ".files-hd"),
        DerivedMix("fileIconImageWash", MixKind.ALPHA, "kindImage", 0.22f, null, ".ico(image)"),
        DerivedMix("fileIconVideoWash", MixKind.ALPHA, "kindVideo", 0.22f, null, ".ico(video)"),
        DerivedMix("fileIconAudioWash", MixKind.ALPHA, "kindAudio", 0.22f, null, ".ico(audio)"),
        DerivedMix("fileIconDocWash", MixKind.ALPHA, "kindDoc", 0.22f, null, ".ico(doc)"),
        DerivedMix("fileIconZipWash", MixKind.ALPHA, "kindZip", 0.22f, null, ".ico(zip)"),
        DerivedMix("fileIconApkWash", MixKind.ALPHA, "kindApk", 0.22f, null, ".ico(apk)"),
    )
    // MOCKUP-DERIVED-END

    /** Base colours that only ever appear inside a mix. */
    public val mixBases: Map<String, String> = mapOf(
        "scrimDarkBase" to "#000000",
        "scrimLightBase" to "#141310",
        "kindImage" to "#F59E0B",
        "kindVideo" to "#0EA5E9",
        "kindAudio" to "#A78BFA",
        "kindDoc" to "#22C55E",
        "kindZip" to "#EF4444",
        "kindApk" to "#8B5CF6",
    )

    // MOCKUP-METRICS-BEGIN
    /**
     * Dimensions in dp and type sizes in sp, transcribed from the mockup's CSS.
     * The reference is a 360 x 740 dp phone whose CSS pixel grid maps 1:1 onto
     * dp; type sizes map onto sp so font scaling stays available (§4.2, §11).
     */
    public val metrics: Map<String, Float> = mapOf(
        // Corner radii — the M3 radius scale.
        "radius.xs" to 8f,
        "radius.sm" to 12f,
        "radius.md" to 16f,
        "radius.lg" to 24f,
        "radius.xl" to 28f,
        "radius.pill" to 999f,

        // Screen and header.
        "screen.paddingHorizontal" to 18f,
        "screen.paddingBottom" to 18f,
        "screen.paddingHorizontalCompact" to 16f,
        "header.minHeight" to 56f,
        "header.gap" to 8f,
        "header.paddingTop" to 4f,
        "header.paddingBottom" to 10f,
        "header.titleSize" to 24f,
        "header.titleLineHeight" to 32f,
        "header.titleWeight" to 500f,
        "header.nestedTitleSize" to 22f,

        // Icon buttons.
        "iconButton.size" to 40f,
        "iconButton.radius" to 20f,
        "iconButton.glyph" to 20f,
        "iconButton.sizeSmall" to 34f,
        "iconButton.glyphSmall" to 18f,
        "iconButton.hitTarget" to 48f,

        // Buttons.
        "button.minHeight" to 44f,
        "button.paddingHorizontal" to 20f,
        "button.paddingVertical" to 11f,
        "button.smallMinHeight" to 36f,
        "button.smallPaddingHorizontal" to 14f,
        "button.smallPaddingVertical" to 7f,
        "button.weight" to 600f,

        // Navigation bars.
        "bottomNav.height" to 72f,
        "bottomNav.paddingHorizontal" to 8f,
        "bottomNav.paddingTop" to 7f,
        "bottomNav.paddingBottom" to 10f,
        "bottomNav.itemMinWidth" to 48f,
        "bottomNav.glyph" to 21f,
        "bottomNav.labelSize" to 11f,
        "bottomNav.pillMinWidth" to 64f,
        "bottomNav.pillPaddingHorizontal" to 18f,
        "bottomNav.pillPaddingVertical" to 4f,
        "actionBar.minHeight" to 68f,
        "actionBar.buttonMinHeight" to 58f,
        "actionBar.glyph" to 18f,
        "actionBar.labelSize" to 10f,
        "actionBar.paddingHorizontal" to 5f,

        // Lists, cards, rows.
        "listItem.minHeight" to 56f,
        "listItem.gap" to 12f,
        "listItem.paddingVertical" to 10f,
        "listItem.paddingHorizontal" to 4f,
        "card.radius" to 16f,
        "card.padding" to 12f,
        // Stacked cards on Connect/Settings use `margin-bottom:14px`.
        "card.gap" to 14f,
        "wash.radius" to 24f,
        "wash.padding" to 13f,
        "fileIcon.size" to 34f,
        "fileIcon.radius" to 12f,
        "fileIcon.glyph" to 17f,
        "fileIcon.sizeSmall" to 30f,
        "avatar.size" to 30f,
        "avatar.sizeLarge" to 44f,
        "avatar.radiusLarge" to 14f,
        "avatar.textSize" to 12f,
        "avatar.textSizeLarge" to 17f,

        // Section headers and metadata type.
        "section.size" to 12f,
        "section.weight" to 600f,
        "section.letterSpacingEm" to 0.035f,
        "section.marginTop" to 22f,
        "section.marginBottom" to 8f,
        "section.actionSize" to 12f,
        "section.actionMinHeight" to 28f,
        "section.clearActionSize" to 32f,
        "meta.size" to 11f,
        "meta.lineHeight" to 1.45f,
        "muted.size" to 13f,
        "muted.lineHeight" to 1.45f,

        // Chips.
        "chip.minHeight" to 24f,
        "chip.paddingHorizontal" to 9f,
        "chip.paddingVertical" to 4f,
        "chip.size" to 9f,
        "chip.weight" to 700f,
        "chip.letterSpacingEm" to 0.05f,
        "chip.radius" to 12f,

        // Progress bars.
        "progress.height" to 5f,
        "progress.radius" to 3f,
        "progress.marginTop" to 7f,
        "progress.scrubberHeight" to 4f,
        "progress.knob" to 14f,
        "progress.scrubberPadding" to 10f,

        // Tiles, grids, selection.
        "tile.padding" to 12f,
        "tile.paddingHorizontal" to 7f,
        "tile.radius" to 16f,
        "tile.valueSize" to 19f,
        "tile.labelSize" to 8f,
        // The larger summary cards (`.statcard`) are their own component.
        "statCard.radius" to 24f,
        "statCard.padding" to 16f,
        "statCard.valueSize" to 30f,
        "statCard.labelSize" to 9f,
        "statCard.gap" to 14f,
        "grid.gapMedia" to 6f,
        "grid.gapApps" to 12f,
        "grid.tileRadius" to 12f,
        "grid.columnsAtReferenceWidth" to 3f,
        "grid.referenceWidth" to 360f,
        "checkbox.size" to 20f,
        "checkbox.radius" to 6f,
        "checkbox.glyph" to 13f,
        "photoTick.size" to 19f,
        "selectionBar.bottomOffset" to 80f,
        "selectionBar.padding" to 8f,
        "selectionBar.radius" to 24f,

        // Discovery radar.
        "radar.size" to 156f,
        "radar.sizeNested" to 118f,
        "radar.coreSize" to 44f,
        "radar.blip" to 7f,
        "radar.sweepDurationMillis" to 2400f,
        "radar.blipPulseDurationMillis" to 1600f,

        // Category tabs (v2.4: flat, all five fit, no scrollbar).
        "tab.fontSize" to 11f,
        "tab.minWidth" to 62f,
        "tab.paddingVertical" to 11f,
        "tab.paddingBottom" to 10f,
        "tab.paddingHorizontal" to 3f,
        "tab.selectedWeight" to 800f,
        "tab.unselectedWeight" to 500f,
        "tab.indicatorHeight" to 3f,
        "tab.indicatorInsetFraction" to 0.12f,
        "tab.marginBottom" to 16f,

        // Switch.
        "switch.width" to 52f,
        "switch.height" to 32f,
        "switch.borderWidth" to 2f,
        "switch.thumbOff" to 18f,
        "switch.thumbOn" to 22f,
        "switch.thumbInsetOff" to 5f,
        "switch.thumbInsetOn" to 3f,
        "switch.thumbTravelOn" to 25f,

        // Sheets, dialogs, toasts.
        "sheet.radius" to 28f,
        "sheet.paddingHorizontal" to 20f,
        "sheet.paddingBottom" to 20f,
        "sheet.maxHeightFraction" to 0.82f,
        "sheet.grabWidth" to 32f,
        "sheet.grabHeight" to 4f,
        "dialog.radius" to 28f,
        "dialog.paddingHorizontal" to 18f,
        "dialog.paddingTop" to 22f,
        "dialog.titleSize" to 16f,
        "toast.radius" to 8f,
        "toast.paddingHorizontal" to 16f,
        "toast.paddingVertical" to 10f,
        "toast.size" to 13f,
        "toast.bottomOffset" to 26f,

        // Onboarding.
        "onboarding.badge" to 92f,
        "onboarding.badgeRadius" to 22f,
        "onboarding.glyph" to 46f,
        "onboarding.titleSize" to 23f,
        "onboarding.dot" to 7f,
        "onboarding.dotActiveWidth" to 18f,
        "onboarding.paddingHorizontal" to 28f,
        "onboarding.badgeGap" to 28f,
        // CSS `box-shadow: 0 12px 34px <c>44`; Compose takes one elevation.
        "onboarding.badgeElevation" to 12f,
        "onboarding.titleGap" to 14f,
        "onboarding.bodyGap" to 30f,
        "onboarding.dotsGap" to 26f,
        "onboarding.dotGap" to 7f,
        "onboarding.linkGap" to 14f,

        // Players.
        "music.artwork" to 190f,
        "music.artworkRadius" to 18f,
        "music.artworkGlyph" to 64f,
        "music.playButton" to 64f,
        "music.playGlyph" to 26f,
        "music.titleSize" to 20f,
        "music.rowActionHeight" to 56f,
        "video.playOverlay" to 74f,
        "video.playGlyph" to 30f,
        "video.controlPlay" to 52f,
        "video.volumeStepWidth" to 5f,
        "video.volumeStepHeight" to 15f,
        "video.volumeSteps" to 10f,

        // Address / breadcrumb bars.
        "addressBar.minHeight" to 44f,
        "addressBar.radius" to 12f,
        "addressBar.padding" to 12f,
        "addressBar.size" to 15f,
        "addressBar.nestedSize" to 11f,
        "addressBar.nestedPaddingHorizontal" to 10f,
        "addressBar.nestedPaddingVertical" to 8f,

        // Scrollbars (hairline, accent toned, transparent track).
        "scrollbar.thickness" to 2f,
        "scrollbar.minThumbLength" to 24f,

        // Motion.
        "motion.stateDurationMillis" to 180f,
        "motion.scrubDurationMillis" to 250f,
        "motion.sheetDurationMillis" to 220f,
        "motion.swipeSettleDurationMillis" to 220f,
        "motion.emphasizedControlX1" to 0.2f,
        "motion.emphasizedControlY1" to 0f,
        "motion.emphasizedControlX2" to 0f,
        "motion.emphasizedControlY2" to 1f,
        "motion.swipeDecelerateX1" to 0.22f,
        "motion.swipeDecelerateY1" to 0.61f,
        "motion.swipeDecelerateX2" to 0.36f,
        "motion.swipeDecelerateY2" to 1f,
        "motion.pressedScale" to 0.97f,

        // Touch targets and accessibility floors.
        "a11y.minTouchTarget" to 48f,
    )
    // MOCKUP-METRICS-END

    /** Reads a raw colour, falling back to the theme independent value. */
    public fun rawColor(name: String, scope: String): String =
        rawColors["$name|$scope"] ?: rawColors["$name|root"]
            ?: error("mockup token $name is not defined for scope $scope")

    /** Parses "#RRGGBB" into an ARGB long. */
    public fun argb(hex: String): Long {
        val body = hex.removePrefix("#")
        require(body.length == 6 || body.length == 8) { "unsupported colour literal: $hex" }
        val value = body.toLong(16)
        return if (body.length == 8) value else 0xFF000000L or value
    }
}

/** How a derived colour is produced from two raw colours. */
public enum class MixKind {
    /** `color-mix(in srgb, A p%, B)` — opaque sRGB interpolation. */
    SOLID,

    /** `color-mix(in srgb, A p%, transparent)` — A at `p` alpha. */
    ALPHA,
}

/**
 * @param name token name used by [app.morsecode.core.design.tokens.MorseColorTokens]
 * @param fraction the percentage in the CSS `color-mix`, as a 0..1 fraction
 * @param with partner colour for [MixKind.SOLID], null for [MixKind.ALPHA]
 * @param cssSource where the formula lives in the mockup (audit trail)
 */
public data class DerivedMix(
    val name: String,
    val kind: MixKind,
    val base: String,
    val fraction: Float,
    val with: String?,
    val cssSource: String,
)
