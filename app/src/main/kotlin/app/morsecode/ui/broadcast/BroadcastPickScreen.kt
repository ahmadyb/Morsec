package app.morsecode.ui.broadcast

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morsecode.R
import app.morsecode.core.design.component.MorseButton
import app.morsecode.core.design.component.MorseCard
import app.morsecode.core.design.component.MorseEmptyState
import app.morsecode.core.design.component.MorseListRow
import app.morsecode.core.design.component.PeerAvatar
import app.morsecode.core.design.component.RadarBlip
import app.morsecode.core.design.component.RadarIndicator
import app.morsecode.core.design.component.SectionHeader
import app.morsecode.core.design.icon.MorseIcons
import app.morsecode.core.design.theme.MorseTextStyles
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.model.FeatureArea
import app.morsecode.core.model.Peer
import app.morsecode.navigation.MorseDestination
import app.morsecode.ui.common.MorseTabScaffold
import app.morsecode.ui.common.rememberFeatureGate
import app.morsecode.ui.transfer.ScreenPadding
import app.morsecode.ui.transfer.TransferScreenHeader

/**
 * The multi-device picker for a broadcast (master prompt §5 "Connect").
 *
 * The reference's `SC.discover` with `S.multi` set: the same radar, the same transport and
 * queue cards, the same Discovered list — with the rows made selectable and the connect button
 * made a broadcast.
 *
 * Three things are deliberately not like the reference:
 *
 * - **The laptop is not offered.** The reference lists whatever discovery found and lets the
 *   user tick it; a broadcast goes to phones that run the app, so a WebShare laptop is
 *   filtered out by the model rather than greyed out here. There is no manual address entry and
 *   no pairing code, as the product requires.
 * - **The radar says what is true.** Discovery is gated until milestones 6 and 7, so the sweep
 *   does not run and the copy says the list is the reference's sample set rather than claiming
 *   a search happened.
 * - **Refresh is a real gate.** Rescanning needs a transport; pressing Refresh therefore opens
 *   the app's own dialog naming the milestone that delivers it instead of printing a message
 *   about a scan that is not running.
 */
@Composable
public fun BroadcastPickScreen(
    onBack: () -> Unit,
    onNavigate: (MorseDestination) -> Unit,
    onBroadcastTo: (String) -> Unit,
    viewModel: BroadcastPickerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gate = rememberFeatureGate()
    val context = LocalContext.current

    // Resolved in composition: a click lambda must not read resources through LocalContext.
    val started = pluralStringResource(
        R.plurals.broadcast_started,
        state.selectedCount,
        state.selectedCount,
    )

    BroadcastPickContent(
        state = state,
        onBack = onBack,
        onNavigate = onNavigate,
        onToggle = viewModel::toggle,
        onRefresh = { gate.run(FeatureArea.LAN_TRANSPORT) { } },
        onBroadcast = {
            Toast.makeText(context, started, Toast.LENGTH_SHORT).show()
            onBroadcastTo(viewModel.chosenToken())
        },
    )
}

