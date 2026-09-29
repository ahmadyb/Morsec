package app.morsecode.core.design.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Typed access to the mockup's dimensions.
 *
 * The reference is a 360 x 740 dp phone drawn on a 1 CSS-px == 1 dp grid, so the
 * numbers below are used directly as dp (and as sp for type, which keeps font
 * scaling available). Nothing here is a hard coded physical pixel size: layouts
 * combine these tokens with window size classes and adaptive constraints.
 */
@Immutable
public data class MorseMetrics(
    val radiusXs: Dp,
    val radiusSm: Dp,
    val radiusMd: Dp,
    val radiusLg: Dp,
    val radiusXl: Dp,
    val radiusPill: Dp,
    val screenPaddingHorizontal: Dp,
    val screenPaddingBottom: Dp,
    val screenPaddingHorizontalCompact: Dp,
    val headerMinHeight: Dp,
    val headerGap: Dp,
    val headerPaddingTop: Dp,
    val headerPaddingBottom: Dp,
    val iconButtonSize: Dp,
    val iconButtonRadius: Dp,
    val iconButtonGlyph: Dp,
    val iconButtonSizeSmall: Dp,
    val iconButtonGlyphSmall: Dp,
    val touchTarget: Dp,
    val buttonMinHeight: Dp,
    val buttonPaddingHorizontal: Dp,
    val buttonPaddingVertical: Dp,
    val buttonSmallMinHeight: Dp,
    val buttonSmallPaddingHorizontal: Dp,
    val buttonSmallPaddingVertical: Dp,
    val bottomNavHeight: Dp,
    val bottomNavPaddingHorizontal: Dp,
    val bottomNavPaddingTop: Dp,
    val bottomNavPaddingBottom: Dp,
    val bottomNavItemMinWidth: Dp,
    val bottomNavGlyph: Dp,
    val bottomNavPillMinWidth: Dp,
    val bottomNavPillPaddingHorizontal: Dp,
    val bottomNavPillPaddingVertical: Dp,
    val actionBarMinHeight: Dp,
    val actionBarGlyph: Dp,
    val actionBarPaddingHorizontal: Dp,
    val listItemMinHeight: Dp,
    val listItemGap: Dp,
    val listItemPaddingVertical: Dp,
    val listItemPaddingHorizontal: Dp,
    val cardPadding: Dp,
    val cardGap: Dp,
    val washPadding: Dp,
    val fileIconSize: Dp,
    val fileIconRadius: Dp,
    val fileIconGlyph: Dp,
    val fileIconSizeSmall: Dp,
    val avatarSize: Dp,
    val avatarSizeLarge: Dp,
    val avatarTextSize: TextUnit,
    val avatarTextSizeLarge: TextUnit,
    val sectionMarginTop: Dp,
    val sectionMarginBottom: Dp,
    val sectionActionMinHeight: Dp,
    val sectionClearActionSize: Dp,
    val chipMinHeight: Dp,
    val chipRadius: Dp,
    val chipPaddingHorizontal: Dp,
    val chipPaddingVertical: Dp,
    val progressHeight: Dp,
    val progressRadius: Dp,
    val progressMarginTop: Dp,
    val scrubberHeight: Dp,
    val scrubberKnob: Dp,
    val scrubberPaddingVertical: Dp,
    val tilePadding: Dp,
    val tilePaddingHorizontal: Dp,
    val statCardRadius: Dp,
    val statCardPadding: Dp,
    val statCardValueSize: TextUnit,
    val statCardLabelSize: TextUnit,
    val statCardGap: Dp,
    val tileRadius: Dp,
    val gridGapMedia: Dp,
    val gridGapApps: Dp,
    val gridTileRadius: Dp,
    val gridColumnsAtReferenceWidth: Int,
    val gridReferenceWidth: Dp,
    val checkboxSize: Dp,
    val checkboxRadius: Dp,
    val checkboxGlyph: Dp,
    val photoTickSize: Dp,
    val selectionBarBottomOffset: Dp,
    val selectionBarPadding: Dp,
    val radarSize: Dp,
    val radarSizeNested: Dp,
    val radarCoreSize: Dp,
    val radarBlip: Dp,
    val tabMinWidth: Dp,
    val tabPaddingVertical: Dp,
    val tabPaddingBottom: Dp,
    val tabPaddingHorizontal: Dp,
    val tabIndicatorHeight: Dp,
    val tabIndicatorInsetFraction: Float,
    val tabMarginBottom: Dp,
    val switchWidth: Dp,
    val switchHeight: Dp,
    val switchBorderWidth: Dp,
    val switchThumbOff: Dp,
    val switchThumbOn: Dp,
    val sheetPaddingHorizontal: Dp,
    val sheetPaddingBottom: Dp,
    val sheetMaxHeightFraction: Float,
    val sheetGrabWidth: Dp,
    val sheetGrabHeight: Dp,
    val dialogPaddingHorizontal: Dp,
    val dialogPaddingTop: Dp,
    val toastPaddingHorizontal: Dp,
    val toastPaddingVertical: Dp,
    val toastBottomOffset: Dp,
    val onboardingBadge: Dp,
    val onboardingGlyph: Dp,
    val onboardingPaddingHorizontal: Dp,
    val onboardingBadgeRadius: Dp,
    val onboardingBadgeGap: Dp,
    val onboardingBadgeElevation: Dp,
    val onboardingTitleGap: Dp,
    val onboardingBodyGap: Dp,
    val onboardingDotsGap: Dp,
    val onboardingDotSize: Dp,
    val onboardingDotActiveWidth: Dp,
    val onboardingDotGap: Dp,
    val onboardingLinkGap: Dp,
    val musicArtwork: Dp,
    val musicPlayButton: Dp,
    val videoPlayOverlay: Dp,
    val videoControlPlay: Dp,
    val volumeStepWidth: Dp,
    val volumeStepHeight: Dp,
    val volumeSteps: Int,
    val addressBarMinHeight: Dp,
    val addressBarPadding: Dp,
    val addressBarNestedPaddingHorizontal: Dp,
    val addressBarNestedPaddingVertical: Dp,
    val scrollbarThickness: Dp,
    val scrollbarMinThumbLength: Dp,
) {
    public companion object {
        private fun dp(key: String): Dp = MockupTokens.metrics.getValue(key).dp
        private fun sp(key: String): TextUnit = MockupTokens.metrics.getValue(key).sp
        private fun int(key: String): Int = MockupTokens.metrics.getValue(key).toInt()

        /** The single resolved metric set; dimensions do not vary by theme. */
        public val default: MorseMetrics = MorseMetrics(
            radiusXs = dp("radius.xs"),
            radiusSm = dp("radius.sm"),
            radiusMd = dp("radius.md"),
            radiusLg = dp("radius.lg"),
            radiusXl = dp("radius.xl"),
            radiusPill = dp("radius.pill"),
            screenPaddingHorizontal = dp("screen.paddingHorizontal"),
            screenPaddingBottom = dp("screen.paddingBottom"),
            screenPaddingHorizontalCompact = dp("screen.paddingHorizontalCompact"),
            headerMinHeight = dp("header.minHeight"),
            headerGap = dp("header.gap"),
            headerPaddingTop = dp("header.paddingTop"),
            headerPaddingBottom = dp("header.paddingBottom"),
            iconButtonSize = dp("iconButton.size"),
            iconButtonRadius = dp("iconButton.radius"),
            iconButtonGlyph = dp("iconButton.glyph"),
            iconButtonSizeSmall = dp("iconButton.sizeSmall"),
            iconButtonGlyphSmall = dp("iconButton.glyphSmall"),
            touchTarget = dp("iconButton.hitTarget"),
            buttonMinHeight = dp("button.minHeight"),
            buttonPaddingHorizontal = dp("button.paddingHorizontal"),
            buttonPaddingVertical = dp("button.paddingVertical"),
            buttonSmallMinHeight = dp("button.smallMinHeight"),
            buttonSmallPaddingHorizontal = dp("button.smallPaddingHorizontal"),
            buttonSmallPaddingVertical = dp("button.smallPaddingVertical"),
            bottomNavHeight = dp("bottomNav.height"),
            bottomNavPaddingHorizontal = dp("bottomNav.paddingHorizontal"),
            bottomNavPaddingTop = dp("bottomNav.paddingTop"),
            bottomNavPaddingBottom = dp("bottomNav.paddingBottom"),
            bottomNavItemMinWidth = dp("bottomNav.itemMinWidth"),
            bottomNavGlyph = dp("bottomNav.glyph"),
            bottomNavPillMinWidth = dp("bottomNav.pillMinWidth"),
            bottomNavPillPaddingHorizontal = dp("bottomNav.pillPaddingHorizontal"),
            bottomNavPillPaddingVertical = dp("bottomNav.pillPaddingVertical"),
            actionBarMinHeight = dp("actionBar.minHeight"),
            actionBarGlyph = dp("actionBar.glyph"),
            actionBarPaddingHorizontal = dp("actionBar.paddingHorizontal"),
            listItemMinHeight = dp("listItem.minHeight"),
            listItemGap = dp("listItem.gap"),
            listItemPaddingVertical = dp("listItem.paddingVertical"),
            listItemPaddingHorizontal = dp("listItem.paddingHorizontal"),
            cardPadding = dp("card.padding"),
            cardGap = dp("card.gap"),
            washPadding = dp("wash.padding"),
            fileIconSize = dp("fileIcon.size"),
            fileIconRadius = dp("fileIcon.radius"),
            fileIconGlyph = dp("fileIcon.glyph"),
            fileIconSizeSmall = dp("fileIcon.sizeSmall"),
            avatarSize = dp("avatar.size"),
            avatarSizeLarge = dp("avatar.sizeLarge"),
            avatarTextSize = MockupTokens.metrics.getValue("avatar.textSize").sp,
            avatarTextSizeLarge = MockupTokens.metrics.getValue("avatar.textSizeLarge").sp,
            sectionMarginTop = dp("section.marginTop"),
            sectionMarginBottom = dp("section.marginBottom"),
            sectionActionMinHeight = dp("section.actionMinHeight"),
            sectionClearActionSize = dp("section.clearActionSize"),
            chipMinHeight = dp("chip.minHeight"),
            chipRadius = dp("chip.radius"),
            chipPaddingHorizontal = dp("chip.paddingHorizontal"),
            chipPaddingVertical = dp("chip.paddingVertical"),
            progressHeight = dp("progress.height"),
            progressRadius = dp("progress.radius"),
            progressMarginTop = dp("progress.marginTop"),
            scrubberHeight = dp("progress.scrubberHeight"),
            scrubberKnob = dp("progress.knob"),
            scrubberPaddingVertical = dp("progress.scrubberPadding"),
            tilePadding = dp("tile.padding"),
            tilePaddingHorizontal = dp("tile.paddingHorizontal"),
            statCardRadius = dp("statCard.radius"),
            statCardPadding = dp("statCard.padding"),
            statCardValueSize = sp("statCard.valueSize"),
            statCardLabelSize = sp("statCard.labelSize"),
            statCardGap = dp("statCard.gap"),
            tileRadius = dp("tile.radius"),
            gridGapMedia = dp("grid.gapMedia"),
            gridGapApps = dp("grid.gapApps"),
            gridTileRadius = dp("grid.tileRadius"),
            gridColumnsAtReferenceWidth = int("grid.columnsAtReferenceWidth"),
            gridReferenceWidth = dp("grid.referenceWidth"),
            checkboxSize = dp("checkbox.size"),
            checkboxRadius = dp("checkbox.radius"),
            checkboxGlyph = dp("checkbox.glyph"),
            photoTickSize = dp("photoTick.size"),
            selectionBarBottomOffset = dp("selectionBar.bottomOffset"),
            selectionBarPadding = dp("selectionBar.padding"),
            radarSize = dp("radar.size"),
            radarSizeNested = dp("radar.sizeNested"),
            radarCoreSize = dp("radar.coreSize"),
            radarBlip = dp("radar.blip"),
            tabMinWidth = dp("tab.minWidth"),
            tabPaddingVertical = dp("tab.paddingVertical"),
            tabPaddingBottom = dp("tab.paddingBottom"),
            tabPaddingHorizontal = dp("tab.paddingHorizontal"),
            tabIndicatorHeight = dp("tab.indicatorHeight"),
            tabIndicatorInsetFraction = MockupTokens.metrics.getValue("tab.indicatorInsetFraction"),
            tabMarginBottom = dp("tab.marginBottom"),
            switchWidth = dp("switch.width"),
            switchHeight = dp("switch.height"),
            switchBorderWidth = dp("switch.borderWidth"),
            switchThumbOff = dp("switch.thumbOff"),
            switchThumbOn = dp("switch.thumbOn"),
            sheetPaddingHorizontal = dp("sheet.paddingHorizontal"),
            sheetPaddingBottom = dp("sheet.paddingBottom"),
            sheetMaxHeightFraction = MockupTokens.metrics.getValue("sheet.maxHeightFraction"),
            sheetGrabWidth = dp("sheet.grabWidth"),
            sheetGrabHeight = dp("sheet.grabHeight"),
            dialogPaddingHorizontal = dp("dialog.paddingHorizontal"),
            dialogPaddingTop = dp("dialog.paddingTop"),
            toastPaddingHorizontal = dp("toast.paddingHorizontal"),
            toastPaddingVertical = dp("toast.paddingVertical"),
            toastBottomOffset = dp("toast.bottomOffset"),
            onboardingBadge = dp("onboarding.badge"),
            onboardingGlyph = dp("onboarding.glyph"),
            onboardingPaddingHorizontal = dp("onboarding.paddingHorizontal"),
            onboardingBadgeRadius = dp("onboarding.badgeRadius"),
            onboardingBadgeGap = dp("onboarding.badgeGap"),
            onboardingBadgeElevation = dp("onboarding.badgeElevation"),
            onboardingTitleGap = dp("onboarding.titleGap"),
            onboardingBodyGap = dp("onboarding.bodyGap"),
            onboardingDotsGap = dp("onboarding.dotsGap"),
            onboardingDotSize = dp("onboarding.dot"),
            onboardingDotActiveWidth = dp("onboarding.dotActiveWidth"),
            onboardingDotGap = dp("onboarding.dotGap"),
            onboardingLinkGap = dp("onboarding.linkGap"),
            musicArtwork = dp("music.artwork"),
            musicPlayButton = dp("music.playButton"),
            videoPlayOverlay = dp("video.playOverlay"),
            videoControlPlay = dp("video.controlPlay"),
            volumeStepWidth = dp("video.volumeStepWidth"),
            volumeStepHeight = dp("video.volumeStepHeight"),
            volumeSteps = int("video.volumeSteps"),
            addressBarMinHeight = dp("addressBar.minHeight"),
            addressBarPadding = dp("addressBar.padding"),
            addressBarNestedPaddingHorizontal = dp("addressBar.nestedPaddingHorizontal"),
            addressBarNestedPaddingVertical = dp("addressBar.nestedPaddingVertical"),
            scrollbarThickness = dp("scrollbar.thickness"),
            scrollbarMinThumbLength = dp("scrollbar.minThumbLength"),
        )
    }
}

