package app.morsecode.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morsecode.R
import app.morsecode.core.design.component.MorseButton
import app.morsecode.core.design.component.MorseButtonVariant
import app.morsecode.core.design.component.MorseDialog
import app.morsecode.core.design.component.MorseIconButton
import app.morsecode.core.design.component.MorseListRow
import app.morsecode.core.design.component.MorseScreenHeader
import app.morsecode.core.design.component.MorseSwitch
import app.morsecode.core.design.component.PeerAvatar
import app.morsecode.core.design.component.SectionHeader
import app.morsecode.core.design.icon.MorseIcons
import app.morsecode.core.design.theme.MorseTextStyles
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.model.Accent
import app.morsecode.core.model.ThemeMode
import app.morsecode.navigation.MorseDestination
import app.morsecode.ui.common.MorseTabScaffold

/**
 * The Settings destination, row for row as the reference lays it out: device
 * card, accent swatches, appearance switches, transfer policy, system entries
 * and diagnostics. Every control writes to DataStore immediately — nothing here
 * is a preview of a setting that does not exist.
 */
@Composable
public fun SettingsScreen(
    onNavigate: (MorseDestination) -> Unit,
    onOpenLogs: () -> Unit,
    onOpenCrashes: () -> Unit,
    onOpenDoctor: () -> Unit,
    onOpenHelp: () -> Unit,
    onReplayOnboarding: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    val context = LocalContext.current

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let { viewModel.addFolder(it.toString()) } }

    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { viewModel.refreshBatteryState() }

    val prefs = state.settings
    // Fallback device name resolved in composition, not via LocalContext
    // (lint: LocalContextGetResourceValueCall).
    val appName = stringResource(R.string.app_name)
    val deviceName = prefs.deviceName?.takeIf { it.isNotBlank() } ?: appName

    MorseTabScaffold(selected = MorseDestination.SETTINGS, onNavigate = onNavigate) {
        MorseScreenHeader(title = stringResource(R.string.settings_title))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = metrics.screenPaddingHorizontal),
        ) {
            // Device card ---------------------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.primaryContainer, RoundedCornerShape(metrics.radiusMd))
                    .clickable { viewModel.openRename(prefs.deviceName) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PeerAvatar(
                    letter = deviceName.first().uppercaseChar(),
                    colorSeed = deviceName,
                    large = true,
                    backgroundOverride = colors.accent,
                    contentColorOverride = colors.onAccent,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = deviceName,
                        style = MorseTextStyles.listTitle,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = stringResource(
                            R.string.settings_device_subtitle,
                            stringResource(accentNameRes(prefs.accent)),
                        ),
                        style = MorseTextStyles.meta,
                        color = colors.textSecondary,
                    )
                }
            }

            // Accent swatches ------------------------------------------------
            SectionHeader(text = stringResource(R.string.settings_section_accent))
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Accent.ordered.forEach { accent ->
                    val selected = accent == prefs.accent
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .then(
                                if (selected) {
                                    Modifier.border(2.dp, colors.textPrimary, CircleShape)
                                } else {
                                    Modifier
                                },
                            )
                            .background(Color(accent.accentArgb), CircleShape)
                            .clickable { viewModel.setAccent(accent) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Icon(
                                painter = painterResource(MorseIcons.check),
                                contentDescription = stringResource(
                                    R.string.accent_selected,
                                    stringResource(accentNameRes(accent)),
                                ),
                                tint = Color(accent.onAccentArgb),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            // Appearance ------------------------------------------------------
            SectionHeader(text = stringResource(R.string.settings_section_appearance))
            SettingsRow(
                iconRes = MorseIcons.chevD,
                title = stringResource(R.string.settings_dark_mode),
                subtitle = stringResource(R.string.settings_dark_mode_sub),
                trailing = {
                    MorseSwitch(
                        checked = prefs.themeMode == ThemeMode.DARK,
                        onCheckedChange = viewModel::setDarkMode,
                        stateLabel = stringResource(R.string.settings_dark_mode),
                    )
                },
            )
            SettingsRow(
                iconRes = MorseIcons.refresh,
                title = stringResource(R.string.settings_follow_system),
                subtitle = stringResource(R.string.settings_follow_system_sub),
                trailing = {
                    MorseSwitch(
                        checked = prefs.themeMode == ThemeMode.FOLLOW_SYSTEM,
                        onCheckedChange = viewModel::setFollowSystem,
                        stateLabel = stringResource(R.string.settings_follow_system),
                    )
                },
            )
            SettingsRow(
                iconRes = MorseIcons.note,
                title = stringResource(R.string.settings_sounds),
                subtitle = stringResource(R.string.settings_sounds_sub),
                trailing = {
                    MorseSwitch(
                        checked = prefs.soundsEnabled,
                        onCheckedChange = viewModel::setSounds,
                        stateLabel = stringResource(R.string.settings_sounds),
                    )
                },
            )

            // Transfer --------------------------------------------------------
            SectionHeader(text = stringResource(R.string.settings_section_transfer))
            SettingsRow(
                iconRes = MorseIcons.bolt,
                title = stringResource(R.string.settings_conflict),
                subtitle = stringResource(viewModel.policyLabel(prefs.duplicatePolicy)),
                chevron = true,
                onClick = viewModel::cycleDuplicatePolicy,
            )
            SettingsRow(
                iconRes = MorseIcons.info,
                title = stringResource(R.string.settings_notifications),
                subtitle = stringResource(R.string.settings_notifications_sub),
                trailing = {
                    MorseSwitch(
                        checked = prefs.notificationsEnabled,
                        onCheckedChange = viewModel::setNotifications,
                        stateLabel = stringResource(R.string.settings_notifications),
                    )
                },
            )
            SettingsRow(
                iconRes = MorseIcons.target,
                title = stringResource(R.string.settings_broadcast_peers),
                subtitle = stringResource(R.string.settings_broadcast_peers_sub, prefs.broadcastPeerLimit),
                chevron = true,
                onClick = viewModel::cycleBroadcastLimit,
            )

            // System ----------------------------------------------------------
            SectionHeader(text = stringResource(R.string.settings_section_system))
            SettingsRow(
                iconRes = MorseIcons.folder,
                title = stringResource(R.string.settings_storage_access),
                subtitle = if (state.grants.isEmpty()) {
                    stringResource(R.string.settings_no_folders)
                } else {
                    stringResource(
                        R.string.settings_granted_folders,
                        state.grants.joinToString(", ") { it.displayName },
                    )
                },
                chevron = true,
                onClick = { folderLauncher.launch(null) },
            )
            SettingsRow(
                iconRes = MorseIcons.battery,
                title = stringResource(R.string.settings_battery),
                subtitle = stringResource(
                    if (state.batteryExempt) R.string.settings_battery_sub_exempt
                    else R.string.settings_battery_sub_not_exempt,
                ),
                chevron = true,
                onClick = { batteryLauncher.launch(viewModel.batterySettingsIntent()) },
            )
            SettingsRow(
                iconRes = MorseIcons.list,
                title = stringResource(R.string.settings_logs),
                subtitle = stringResource(R.string.settings_logs_sub),
                chevron = true,
                onClick = onOpenLogs,
            )
            SettingsRow(
                iconRes = MorseIcons.warn,
                title = stringResource(R.string.settings_crashes),
                subtitle = pluralStringResource(
                    R.plurals.settings_crash_count,
                    state.crashCount,
                    state.crashCount,
                ),
                chevron = true,
                onClick = onOpenCrashes,
            )

            // Diagnostics -----------------------------------------------------
            SectionHeader(text = stringResource(R.string.settings_section_diagnostics))
            SettingsRow(
                iconRes = MorseIcons.shield,
                title = stringResource(R.string.settings_doctor),
                subtitle = stringResource(R.string.settings_doctor_sub),
                chevron = true,
                onClick = onOpenDoctor,
            )

            // About -----------------------------------------------------------
            SectionHeader(text = stringResource(R.string.settings_section_about))
            SettingsRow(
                iconRes = MorseIcons.radar,
                title = stringResource(R.string.settings_replay_onboarding),
                subtitle = stringResource(R.string.settings_replay_onboarding_sub),
                chevron = true,
                onClick = {
                    viewModel.replayOnboarding()
                    onReplayOnboarding()
                },
            )
            SettingsRow(
                iconRes = MorseIcons.help,
                title = stringResource(R.string.settings_help),
                subtitle = null,
                chevron = true,
                onClick = onOpenHelp,
            )
            SettingsRow(
                iconRes = MorseIcons.info,
                title = stringResource(R.string.settings_about),
                subtitle = stringResource(R.string.settings_about_sub, state.appVersion, state.versionCode),
            )

            Box(modifier = Modifier.size(metrics.screenPaddingBottom))
        }

        if (state.renameVisible) {
            MorseDialog(onDismiss = viewModel::dismissRename) {
                Text(
                    text = stringResource(R.string.settings_rename_title),
                    style = MorseTextStyles.listTitle,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                OutlinedTextField(
                    value = state.nameDraft,
                    onValueChange = viewModel::setNameDraft,
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_rename_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MorseButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = viewModel::dismissRename,
                        variant = MorseButtonVariant.GHOST,
                        modifier = Modifier.weight(1f),
                    )
                    MorseButton(
                        text = stringResource(R.string.action_save),
                        onClick = {
                            viewModel.saveDeviceName()
                            Toast.makeText(context, R.string.action_save, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(
    iconRes: Int,
    title: String,
    subtitle: String?,
    trailing: (@Composable () -> Unit)? = null,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = MorseTheme.colors
    MorseListRow(
        title = title,
        meta = subtitle,
        onClick = onClick,
        enabled = onClick != null,
        leading = {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(colors.raised, RoundedCornerShape(ROW_ICON_RADIUS)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                trailing?.invoke()
                if (chevron) {
                    MorseIconButton(
                        iconRes = MorseIcons.chevron,
                        contentDescription = null,
                        onClick = onClick ?: {},
                        enabled = onClick != null,
                        tint = colors.textTertiary,
                        size = 32.dp,
                        glyph = 16.dp,
                    )
                }
            }
        },
    )
}

private val ROW_ICON_RADIUS = 10.dp

private fun accentNameRes(accent: Accent): Int = when (accent) {
    Accent.SUNFLOWER -> R.string.accent_sunflower
    Accent.LEAF -> R.string.accent_leaf
    Accent.EMBER -> R.string.accent_ember
    Accent.VIOLET -> R.string.accent_violet
    Accent.SKY -> R.string.accent_sky
}
