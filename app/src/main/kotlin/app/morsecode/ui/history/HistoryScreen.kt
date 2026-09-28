package app.morsecode.ui.history

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Box
import kotlinx.coroutines.launch
import android.net.Uri
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morsecode.R
import app.morsecode.core.design.component.FileKindIcon
import app.morsecode.core.design.component.MorseButton
import app.morsecode.core.design.component.MorseButtonVariant
import app.morsecode.core.design.component.MorseDialog
import app.morsecode.core.design.component.MorseEmptyState
import app.morsecode.core.design.component.MorseIconButton
import app.morsecode.core.design.component.MorseListRow
import app.morsecode.core.design.component.MorseScreenHeader
import app.morsecode.core.design.component.SectionHeader
import app.morsecode.core.design.component.SegmentedOption
import app.morsecode.core.design.component.SegmentedPill
import app.morsecode.core.design.component.morseScrollbar
import app.morsecode.core.design.icon.MorseIcons
import app.morsecode.core.design.theme.MorseTextStyles
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.model.FeatureArea
import app.morsecode.core.model.HistoryEntry
import app.morsecode.core.model.MediaItem
import app.morsecode.core.model.SessionDirection
import app.morsecode.navigation.MorseDestination
import app.morsecode.ui.common.MorseTabScaffold
import app.morsecode.ui.common.ShareFiles
import app.morsecode.ui.common.rememberFeatureGate

/**
 * The History destination: one table, two tabs.
 *
 * Rows are grouped by day and carry the reference's per-row overflow menu. Open
 * and Share are real system actions on the stored uri, Remove deletes the row,
 * Delete file deletes the file, and Send again is gated until the transfer
 * engine exists.
 */
