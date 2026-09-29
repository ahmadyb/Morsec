package app.morsecode.core.design.tokens

import androidx.compose.ui.graphics.Color
import app.morsecode.core.model.Accent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs on the JVM (Compose `Color` is pure math) and pins the resolved palette
 * to the mockup. tools/verify independently re-derives the same values from the
 * reference document's CSS, so a drift fails in two places.
 */
/**
 * Compose packs an sRGB colour derived from a hex literal into 8 bits per
 * channel, so a 0.42 wash reads back as 107/255 = 0.4196078. One quantisation
 * step is the honest tolerance for an alpha assertion.
 */
private const val ALPHA_STEP = 1f / 255f

class ColorTokensTest {

    private val dark = morseColorTokens(darkTheme = true, accent = Accent.SUNFLOWER)
    private val light = morseColorTokens(darkTheme = false, accent = Accent.SUNFLOWER)

    @Test
    fun `dark surfaces match the material 3 override layer`() {
        assertEquals(Color(0xFF10100D), dark.background)
        assertEquals(Color(0xFF1B1B17), dark.card)
        assertEquals(Color(0xFF26251F), dark.raised)
        assertEquals(Color(0xFF302F28), dark.pressed)
        assertEquals(Color(0xFF33322B), dark.line)
        assertEquals(Color(0xFFE8E5DB), dark.textPrimary)
        assertEquals(Color(0xFFCAC6BA), dark.textSecondary)
        assertEquals(Color(0xFF918F86), dark.textTertiary)
        assertTrue(dark.isDark)
    }

    @Test
    fun `light surfaces match the material 3 override layer`() {
        assertEquals(Color(0xFFFFFBF2), light.background)
        assertEquals(Color(0xFFF7F2E8), light.card)
        assertEquals(Color(0xFFEEE9DF), light.raised)
        assertEquals(Color(0xFFE7E1D5), light.pressed)
        assertEquals(Color(0xFFD9D3C7), light.line)
        assertEquals(Color(0xFF201E18), light.textPrimary)
        assertEquals(Color(0xFF555148), light.textSecondary)
        assertEquals(Color(0xFF777269), light.textTertiary)
        assertTrue(!light.isDark)
    }

    @Test
    fun `status colours are theme independent`() {
        for (tokens in listOf(dark, light)) {
            assertEquals(Color(0xFF22C55E), tokens.ok)
            assertEquals(Color(0xFFF59E0B), tokens.warn)
            assertEquals(Color(0xFFEF4444), tokens.error)
            assertEquals(Color(0xFF0EA5E9), tokens.info)
            assertEquals(Color(0xFFA78BFA), tokens.receive)
            assertEquals(Color(0xFF412D6D), tokens.receiveBackground)
            assertEquals(Color(0xFF84CC16), tokens.lime)
        }
    }

    @Test
    fun `primary container is a twenty percent accent wash over the card`() {
        val expected = mixSolid(Color(0xFFFACC15), Color(0xFF1B1B17), 0.20f)
        assertEquals(expected, dark.primaryContainer)
        // The mix must sit between the two inputs on every channel.
        assertTrue(dark.primaryContainer.red in Color(0xFF1B1B17).red..Color(0xFFFACC15).red)
        assertTrue(dark.primaryContainer.green in Color(0xFF1B1B17).green..Color(0xFFFACC15).green)
    }

    @Test
    fun `alpha washes keep the source colour and only change alpha`() {
        assertEquals(0.42f, dark.outline.alpha, ALPHA_STEP)
        assertEquals(dark.textSecondary.red, dark.outline.red, 0.0001f)
        assertEquals(0.18f, dark.chipAccentBackground.alpha, ALPHA_STEP)
        assertEquals(dark.accent.red, dark.chipAccentBackground.red, 0.0001f)
        assertEquals(0.08f, dark.fileTabSelected.alpha, ALPHA_STEP)
        assertEquals(0.62f, dark.scrim.alpha, ALPHA_STEP)
        // The light theme scrim uses the reference's rgba(20,19,16,.38).
        assertEquals(0.38f, light.scrim.alpha, ALPHA_STEP)
        assertEquals(Color(0xFF141310).red, light.scrim.red, 0.0001f)
    }

    @Test
    fun `every accent resolves its own ink colour`() {
        assertEquals(Color(0xFFFACC15), morseColorTokens(true, Accent.SUNFLOWER).accent)
        assertEquals(Color(0xFF84CC16), morseColorTokens(true, Accent.LEAF).accent)
        assertEquals(Color(0xFFEA580C), morseColorTokens(true, Accent.EMBER).accent)
        assertEquals(Color(0xFF8B5CF6), morseColorTokens(true, Accent.VIOLET).accent)
        assertEquals(Color(0xFF0EA5E9), morseColorTokens(true, Accent.SKY).accent)

        assertEquals(Color(0xFF1A1400), morseColorTokens(true, Accent.SUNFLOWER).onAccent)
        assertEquals(Color.White, morseColorTokens(true, Accent.EMBER).onAccent)
        assertEquals(Color.White, morseColorTokens(true, Accent.VIOLET).onAccent)
    }

