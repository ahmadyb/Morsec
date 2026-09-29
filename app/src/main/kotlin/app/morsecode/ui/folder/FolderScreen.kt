package app.morsecode.ui.folder

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morsecode.R
import app.morsecode.core.design.component.MorseButton
import app.morsecode.core.design.component.MorseButtonVariant
import app.morsecode.core.design.component.MorseCrumb
import app.morsecode.core.design.component.MorseDialog
import app.morsecode.core.design.component.MorseEmptyState
import app.morsecode.core.design.component.MorseIconButton
import app.morsecode.core.design.component.MorseLoading
import app.morsecode.core.design.component.MorseMetaText
import app.morsecode.core.design.component.MorseScreenHeader
import app.morsecode.core.design.component.SectionHeader
import app.morsecode.core.design.component.morseScrollbar
import app.morsecode.core.design.icon.MorseIcons
import app.morsecode.core.design.theme.MorseTextStyles
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.model.FeatureArea
import app.morsecode.navigation.MorseDestination
import app.morsecode.ui.common.MorseMediaRow
import app.morsecode.ui.common.MorseSelectionBar
import app.morsecode.ui.common.MorseTabScaffold
import app.morsecode.ui.common.ShareFiles
import app.morsecode.ui.common.rememberFeatureGate
import kotlinx.coroutines.launch

/**
 * The internal folder browser: one SAF level, its real contents, and the path bar
 * that is the only way up (master prompt §4.3).
 *
 * The header stays put, the breadcrumb sits directly below it and never scrolls
 * away with the rows, and there is no upward-arrow control — every level of the
 * path is a target instead. Going down pushes a destination, so the system back
 * gesture walks the path out one level at a time and each level keeps its own
 * scroll position and selection.
 *
 * The path can be copied and pasted back. Editing it is offered because it is
 * genuinely useful in a deep tree, and it is safe because [FolderViewModel] only
 * ever resolves a path inside the grant the user gave, and checks the result
 * against the platform before the browser claims a folder is there.
 */
