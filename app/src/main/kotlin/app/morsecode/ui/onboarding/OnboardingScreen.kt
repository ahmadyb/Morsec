package app.morsecode.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morsecode.R
import app.morsecode.core.design.component.MorseButton
import app.morsecode.core.design.component.MorseLink
import app.morsecode.core.design.component.MorseScreen
import app.morsecode.core.design.icon.MorseIcons
import app.morsecode.core.design.theme.MorseTextStyles
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.storage.permissions.PermissionMatrix

/** One card of the tour: badge, icon and the reference's exact copy. */
private data class OnboardingSlide(
    @DrawableRes val iconRes: Int,
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
    @StringRes val primaryRes: Int,
    @StringRes val secondaryRes: Int,
    /** Card 2 is the only one that asks for a permission, and only when tapped. */
    val requestsStoragePermission: Boolean = false,
)

private val slides = listOf(
    OnboardingSlide(
        iconRes = MorseIcons.radar,
        titleRes = R.string.onb1_title,
        bodyRes = R.string.onb1_body,
        primaryRes = R.string.onb1_primary,
        secondaryRes = R.string.onb1_secondary,
    ),
    OnboardingSlide(
        iconRes = MorseIcons.lock,
        titleRes = R.string.onb2_title,
        bodyRes = R.string.onb2_body,
        primaryRes = R.string.onb2_primary,
        secondaryRes = R.string.onb2_secondary,
        requestsStoragePermission = true,
    ),
    OnboardingSlide(
        iconRes = MorseIcons.globe,
        titleRes = R.string.onb3_title,
        bodyRes = R.string.onb3_body,
        primaryRes = R.string.onb3_primary,
        secondaryRes = R.string.onb3_secondary,
    ),
    OnboardingSlide(
        iconRes = MorseIcons.check,
        titleRes = R.string.onb4_title,
        bodyRes = R.string.onb4_body,
        primaryRes = R.string.onb4_primary,
        secondaryRes = R.string.onb4_secondary,
    ),
)

/**
 * The four-card tour (`.onb` in the reference).
 *
 * Card 2's primary button issues the real contextual storage request — the only
 * permission prompt the tour shows — and the last card persists completion so a
 * restart goes straight to Connect.
 */
@Composable
public fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        viewModel.onPermissionsResult(result)
        viewModel.next()
    }

    val slide = slides[state.slide.coerceIn(0, slides.lastIndex)]
    val gradient = colors.onboardingBadgeGradients[state.slide.coerceIn(0, slides.lastIndex)]

    MorseScreen {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = metrics.onboardingPaddingHorizontal),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(metrics.onboardingBadge)
                    .shadow(
                        elevation = metrics.onboardingBadgeElevation,
                        shape = RoundedCornerShape(metrics.onboardingBadgeRadius),
                        spotColor = gradient.first,
                        ambientColor = gradient.first,
                    )
                    .background(
                        brush = Brush.linearGradient(listOf(gradient.first, gradient.second)),
                        shape = RoundedCornerShape(metrics.onboardingBadgeRadius),
                    )
                    .semantics {
                        contentDescription = context.getString(slide.titleRes)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(slide.iconRes),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(metrics.onboardingGlyph),
                )
            }

            Spacer(Modifier.height(metrics.onboardingBadgeGap))

            Text(
                text = stringResource(slide.titleRes),
                style = MorseTextStyles.onboardingTitle,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(metrics.onboardingTitleGap))

            Text(
                text = stringResource(slide.bodyRes),
                style = MorseTextStyles.muted,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(metrics.onboardingBodyGap))

            Row(horizontalArrangement = Arrangement.spacedBy(metrics.onboardingDotGap)) {
                repeat(slides.size) { index ->
                    val active = index == state.slide
                    Box(
                        modifier = Modifier
                            .width(if (active) metrics.onboardingDotActiveWidth else metrics.onboardingDotSize)
                            .height(metrics.onboardingDotSize)
                            .background(
                                color = if (active) colors.accent else colors.pressed,
                                shape = RoundedCornerShape(percent = PILL_PERCENT),
                            ),
                    )
                }
            }

            Spacer(Modifier.height(metrics.onboardingDotsGap))

            MorseButton(
                text = stringResource(slide.primaryRes),
                onClick = {
                    when {
                        slide.requestsStoragePermission -> permissionLauncher.launch(
                            (PermissionMatrix.mediaRead() + PermissionMatrix.mediaWrite()).toTypedArray(),
                        )

                        state.isLast -> {
                            viewModel.complete()
                            onFinished()
                        }

                        else -> viewModel.next()
                    }
                },
                fillWidth = true,
            )

            Spacer(Modifier.height(metrics.onboardingLinkGap))

            MorseLink(
                text = stringResource(slide.secondaryRes),
                onClick = {
                    when {
                        // Card 2's secondary declines the request and moves on.
                        slide.requestsStoragePermission -> viewModel.next()
                        // Card 4's secondary restarts the tour.
                        state.isLast -> viewModel.replay()
                        else -> {
                            viewModel.complete()
                            onFinished()
                        }
                    }
                },
            )
        }
    }
}

private const val PILL_PERCENT = 50
