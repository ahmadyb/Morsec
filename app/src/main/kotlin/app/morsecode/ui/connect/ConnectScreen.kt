package app.morsecode.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import android.widget.Toast
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morsecode.R
import app.morsecode.core.design.component.ChipStyle
import app.morsecode.core.design.component.MorseButton
import app.morsecode.core.design.component.MorseButtonVariant
import app.morsecode.core.design.component.MorseCard
import app.morsecode.core.design.component.MorseChip
import app.morsecode.core.design.component.MorseEmptyState
import app.morsecode.core.design.component.MorseIconButton
import app.morsecode.core.design.component.MorseListRow
import app.morsecode.core.design.component.MorseScreenHeader
import app.morsecode.core.design.component.PeerAvatar
import app.morsecode.core.design.component.RadarBlip
import app.morsecode.core.design.component.RadarIndicator
import app.morsecode.core.design.component.SectionHeader
import app.morsecode.core.design.icon.MorseIcons
import app.morsecode.core.design.theme.MorseTextStyles
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.model.FeatureArea
import app.morsecode.core.model.RecentDevice
import app.morsecode.navigation.MorseDestination
import app.morsecode.ui.common.MorseTabScaffold
import app.morsecode.ui.common.rememberFeatureGate
import app.morsecode.ui.transfer.TransferLayout

/**
 * The Connect destination.
 *
 * The radar, the peer list and the recent devices are all driven by real state:
 * while the discovery transports are gated, the radar is static, the peer count
 * reads "no devices nearby" and Send/Receive/Broadcast explain exactly what is
 * missing instead of animating a scan that is not happening.
 */
@Composable
public fun ConnectScreen(
    onNavigate: (MorseDestination) -> Unit,
    onOpenHelp: () -> Unit,
    /**
     * Opens one of the two duplex transfer views.
     *
     * Send and Receive used to be gates: there was nowhere to go, so they said which
     * milestone delivers the engine. The session screens exist now, so these open them and
     * it is the session's own engine work — adding files, running in the background — that
     * says what this build cannot do yet.
     */
    onOpenTransfer: (TransferLayout) -> Unit,
    viewModel: ConnectViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    val gate = rememberFeatureGate()
    val context = LocalContext.current

    MorseTabScaffold(selected = MorseDestination.CONNECT, onNavigate = onNavigate) {
        MorseScreenHeader(
            title = stringResource(R.string.connect_title),
            actions = {
                MorseIconButton(
                    iconRes = MorseIcons.help,
                    contentDescription = stringResource(R.string.connect_help),
                    onClick = onOpenHelp,
                )
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = metrics.screenPaddingHorizontal,
                vertical = metrics.headerPaddingBottom,
            ),
            verticalArrangement = Arrangement.spacedBy(metrics.cardGap),
        ) {
            item(key = "radar") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    RadarIndicator(
                        active = state.discoveryLive && state.scanning,
                        blips = state.peers.mapIndexed { index, _ ->
                            RadarBlip(
                                xFraction = BLIP_POSITIONS[index % BLIP_POSITIONS.size].first,
                                yFraction = BLIP_POSITIONS[index % BLIP_POSITIONS.size].second,
                                delayFraction = index * 0.25f,
                            )
                        },
                        coreContentDescription = stringResource(R.string.cd_radar),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = if (state.discoveryLive) {
                            pluralStringResource(
                                R.plurals.connect_devices_nearby,
                                state.peers.size,
                                state.peers.size,
                            )
                        } else {
                            stringResource(R.string.connect_idle_title)
                        },
                        style = MorseTextStyles.listTitle,
                        color = colors.textPrimary,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = if (state.discoveryLive) {
                            stringResource(R.string.connect_scanning)
                        } else {
                            stringResource(R.string.connect_idle_body)
                        },
                        style = MorseTextStyles.muted,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            item(key = "actions") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MorseButton(
                            text = stringResource(R.string.connect_send),
                            onClick = { onOpenTransfer(TransferLayout.SENDING_FIRST) },
                            modifier = Modifier.weight(1f),
                        )
                        MorseButton(
                            text = stringResource(R.string.connect_receive),
                            onClick = { onOpenTransfer(TransferLayout.RECEIVING_FIRST) },
                            variant = MorseButtonVariant.GHOST,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    MorseButton(
                        text = stringResource(R.string.connect_broadcast),
                        onClick = { gate.run(FeatureArea.SESSIONS_AND_BROADCAST) { } },
                        variant = MorseButtonVariant.WASH,
                        fillWidth = true,
                    )
                }
            }

            item(key = "webshare") {
                MorseCard(onClick = { gate.run(FeatureArea.WEBSHARE_SERVER) { } }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(colors.raised, RoundedCornerShape(metrics.fileIconRadius)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(MorseIcons.pc),
                                contentDescription = null,
                                tint = colors.textSecondary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(
                                text = stringResource(R.string.connect_webshare_title),
                                style = MorseTextStyles.listTitle,
                                color = colors.textPrimary,
                            )
                            Text(
                                text = stringResource(R.string.connect_webshare_meta, state.webSharePort),
                                style = MorseTextStyles.monospacedMeta,
                                color = colors.textTertiary,
                            )
                        }
                        MorseChip(
                            text = stringResource(
                                if (state.webShareRunning) R.string.connect_webshare_on
                                else R.string.connect_webshare_off,
                            ),
                            style = if (state.webShareRunning) ChipStyle.OK else ChipStyle.OUTLINE,
                        )
                    }
                }
            }

            item(key = "recent-header") {
                SectionHeader(
                    text = stringResource(R.string.connect_recent_header),
                    actionLabel = stringResource(R.string.action_clear),
                    onAction = {
                        viewModel.clearRecentDevices()
                        Toast.makeText(
                            context,
                            R.string.connect_recent_cleared,
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            }

            if (state.recentDevices.isEmpty()) {
                item(key = "recent-empty") {
                    MorseEmptyState(
                        title = stringResource(R.string.connect_recent_empty_title),
                        message = stringResource(R.string.connect_recent_empty_body),
                        iconRes = MorseIcons.radar,
                    )
                }
            } else {
                items(state.recentDevices, key = { it.peerId }) { device ->
                    RecentDeviceRow(
                        device = device,
                        subtitle = recentSubtitle(device, viewModel),
                        onClick = { gate.run(FeatureArea.TRANSFER_ENGINE) { } },
                        onForget = { viewModel.forgetDevice(device.peerId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentDeviceRow(
    device: RecentDevice,
    subtitle: String,
    onClick: () -> Unit,
    onForget: () -> Unit,
) {
    MorseListRow(
        title = device.displayName,
        meta = subtitle,
        onClick = onClick,
        leading = {
            PeerAvatar(letter = device.letter, colorSeed = device.peerId)
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MorseChip(text = device.transport.badge, style = ChipStyle.OUTLINE)
                MorseIconButton(
                    iconRes = MorseIcons.close,
                    contentDescription = stringResource(R.string.connect_recent_cleared),
                    onClick = onForget,
                    size = 40.dp,
                    glyph = 16.dp,
                )
            }
        },
    )
}

private fun recentSubtitle(device: RecentDevice, viewModel: ConnectViewModel): String =
    listOfNotNull(
        if (device.lastSeenEpochMillis > 0L) viewModel.relativeTime(device.lastSeenEpochMillis) else null,
        device.lastSummary,
        device.detail.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

/** Blip placements copied from the reference's three `<span class="blip">` positions. */
private val BLIP_POSITIONS = listOf(
    0.22f to 0.32f,
    0.70f to 0.26f,
    0.58f to 0.70f,
)