@Composable
public fun FolderScreen(
    onBack: () -> Unit,
    onNavigate: (MorseDestination) -> Unit,
    onOpenFolder: (String) -> Unit,
    viewModel: FolderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    val gate = rememberFeatureGate()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Resolved in composition: the click lambdas below must not read resources
    // through LocalContext, which is what lint's resource-in-lambda check rejects.
    val rootLabel = stringResource(R.string.folder_root_internal)
    val appName = stringResource(R.string.app_name)
    val pathLabel = stringResource(R.string.folder_path)
    val copiedText = stringResource(R.string.folder_path_copied)
    val fallbackTitle = stringResource(R.string.folder_title)
    val selecting = state.selecting || state.selection.isNotEmpty()

    val labels = state.levels.map { level -> if (level.isVolume) rootLabel else level.name }
    val title = state.title.ifEmpty { labels.lastOrNull().orEmpty() }.ifEmpty { fallbackTitle }
    val path = labels.joinToString("/")

    val message = state.message
    val messageText = when (message) {
        FolderMessage.Unavailable -> stringResource(R.string.folder_unavailable)
        FolderMessage.PathOutsideGrant -> stringResource(R.string.folder_path_outside)
        FolderMessage.PathNotFound -> stringResource(R.string.folder_path_not_found)
        null -> null
    }
    LaunchedEffect(message) {
        val text = messageText
        if (text != null) Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        if (message != null) viewModel.consumeMessage()
    }

    MorseTabScaffold(selected = MorseDestination.FILES, onNavigate = onNavigate) {
        MorseScreenHeader(
            title = title,
            nested = true,
            leading = {
                MorseIconButton(
                    iconRes = MorseIcons.back,
                    contentDescription = stringResource(R.string.action_back),
                    onClick = onBack,
                )
            },
            actions = {
                MorseIconButton(
                    iconRes = MorseIcons.check,
                    contentDescription = stringResource(R.string.files_select),
                    onClick = viewModel::toggleSelecting,
                )
            },
        )

        MorseCrumb(
            levels = labels.ifEmpty { listOf(title) },
            onLevelClick = { index -> viewModel.levelUri(index)?.let(onOpenFolder) },
            iconDescription = pathLabel,
            currentDescription = stringResource(R.string.folder_current_level),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = metrics.screenPaddingHorizontal, vertical = 6.dp),
            trailing = {
                MorseIconButton(
                    iconRes = MorseIcons.copy,
                    contentDescription = stringResource(R.string.folder_copy_path),
                    size = metrics.iconButtonSizeSmall,
                    glyph = metrics.iconButtonGlyphSmall,
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText(pathLabel, path))
                        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                    },
                )
                MorseIconButton(
                    iconRes = MorseIcons.edit,
                    contentDescription = stringResource(R.string.folder_edit_path),
                    size = metrics.iconButtonSizeSmall,
                    glyph = metrics.iconButtonGlyphSmall,
                    onClick = { viewModel.openPathEditor(true) },
                )
            },
        )

        when {
            !state.accessible -> MorseEmptyState(
                title = stringResource(R.string.folder_unavailable_title),
                message = stringResource(R.string.folder_unavailable),
                iconRes = MorseIcons.lock,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            state.loading -> MorseLoading(
                contentDescription = stringResource(R.string.files_category_loading),
                modifier = Modifier.weight(1f),
            )

            state.items.isEmpty() -> MorseEmptyState(
                title = stringResource(R.string.folder_empty_title),
                message = stringResource(R.string.folder_empty_body),
                iconRes = MorseIcons.folder,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).morseScrollbar(listState),
                contentPadding = PaddingValues(
                    horizontal = metrics.screenPaddingHorizontal,
                    vertical = metrics.gridGapApps,
                ),
                verticalArrangement = Arrangement.spacedBy(metrics.gridGapApps),
            ) {
                item(key = "header") {
                    SectionHeader(
                        text = stringResource(R.string.files_section_header, title, state.items.size),
                        actionIconRes = MorseIcons.check,
                        actionIconDescription = stringResource(R.string.folder_select_all),
                        onActionIcon = {
                            val allSelected = state.items.isNotEmpty() &&
                                state.selection.size == state.items.size
                            if (allSelected) viewModel.clearSelection() else viewModel.selectAll()
                        },
                    )
                }
                items(state.items, key = { it.id }) { item ->
                    MorseMediaRow(
                        item = item,
                        selected = item.id in state.selection,
                        selecting = selecting,
                        subtitle = viewModel.subtitleFor(item).orEmpty(),
                        onClick = {
                            when {
                                selecting -> viewModel.toggleItem(item.id)
                                item.isFolder -> item.uriString?.let(onOpenFolder)
                                else -> {
                                    if (!ShareFiles.open(context, item)) {
                                        Toast.makeText(
                                            context,
                                            R.string.history_open_failed,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            }
                        },
                        onToggleSelect = { viewModel.toggleItem(item.id) },
                        onRemove = null,
                        removeDescription = null,
                    )
                }
            }
        }

        if (state.selection.isNotEmpty()) {
            MorseSelectionBar(
                summary = stringResource(R.string.files_selected_count, state.selection.size) +
                    " · " + viewModel.formatBytes(state.selectedBytes),
                onShare = {
                    scope.launch {
                        val uris = ShareFiles.prepare(context, viewModel.selectedItems())
                        if (uris.isEmpty()) {
                            Toast.makeText(context, R.string.error_share_no_app, Toast.LENGTH_SHORT).show()
                        } else {
                            ShareFiles.share(context, uris, appName)
                        }
                    }
                },
                onSend = { gate.run(FeatureArea.TRANSFER_ENGINE) { } },
                onClear = viewModel::clearSelection,
            )
        }

        if (state.pathEditorVisible) {
            MorseDialog(onDismiss = { viewModel.openPathEditor(false) }) {
                Text(
                    text = stringResource(R.string.folder_edit_path_title),
                    style = MorseTextStyles.listTitle,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = state.pathDraft,
                    onValueChange = viewModel::setPathDraft,
                    singleLine = true,
                    label = { Text(stringResource(R.string.folder_edit_path_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                MorseMetaText(
                    text = stringResource(R.string.folder_edit_path_help),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    color = colors.textSecondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MorseButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = { viewModel.openPathEditor(false) },
                        variant = MorseButtonVariant.GHOST,
                        modifier = Modifier.weight(1f),
                    )
                    MorseButton(
                        text = stringResource(R.string.folder_open),
                        onClick = {
                            scope.launch { viewModel.submitPath(rootLabel)?.let(onOpenFolder) }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