/**
 * Type scale, transcribed from the mockup.
 *
 * `letter-spacing` is expressed in em in CSS, so each entry multiplies the font
 * size by the recorded em value to get an absolute sp offset.
 */
public object MorseType {
    private fun size(key: String): TextUnit = MockupTokens.metrics.getValue(key).sp
    private fun weight(key: String): Int = MockupTokens.metrics.getValue(key).toInt()
    private fun em(sizeKey: String, emKey: String): TextUnit =
        (MockupTokens.metrics.getValue(sizeKey) * MockupTokens.metrics.getValue(emKey)).sp

    /** Screen title: `.hd h1` — 24/32, weight 500. */
    public val screenTitleSize: TextUnit = size("header.titleSize")
    public val screenTitleLineHeight: TextUnit = size("header.titleLineHeight")
    public val screenTitleWeight: Int = weight("header.titleWeight")

    /** Nested screen title (a screen reached from another): 22sp. */
    public val nestedTitleSize: TextUnit = size("header.nestedTitleSize")

    /** `.sec` — 12sp weight 600, +0.035em. */
    public val sectionSize: TextUnit = size("section.size")
    public val sectionWeight: Int = weight("section.weight")
    public val sectionLetterSpacing: TextUnit = em("section.size", "section.letterSpacingEm")