/** The picker as a function of the state it is handed, so a test can hand it any selection. */
@Composable
internal fun BroadcastPickContent(
    state: BroadcastPickUiState,
    onBack: () -> Unit,
    onNavigate: (MorseDestination) -> Unit,
    onToggle: (String) -> Unit,
    onRefresh: () -> Unit,
    onBroadcast: () -> Unit,
) {
    val colors = MorseTheme.colors

    MorseTabScaffold(selected = MorseDestination.FILES, onNavigate = onNavigate) {
        TransferScreenHeader(
            title = stringResource(R.string.broadcast_title),
            backDescription = stringResource(R.string.action_back),
            onBack = onBack,
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).testTag(BroadcastListTag),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            item(key = "search") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    RadarIndicator(
                        active = state.discoveryLive,
                        blips = state.peers.mapIndexed { index, _ ->
                            val position = BLIP_POSITIONS[index % BLIP_POSITIONS.size]
                            RadarBlip(
                                xFraction = position.first,
                                yFraction = position.second,
                                delayFraction = index * 0.25f,
                            )
                        },
                        coreContentDescription = stringResource(R.string.cd_radar),
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = if (state.discoveryLive) {
                            stringResource(R.string.broadcast_pick_searching)
                        } else {
                            stringResource(R.string.broadcast_pick_sample_title)
                        },
                        style = MorseTextStyles.listTitle,
                        color = colors.textPrimary,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = if (state.discoveryLive) {
                            stringResource(R.string.broadcast_pick_searching_body)
                        } else {
                            stringResource(R.string.broadcast_pick_sample_body)
                        },
                        style = MorseTextStyles.muted,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp, start = ScreenPadding, end = ScreenPadding),
                    )
                }
            }

            item(key = "transport") {
                PickFactRow(
                    label = stringResource(R.string.broadcast_transport_label),
                    value = stringResource(R.string.broadcast_transport_value),
                    accent = true,
                )
            }

            item(key = "queue") {
                PickFactRow(
                    label = stringResource(R.string.broadcast_queue_label),
                    value = pluralStringResource(
                        R.plurals.broadcast_queue_files,
                        state.batchFileCount,
                        state.batchFileCount,
                        state.batchBytes,
                    ),
                )
            }

            // The count the master prompt asks for, and the place the two-phone floor is
            // explained: a disabled button with no reason on screen is a dead end.
            item(key = "selected") {
                PickFactRow(
                    label = stringResource(R.string.broadcast_selected_label),
                    value = if (state.selectedCount == 0) {
                        stringResource(R.string.broadcast_selected_none)
                    } else {
                        pluralStringResource(
                            R.plurals.broadcast_selected_phones,
                            state.selectedCount,
                            state.selectedCount,
                        )
                    },
                )
            }

            item(key = "discovered-header") {
                SectionHeader(
                    text = stringResource(R.string.broadcast_discovered),
                    actionLabel = stringResource(R.string.broadcast_refresh),
                    onAction = onRefresh,
                    modifier = Modifier.padding(horizontal = ScreenPadding),
                )
            }

            if (state.hasDevices) {
                items(state.peers, key = { it.peerId }) { peer ->
                    BroadcastPeerRow(
                        peer = peer,
                        selected = state.selection.isSelected(peer.peerId),
                        onToggle = { onToggle(peer.peerId) },
                    )
                }
            } else {
                item(key = "no-devices") {
                    MorseEmptyState(
                        title = stringResource(R.string.broadcast_no_devices),
                        message = stringResource(R.string.broadcast_no_devices_body),
                        iconRes = MorseIcons.radar,
                    )
                }
            }

            item(key = "start") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = ScreenPadding, end = ScreenPadding, top = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MorseButton(
                        text = stringResource(R.string.broadcast_start, state.selectedCount),
                        onClick = onBroadcast,
                        enabled = state.canStart,
                        fillWidth = true,
                    )
                    Text(
                        text = if (state.canStart) {
                            stringResource(R.string.broadcast_pick_hint)
                        } else {
                            pluralStringResource(
                                R.plurals.broadcast_pick_more,
                                state.shortfall,
                                state.shortfall,
                            )
                        },
                        style = MorseTextStyles.meta,
                        color = colors.textTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * One label-and-value card, as the reference's TRANSPORT and QUEUE rows are drawn.
 *
 * A card rather than a plain row because these facts describe the whole batch rather than one
 * device in the list below them, which is exactly what the reference's two cards are for.
 */
@Composable
private fun PickFactRow(
    label: String,
    value: String,
    accent: Boolean = false,
) {
    val colors = MorseTheme.colors
    MorseCard(
        modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp),
        padding = CARD_PADDING,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MorseTextStyles.sectionHeader,
                color = colors.textTertiary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = value,
                style = MorseTextStyles.monospacedMeta,
                color = if (accent) colors.accent else colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One discovered phone, with its own selected state.
 *
 * The whole row is the control — one selectable node, so a screen reader announces the phone
 * once and says whether it is ticked — and the circle at the end is the reference's `.rad`
 * drawn for the eye rather than for the accessibility tree. The reference's own list decides
 * what the tick means; here it decides who the batch fans out to.
 */
@Composable
private fun BroadcastPeerRow(
    peer: Peer,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val colors = MorseTheme.colors
    MorseListRow(
        leading = { PeerAvatar(letter = peer.letter, colorSeed = peer.colorSeed) },
        title = peer.displayName,
        meta = peer.detail,
        modifier = Modifier
            .selectable(
                selected = selected,
                role = Role.Checkbox,
                onClick = onToggle,
            ),
        selected = selected,
        trailing = { SelectionCircle(selected = selected) },
        divider = true,
    )
}

/**
 * The reference's `.rad`, drawn: an 18 dp ring that fills and takes a tick when chosen.
 *
 * The design system has a radio button for single-choice lists; a broadcast addresses many
 * phones at once, so the row carries the selectable semantics itself and this stays a shape.
 */
@Composable
private fun SelectionCircle(selected: Boolean) {
    val colors = MorseTheme.colors
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(if (selected) colors.accent else Color.Transparent)
            .border(1.5.dp, if (selected) colors.accent else colors.textTertiary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                painter = painterResource(id = MorseIcons.check),
                contentDescription = null,
                tint = colors.onAccent,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/** Where the reference puts three blips on its radar. */
private val BLIP_POSITIONS = listOf(
    0.22f to 0.32f,
    0.70f to 0.26f,
    0.58f to 0.70f,
)

/** `.card{padding:11px 14px}` for the picker's own fact rows. */
private val CARD_PADDING = 14.dp