    @Test
    fun `progress bar paints the accent to lime gradient`() {
        assertEquals(Color(0xFFFACC15), dark.progressStart)
        assertEquals(Color(0xFF84CC16), dark.progressEnd)
        assertEquals(dark.pressed, dark.progressTrack)
    }

    @Test
    fun `immersive player surfaces are true black`() {
        assertEquals(Color.Black, dark.viewerBackdrop)
        assertEquals(Color(0xFF1A1A1A), dark.viewerIconBackground)
        assertEquals(Color(0xFFDDDDDD), dark.viewerIconContent)
        assertEquals(Color(0xFF0B0B0B), dark.videoControlBar)
    }

    @Test
    fun `material roles are wired to the token set`() {
        val scheme = dark.materialColorScheme()
        assertEquals(dark.accent, scheme.primary)
        assertEquals(dark.onAccent, scheme.onPrimary)
        assertEquals(dark.primaryContainer, scheme.primaryContainer)
        assertEquals(dark.background, scheme.background)
        assertEquals(dark.textPrimary, scheme.onBackground)
        assertEquals(dark.card, scheme.surfaceContainer)
        assertEquals(dark.raised, scheme.surfaceContainerHigh)
        assertEquals(dark.surfaceContainerLow, scheme.surfaceContainerLow)
        assertEquals(dark.error, scheme.error)

        val lightScheme = light.materialColorScheme()
        assertEquals(light.background, lightScheme.surface)
    }

    @Test
    fun `file kind washes use twenty two percent of the kind colour`() {
        val wash = dark.kindWash(app.morsecode.core.model.MediaKind.IMAGE)
        assertEquals(0.22f, wash.alpha, ALPHA_STEP)
        assertEquals(Color(0xFFF59E0B).red, wash.red, 0.0001f)
        assertEquals(Color(0xFFF59E0B), dark.kindColour(app.morsecode.core.model.MediaKind.IMAGE))
        assertEquals(dark.raised, dark.kindWash(app.morsecode.core.model.MediaKind.FOLDER))
        assertEquals(dark.accent, dark.kindForeground(app.morsecode.core.model.MediaKind.FOLDER))
    }

    @Test
    fun `onboarding badge gradients match the reference slides`() {
        val gradients = dark.onboardingBadgeGradients
        assertEquals(4, gradients.size)
        assertEquals(Color(0xFFF59E0B) to Color(0xFFEA580C), gradients[0])
        assertEquals(Color(0xFF22C55E) to Color(0xFF16A34A), gradients[1])
        assertEquals(Color(0xFF0EA5E9) to Color(0xFF0284C7), gradients[2])
        assertEquals(Color(0xFF8B5CF6) to Color(0xFF7C3AED), gradients[3])
        // Slide badges are theme independent.
        assertEquals(gradients, light.onboardingBadgeGradients)
    }

    @Test
    fun `metrics resolve from the mockup tables`() {
        val metrics = MorseMetrics.default
        assertEquals(72f, metrics.bottomNavHeight.value, 0.001f)
        assertEquals(56f, metrics.headerMinHeight.value, 0.001f)
        assertEquals(0.82f, metrics.sheetMaxHeightFraction, 0.0001f)
        assertEquals(16f, metrics.tileRadius.value, 0.001f)
        assertEquals(12f, metrics.tilePadding.value, 0.001f)
        assertEquals(7f, metrics.tilePaddingHorizontal.value, 0.001f)
        assertEquals(19f, MorseType.tileValueSize.value, 0.001f)
        assertEquals(8f, MorseType.tileLabelSize.value, 0.001f)
        assertEquals(24f, metrics.statCardRadius.value, 0.001f)
        assertEquals(30f, MorseType.statCardValueSize.value, 0.001f)
        assertEquals(68f, metrics.actionBarMinHeight.value, 0.001f)
        assertEquals(56f, metrics.listItemMinHeight.value, 0.001f)
        assertEquals(40f, metrics.iconButtonSize.value, 0.001f)
        assertEquals(48f, metrics.touchTarget.value, 0.001f)
        assertEquals(44f, metrics.buttonMinHeight.value, 0.001f)
        assertEquals(36f, metrics.buttonSmallMinHeight.value, 0.001f)
        assertEquals(52f, metrics.switchWidth.value, 0.001f)
        assertEquals(32f, metrics.switchHeight.value, 0.001f)
        assertEquals(156f, metrics.radarSize.value, 0.001f)
        assertEquals(6f, metrics.gridGapMedia.value, 0.001f)
        assertEquals(12f, metrics.gridGapApps.value, 0.001f)
        assertEquals(3, metrics.gridColumnsAtReferenceWidth)
        assertEquals(360f, metrics.gridReferenceWidth.value, 0.001f)
        assertEquals(2f, metrics.scrollbarThickness.value, 0.001f)
        assertEquals(0.12f, metrics.tabIndicatorInsetFraction, 0.0001f)
        assertEquals(3f, metrics.tabIndicatorHeight.value, 0.001f)
        assertEquals(16f, metrics.radiusMd.value, 0.001f)
        assertEquals(28f, metrics.radiusXl.value, 0.001f)
        assertEquals(999f, metrics.radiusPill.value, 0.001f)
    }
}