    /** `.meta` — 11sp, line-height 1.45. */
    public val metaSize: TextUnit = size("meta.size")
    public val metaLineHeight: TextUnit = (MockupTokens.metrics.getValue("meta.size") *
        MockupTokens.metrics.getValue("meta.lineHeight")).sp

    /** `.mut` — 13sp body copy. */
    public val mutedSize: TextUnit = size("muted.size")
    public val mutedLineHeight: TextUnit = (MockupTokens.metrics.getValue("muted.size") *
        MockupTokens.metrics.getValue("muted.lineHeight")).sp

    /** `.li .tx b` — 14sp weight 600. */
    public val listItemTitleSize: TextUnit = 14.sp
    public val listItemTitleWeight: Int = 600

    /** `.chip` — 9sp weight 700, +0.05em, uppercase. */
    public val chipSize: TextUnit = size("chip.size")
    public val chipWeight: Int = weight("chip.weight")
    public val chipLetterSpacing: TextUnit = em("chip.size", "chip.letterSpacingEm")

    /** Buttons inherit the 14sp body size at weight 600. */
    public val buttonSize: TextUnit = 14.sp
    public val buttonWeight: Int = weight("button.weight")
    public val buttonSmallSize: TextUnit = 12.sp

    /** Bottom navigation labels: 11sp, bold when selected. */
    public val bottomNavLabelSize: TextUnit = size("bottomNav.labelSize")

