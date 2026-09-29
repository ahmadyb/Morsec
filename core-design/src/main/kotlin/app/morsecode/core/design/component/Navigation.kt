package app.morsecode.core.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.morsecode.core.design.theme.MorseTextStyles
import app.morsecode.core.design.theme.MorseTheme

/** One destination in the bottom navigation bar. */
public data class BottomNavDestination(
    val id: String,
    val label: String,
    val iconRes: Int,
    val contentDescription: String? = null,
)

/**
 * The `.bn` bottom navigation: 72 dp tall, four equal destinations, each with a
 * pill indicator behind a 21 dp glyph and an 11 sp label that turns bold when
 * selected. Built by hand rather than from `NavigationBar` because the reference
 * specifies the pill's 64 dp minimum width and the exact selected colours.
 */
@Composable
public fun MorseBottomBar(
    destinations: List<BottomNavDestination>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(metrics.bottomNavHeight)
            .background(colors.card)
            .padding(
                start = metrics.bottomNavPaddingHorizontal,
                top = metrics.bottomNavPaddingTop,
                end = metrics.bottomNavPaddingHorizontal,
                bottom = metrics.bottomNavPaddingBottom,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        destinations.forEach { destination ->
            BottomNavItem(
                destination = destination,
                selected = destination.id == selectedId,
                onClick = { onSelect(destination.id) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RowScope.BottomNavItem(
    destination: BottomNavDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressedScale = MorseTheme.motion.pressedScale

    Column(
        modifier = modifier
            .defaultMinSize(minWidth = metrics.bottomNavItemMinWidth, minHeight = 48.dp)
            .graphicsLayer {
                val scale = if (pressed) pressedScale else 1f
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(metrics.radiusMd))
            .background(if (pressed) colors.pressed else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .semantics { this.selected = selected }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            modifier = Modifier
                .defaultMinSize(minWidth = metrics.bottomNavPillMinWidth)
                .clip(RoundedCornerShape(percent = 50))
                .background(if (selected) colors.primaryContainer else Color.Transparent)
                .padding(
                    horizontal = metrics.bottomNavPillPaddingHorizontal,
                    vertical = metrics.bottomNavPillPaddingVertical,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = destination.iconRes),
                contentDescription = destination.contentDescription,
                tint = if (selected) colors.accent else colors.textSecondary,
                modifier = Modifier.size(metrics.bottomNavGlyph),
            )
        }
        Text(
            text = destination.label,
            style = if (selected) MorseTextStyles.navLabelSelected else MorseTextStyles.navLabel,
            color = if (selected) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** One action in the transfer action bar. */
public data class TransferAction(
    val id: String,
    val label: String,
    val iconRes: Int,
    val contentDescription: String? = null,
    val primary: Boolean = false,
    val danger: Boolean = false,
    val enabled: Boolean = true,
)

/**
 * The `.ab` transfer action bar: Add files, Pause all / Resume all, Background
 * and End. It is the single place the global pause lives — the reference forbids
 * duplicating it above and below the file list (§5 Transfers).
 */
@Composable
public fun TransferActionBar(
    actions: List<TransferAction>,
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = metrics.actionBarMinHeight)
                .background(colors.surfaceContainerLow)
                .padding(horizontal = metrics.actionBarPaddingHorizontal, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions.forEach { action ->
                TransferActionButton(
                    action = action,
                    onAction = onAction,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // .ab{border-top:1px solid var(--line)}
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.line),
        )
    }
}

@Composable
private fun RowScope.TransferActionButton(
    action: TransferAction,
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressedScale = MorseTheme.motion.pressedScale
    val tint = when {
        !action.enabled -> colors.textTertiary
        action.danger -> colors.error
        action.primary -> colors.accent
        else -> colors.textSecondary
    }

    Column(
        modifier = modifier
            .defaultMinSize(minHeight = 58.dp)
            .graphicsLayer {
                val scale = if (pressed) pressedScale else 1f
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(14.dp))
            .background(if (pressed) colors.pressed else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = action.enabled,
                role = Role.Button,
                onClick = { onAction(action.id) },
            )
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(if (action.primary) colors.actionBarPrimaryPill else Color.Transparent)
                .padding(horizontal = 14.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = action.iconRes),
                contentDescription = action.contentDescription,
                tint = tint,
                modifier = Modifier.size(metrics.actionBarGlyph),
            )
        }
        Text(
            text = action.label,
            style = MorseTextStyles.actionBarLabel,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** One Files destination category tab. */
public data class CategoryTab(val id: String, val label: String)

/**
 * The `.files-tabs` category strip (v2.4):
 *
 *  - all five tabs always fit the phone width (equal weights, no scrolling and
 *    therefore no horizontal scrollbar);
 *  - flat background with a 1 dp bottom rule;
 *  - the selected tab uses bold accent text, an 8% accent tint and a straight
 *    3 dp accent underline inset 12% at each side.
 *
 * The strip is 48 dp tall rather than the reference's ~34 dp so category
 * switching meets the 48 dp touch target floor (§11); tint, indicator and type
 * are unchanged. Recorded in doc/fidelity-notes.md.
 */
@Composable
public fun CategoryTabRow(
    tabs: List<CategoryTab>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    val insetFraction = metrics.tabIndicatorInsetFraction
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.stickyHeaderScrim),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                val selected = tab.id == selectedId
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                when {
                                    selected -> colors.fileTabSelected
                                    pressed -> colors.pressed
                                    else -> Color.Transparent
                                },
                            )
                            .clickable(
                                interactionSource = interaction,
                                indication = null,
                                role = Role.Tab,
                                onClick = { onSelect(tab.id) },
                            )
                            .semantics { this.selected = selected }
                            .padding(horizontal = metrics.tabPaddingHorizontal),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = tab.label,
                            style = if (selected) MorseTextStyles.tabLabelSelected else MorseTextStyles.tabLabel,
                            color = if (selected) colors.accent else colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (selected) {
                        // .files-tabs button.on::after — straight 3 dp accent bar,
                        // inset 12% at each side, sitting on the bottom rule.
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth(1f - 2 * insetFraction)
                                .height(metrics.tabIndicatorHeight)
                                .background(colors.accent),
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.line),
        )
    }
}