@Composable
public fun HistoryScreen(
    onNavigate: (MorseDestination) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    val context = LocalContext.current
    val gate = rememberFeatureGate()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    MorseTabScaffold(selected = MorseDestination.HISTORY, onNavigate = onNavigate) {
        MorseScreenHeader(
            title = stringResource(R.string.history_title),
            actions = {
                MorseIconButton(
                    iconRes = MorseIcons.search,
                    contentDescription = stringResource(R.string.action_search),
                    onClick = viewModel::toggleSearch,
                )
                MorseIconButton(
                    iconRes = MorseIcons.trash,
                    contentDescription = stringResource(R.string.action_clear),
                    onClick = viewModel::requestClear,
                    enabled = state.itemCount > 0,
                )
            },
        )

        if (state.searchVisible) {
            androidx.compose.material3.OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = metrics.screenPaddingHorizontal, vertical = 6.dp),
                placeholder = { Text(stringResource(R.string.history_search_hint)) },
                singleLine = true,
            )
        }

        SegmentedPill(
            options = listOf(
                SegmentedOption(SessionDirection.INBOUND.id, stringResource(R.string.history_tab_received)),
                SegmentedOption(SessionDirection.OUTBOUND.id, stringResource(R.string.history_tab_sent)),
            ),
            selectedId = state.direction.id,
            onSelect = viewModel::setDirection,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = metrics.screenPaddingHorizontal, vertical = 8.dp),
        )

        if (state.itemCount == 0) {
            MorseEmptyState(
                title = stringResource(R.string.history_empty_title),
                message = stringResource(R.string.history_empty_body),
                iconRes = MorseIcons.clock,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().weight(1f).morseScrollbar(listState),
                contentPadding = PaddingValues(horizontal = metrics.screenPaddingHorizontal),
            ) {
                state.sections.forEach { section ->
                    item(key = "header-${section.label}") {
                        SectionHeader(text = section.label)
                    }
                    items(section.entries, key = { it.historyId }) { entry ->
                        HistoryRow(
                            entry = entry,
                            subtitle = historySubtitle(entry, viewModel),
                            successColor = colors.ok,
                            failureColor = colors.error,
                            onOpen = {
                                if (!ShareFiles.open(context, entry.asMediaItem())) {
                                    Toast.makeText(context, R.string.history_open_failed, Toast.LENGTH_SHORT).show()
                                }
                            },
                            onShare = { target ->
                                scope.launch {
                                    val uris = ShareFiles.prepare(context, listOf(target.asMediaItem()))
                                    if (uris.isEmpty()) {
                                        Toast.makeText(context, R.string.error_share_no_app, Toast.LENGTH_SHORT).show()
                                    } else {
                                        ShareFiles.share(context, uris, target.displayName)
                                    }
                                }
                            },
                            onSendAgain = { gate.run(FeatureArea.TRANSFER_ENGINE) { } },
                            onRemove = {
                                viewModel.remove(entry)
                                Toast.makeText(context, R.string.history_removed, Toast.LENGTH_SHORT).show()
                            },
                            onDeleteFile = {
                                val deleted = entry.resultUriString?.let { uri ->
                                    runCatching {
                                        context.contentResolver.delete(Uri.parse(uri), null, null)
                                    }.getOrDefault(0)
                                } ?: 0
                                Toast.makeText(
                                    context,
                                    if (deleted > 0) R.string.history_file_deleted else R.string.history_file_delete_failed,
                                    Toast.LENGTH_SHORT,
                                ).show()
                                if (deleted > 0) viewModel.remove(entry)
                            },
                        )
                    }
                }
            }
        }

        if (state.confirmClearVisible) {
            MorseDialog(onDismiss = viewModel::dismissClear) {
                Text(
                    text = stringResource(R.string.history_clear_confirm),
                    style = MorseTextStyles.listTitle,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MorseButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = viewModel::dismissClear,
                        variant = MorseButtonVariant.GHOST,
                        modifier = Modifier.weight(1f),
                    )
                    MorseButton(
                        text = stringResource(R.string.action_clear),
                        onClick = {
                            viewModel.clearVisibleTab()
                            Toast.makeText(context, R.string.history_cleared, Toast.LENGTH_SHORT).show()
                        },
                        variant = MorseButtonVariant.DANGER,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    subtitle: String,
    successColor: Color,
    failureColor: Color,
    onOpen: () -> Unit,
    onShare: (HistoryEntry) -> Unit,
    onSendAgain: () -> Unit,
    onRemove: () -> Unit,
    onDeleteFile: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val success = entry.isSuccess

    MorseListRow(
        title = entry.displayName,
        meta = subtitle,
        onClick = onOpen,
        leading = { FileKindIcon(kind = entry.kind) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(if (success) MorseIcons.check else MorseIcons.close),
                    contentDescription = stringResource(
                        if (success) R.string.history_row_completed else R.string.history_row_failed,
                    ),
                    tint = if (success) successColor else failureColor,
                    modifier = Modifier.size(18.dp),
                )
                Box {
                    MorseIconButton(
                        iconRes = MorseIcons.moreV,
                        contentDescription = stringResource(R.string.action_more),
                        onClick = { menuOpen = true },
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.history_action_open)) },
                            onClick = {
                                menuOpen = false
                                onOpen()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.history_action_share)) },
                            onClick = {
                                menuOpen = false
                                onShare(entry)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.history_action_send_again)) },
                            onClick = {
                                menuOpen = false
                                onSendAgain()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.history_action_remove)) },
                            onClick = {
                                menuOpen = false
                                onRemove()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.history_action_delete_file)) },
                            onClick = {
                                menuOpen = false
                                onDeleteFile()
                            },
                        )
                    }
                }
            }
        },
    )
}

private fun historySubtitle(entry: HistoryEntry, viewModel: HistoryViewModel): String =
    listOfNotNull(
        entry.peerName?.takeIf { it.isNotBlank() },
        if (entry.totalBytes > 0L) viewModel.formatBytes(entry.totalBytes) else null,
        if (entry.finishedEpochMillis > 0L) viewModel.formatClock(entry.finishedEpochMillis) else null,
    ).joinToString(" · ")

/** Adapts a history row to the sharing helper without inventing metadata. */
private fun HistoryEntry.asMediaItem(): MediaItem = MediaItem(
    id = transferId,
    displayName = displayName,
    kind = kind,
    mimeType = mimeType,
    sizeBytes = totalBytes,
    dateModifiedEpochMillis = finishedEpochMillis,
    uriString = resultUriString,
)