    /** Transfer action bar labels: 10sp. */
    public val actionBarLabelSize: TextUnit = size("actionBar.labelSize")

    /** Category tabs: 11sp, weight 500 unselected / 800 selected (v2.4). */
    public val tabLabelSize: TextUnit = size("tab.fontSize")
    public val tabSelectedWeight: Int = weight("tab.selectedWeight")
    public val tabUnselectedWeight: Int = weight("tab.unselectedWeight")

    /** `.tile b` / `.tile span` — stat tiles inside a summary card. */
    public val tileValueSize: TextUnit = size("tile.valueSize")
    public val tileLabelSize: TextUnit = size("tile.labelSize")
    public val tileLabelLetterSpacing: TextUnit = 0.56.sp

    /** Onboarding slide title: 23sp at 1.25 line height. */
    public val onboardingTitleSize: TextUnit = size("onboarding.titleSize")
    public val onboardingTitleLineHeight: TextUnit = 29.sp

    /** Player titles. */
    public val musicTitleSize: TextUnit = size("music.titleSize")
    public val nowPlayingLabelSize: TextUnit = 12.sp
    public val nowPlayingLetterSpacing: TextUnit = 1.44.sp

    /** Dialog title, toast, log rows and the address bar. */
    public val dialogTitleSize: TextUnit = size("dialog.titleSize")
    public val toastSize: TextUnit = size("toast.size")
    public val logRowSize: TextUnit = 10.sp
    public val addressBarSize: TextUnit = size("addressBar.size")
    public val addressBarNestedSize: TextUnit = size("addressBar.nestedSize")
}
