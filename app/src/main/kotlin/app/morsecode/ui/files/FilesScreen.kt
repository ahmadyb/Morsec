package app.morsecode.ui.files

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morsecode.R
import app.morsecode.core.design.component.CategoryTab
import app.morsecode.core.design.component.MorseButton
import app.morsecode.core.design.component.MorseButtonVariant
import app.morsecode.core.design.component.MorseCategoryPager
import app.morsecode.core.design.component.MorseCheckbox
import app.morsecode.core.design.component.MorseEmptyState
import app.morsecode.core.design.component.MorseIconButton
import app.morsecode.core.design.component.MorseListRow
import app.morsecode.core.design.component.MorseLoading
import app.morsecode.core.design.component.MorseModalSheet
import app.morsecode.core.design.component.MorseRadioButton
import app.morsecode.core.design.component.MorseScreenHeader
import app.morsecode.core.design.component.SectionHeader
import app.morsecode.core.design.component.morseScrollbar
import app.morsecode.core.design.icon.MorseIcons
import app.morsecode.core.design.theme.MorseTextStyles
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.model.FeatureArea
import app.morsecode.core.model.MediaItem
import app.morsecode.core.model.SortDirection
import app.morsecode.core.model.SortKey
import app.morsecode.core.model.SortOrder
import app.morsecode.core.storage.permissions.PermissionMatrix
import app.morsecode.core.storage.saf.SafPaths
import app.morsecode.navigation.MorseDestination
import app.morsecode.ui.common.MorseMediaRow
import app.morsecode.ui.common.MorseSelectionBar
import app.morsecode.ui.common.MediaOpen
import app.morsecode.ui.common.MorseTabScaffold
import app.morsecode.ui.common.ShareFiles
import app.morsecode.ui.common.mediaOpenFor
import app.morsecode.ui.common.rememberFeatureGate
import kotlinx.coroutines.launch

@StringRes
private fun tabLabelRes(tab: FilesTab): Int = when (tab) {
    FilesTab.PHOTOS -> R.string.files_tab_photos
    FilesTab.VIDEOS -> R.string.files_tab_videos
    FilesTab.MUSIC -> R.string.files_tab_music
    FilesTab.APPS -> R.string.files_tab_apps
    FilesTab.FILES -> R.string.files_tab_files
}

/**
 * The Files destination: five categories over real device content.
 *
 * Photos and videos use the reference's 3-column grid with day sections and a
 * select-all circle per section; music, apps and documents use rows. Selection
 * drives a bottom bar with a real system share and a gated Send, and the Files
 * tab can add an SAF folder, which is a genuine grant the app keeps.
 */
@Composable
public fun FilesScreen(
    onNavigate: (MorseDestination) -> Unit,
    onOpenFolder: (String) -> Unit,
    /**
     * Opens the image viewer at [item].
     *
     * The sort order travels with it: the viewer shows the same list in the same
     * order, so its "3 of 15" is the 3 of 15 the user counted in the grid.
     */
    onOpenViewer: (MediaItem, SortOrder) -> Unit,
    onOpenMusic: (MediaItem, SortOrder) -> Unit,
    viewModel: FilesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    val gate = rememberFeatureGate()
    val scope = rememberCoroutineScope()
    // Chooser title for "share outside Morsecode"; resolved in composition so the
    // click lambda never reads resources through LocalContext.
    val appName = stringResource(R.string.app_name)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> viewModel.onPermissionResult(result.values.any { it }) }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let { viewModel.addFolder(it.toString()) } }

    // The one-shot message text is resolved in composition: the effect below is a
    // coroutine, not a composable scope, and reading a string through
    // LocalContext there is what lint's LocalContextGetResourceValueCall rejects.
    val message = state.message
    val messageText: String? = when (message) {
        is FilesMessage.FolderAdded -> stringResource(R.string.files_grant_added, message.displayName)
        FilesMessage.GrantFailed -> stringResource(R.string.files_grant_failed)
        FilesMessage.PermissionDeclined -> stringResource(R.string.files_permission_body)
        null -> null
    }
    val messageDuration =
        if (message is FilesMessage.PermissionDeclined) Toast.LENGTH_LONG else Toast.LENGTH_SHORT

    LaunchedEffect(message) {
        val text = messageText
        if (text != null) Toast.makeText(context, text, messageDuration).show()
        if (message != null) viewModel.consumeMessage()
    }

    MorseTabScaffold(selected = MorseDestination.FILES, onNavigate = onNavigate) {
        MorseScreenHeader(
            title = stringResource(R.string.files_title),
            actions = {
                MorseIconButton(
                    iconRes = MorseIcons.sliders,
                    contentDescription = stringResource(R.string.files_sort),
                    onClick = { viewModel.openSortSheet(true) },
                )
                MorseIconButton(
                    iconRes = MorseIcons.check,
                    contentDescription = stringResource(R.string.files_select),
                    onClick = { viewModel.toggleSelecting() },
                )
                if (state.tab == FilesTab.FILES) {
                    MorseIconButton(
                        iconRes = MorseIcons.plus,
                        contentDescription = stringResource(R.string.files_add_folder),
                        onClick = { folderLauncher.launch(null) },
                    )
                }
            },
        )

        MorseCategoryPager(
            tabs = FilesTab.ordered.map { tab -> CategoryTab(tab.id, stringResource(tabLabelRes(tab))) },
            selectedId = state.tab.id,
            onSelect = viewModel::selectTab,
            modifier = Modifier.weight(1f),
        ) { page ->
            // The pager knows categories by id; the screen knows them by enum.
            FilesCategoryPage(
                tab = FilesTab.fromId(page.id),
                state = state,
                viewModel = viewModel,
                onOpenFolder = onOpenFolder,
                onRequestPermission = {
                    permissionLauncher.launch(
                        (PermissionMatrix.mediaRead() + PermissionMatrix.mediaWrite()).toTypedArray(),
                    )
                },
                onAddFolder = { folderLauncher.launch(null) },
                onOpen = { item ->
                    when (val target = mediaOpenFor(item)) {
                        // §4.5: photographs open in the viewer the app draws.
                        is MediaOpen.Viewer -> onOpenViewer(target.item, state.sortOrder)

                        // §4.6: tracks open in the player the app draws, in the
                        // order this list was showing them.
                        is MediaOpen.MusicPlayer -> onOpenMusic(target.item, state.sortOrder)

                        is MediaOpen.HandOff -> {
                            if (!ShareFiles.open(context, target.item)) {
                                Toast.makeText(context, R.string.history_open_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
            )
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

        if (state.sortSheetVisible) {
            MorseModalSheet(
                onDismiss = { viewModel.openSortSheet(false) },
                title = stringResource(R.string.files_sort_sheet_title),
            ) {
                SortKey.ordered.forEach { key ->
                    MorseListRow(
                        title = stringResource(sortKeyLabelRes(key)),
                        leading = {
                            MorseRadioButton(
                                selected = state.sortOrder.key == key,
                                onClick = { viewModel.setSortKey(key) },
                                contentDescription = stringResource(sortKeyLabelRes(key)),
                            )
                        },
                        onClick = { viewModel.setSortKey(key) },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MorseButton(
                        text = stringResource(R.string.files_sort_ascending),
                        onClick = { viewModel.setSortDirection(SortDirection.ASC) },
                        variant = if (state.sortOrder.direction == SortDirection.ASC) {
                            MorseButtonVariant.WASH
                        } else {
                            MorseButtonVariant.GHOST
                        },
                        modifier = Modifier.weight(1f),
                        small = true,
                    )
                    MorseButton(
                        text = stringResource(R.string.files_sort_descending),
                        onClick = { viewModel.setSortDirection(SortDirection.DESC) },
                        variant = if (state.sortOrder.direction == SortDirection.DESC) {
                            MorseButtonVariant.WASH
                        } else {
                            MorseButtonVariant.GHOST
                        },
                        modifier = Modifier.weight(1f),
                        small = true,
                    )
                }
            }
        }
    }
}

/**
 * One Files category page inside [MorseCategoryPager].
 *
 * The page renders from its own [TabContent], so what is on screen belongs to the
 * category under the finger rather than to whichever one is selected, and a
 * category that has never been read shows [MorseLoading] instead of claiming to
 * be empty. Scroll position survives a category change: the pager keeps each
 * page's `rememberSaveable` state, so the grid or list is created once per page.
 */
@Composable
private fun FilesCategoryPage(
    tab: FilesTab,
    state: FilesUiState,
    viewModel: FilesViewModel,
    onOpenFolder: (String) -> Unit,
    onRequestPermission: () -> Unit,
    onAddFolder: () -> Unit,
    onOpen: (MediaItem) -> Unit,
) {
    val content = state.contentFor(tab)
    val needsPermission = !state.access.canListMedia &&
        (tab == FilesTab.PHOTOS || tab == FilesTab.VIDEOS || tab == FilesTab.MUSIC)
    val needsFolder = tab == FilesTab.FILES && !state.access.canListDocuments

    when {
        needsPermission -> MorseEmptyState(
            title = stringResource(R.string.files_permission_title),
            message = stringResource(R.string.files_permission_body),
            iconRes = MorseIcons.lock,
            modifier = Modifier.fillMaxSize(),
            action = {
                MorseButton(
                    text = stringResource(R.string.files_permission_grant),
                    onClick = onRequestPermission,
                    variant = MorseButtonVariant.WASH,
                )
            },
        )

        // The category's real query has not returned yet.
        content == null -> MorseLoading(
            contentDescription = stringResource(R.string.files_category_loading),
        )

        content.itemCount == 0 -> MorseEmptyState(
            title = stringResource(emptyTitleRes(tab)),
            message = stringResource(emptyBodyRes(tab)),
            iconRes = MorseIcons.folder,
            modifier = Modifier.fillMaxSize(),
            action = if (needsFolder) {
                {
                    MorseButton(
                        text = stringResource(R.string.files_add_folder),
                        onClick = onAddFolder,
                        variant = MorseButtonVariant.WASH,
                    )
                }
            } else {
                null
            },
        )

        tab == FilesTab.PHOTOS || tab == FilesTab.VIDEOS -> MediaGrid(
            tab = tab,
            content = content,
            state = state,
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize(),
            onOpen = onOpen,
        )

        else -> MediaList(
            tab = tab,
            content = content,
            state = state,
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize(),
            onOpen = onOpen,
            onOpenFolder = onOpenFolder,
            onRemoveFolder = viewModel::removeFolder,
        )
    }
}

@Composable
private fun MediaGrid(
    tab: FilesTab,
    content: TabContent,
    state: FilesUiState,
    viewModel: FilesViewModel,
    modifier: Modifier = Modifier,
    onOpen: (MediaItem) -> Unit,
) {
    val metrics = MorseTheme.metrics
    val colors = MorseTheme.colors
    val gridState = rememberLazyGridState()
    val showVideoBadges = tab == FilesTab.VIDEOS

    LazyVerticalGrid(
        columns = GridCells.Fixed(metrics.gridColumnsAtReferenceWidth),
        state = gridState,
        modifier = modifier
            .fillMaxSize()
            .morseScrollbar(gridState),
        contentPadding = PaddingValues(
            horizontal = metrics.screenPaddingHorizontal,
            vertical = metrics.gridGapMedia,
        ),
        horizontalArrangement = Arrangement.spacedBy(metrics.gridGapMedia),
        verticalArrangement = Arrangement.spacedBy(metrics.gridGapMedia),
    ) {
        content.sections.forEach { section ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "header-${section.label}") {
                SectionHeader(
                    text = if (section.label.isEmpty()) {
                        stringResource(R.string.files_all_section, section.items.size)
                    } else {
                        stringResource(R.string.files_section_header, section.label, section.items.size)
                    },
                    actionIconRes = MorseIcons.check,
                    actionIconDescription = stringResource(R.string.files_select),
                    onActionIcon = { viewModel.selectGroup(section.items.map { it.id }) },
                )
            }
            items(section.items, key = { it.id }) { item ->
                MediaTile(
                    item = item,
                    selected = item.id in state.selection,
                    selecting = state.selecting || state.selection.isNotEmpty(),
                    durationLabel = if (showVideoBadges && item.durationMillis > 0L) {
                        viewModel.formatDuration(item.durationMillis)
                    } else {
                        null
                    },
                    onClick = {
                        if (state.selecting || state.selection.isNotEmpty()) {
                            viewModel.toggleItem(item.id)
                        } else {
                            onOpen(item)
                        }
                    },
                    onToggleSelect = { viewModel.toggleItem(item.id) },
                    accent = colors.accent,
                    borderColor = colors.line,
                    radius = metrics.radiusSm,
                    badgeBackground = colors.viewerIconBackground,
                )
            }
        }
    }
}

/** True when a row is a folder the app was granted, and can therefore be browsed. */
private fun isBrowsableFolder(item: MediaItem): Boolean =
    item.isFolder && item.uriString?.let(SafPaths::isTreeUri) == true

@Composable
private fun MediaTile(
    item: MediaItem,
    selected: Boolean,
    selecting: Boolean,
    durationLabel: String?,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit,
    accent: Color,
    borderColor: Color,
    radius: androidx.compose.ui.unit.Dp,
    badgeBackground: Color,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(borderColor)
            .then(
                if (selected) {
                    Modifier.border(BorderStroke(2.dp, accent), shape)
                } else {
                    Modifier
                },
            )
            .clickableTile(onClick),
    ) {
        MediaThumbnail(item = item, modifier = Modifier.fillMaxSize())

        if (selecting) {
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                MorseCheckbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelect() },
                    round = true,
                    overlay = true,
                    contentDescription = item.displayName,
                )
            }
        }

        if (durationLabel != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(badgeBackground.copy(alpha = 0.78f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = durationLabel,
                    style = MorseTextStyles.meta,
                    color = Color.White,
                )
            }
        }

        if (item.kind == app.morsecode.core.model.MediaKind.VIDEO && !selecting) {
            Icon(
                painter = painterResource(MorseIcons.play),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(28.dp),
            )
        }
    }
}

private fun Modifier.clickableTile(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

@Composable
private fun MediaList(
    tab: FilesTab,
    content: TabContent,
    state: FilesUiState,
    viewModel: FilesViewModel,
    modifier: Modifier = Modifier,
    onOpen: (MediaItem) -> Unit,
    onOpenFolder: (String) -> Unit,
    onRemoveFolder: (Long) -> Unit,
) {
    val metrics = MorseTheme.metrics
    val colors = MorseTheme.colors
    val listState = rememberLazyListState()
    val selecting = state.selecting || state.selection.isNotEmpty()

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().morseScrollbar(listState),
        contentPadding = PaddingValues(
            horizontal = metrics.screenPaddingHorizontal,
            vertical = metrics.gridGapApps,
        ),
        verticalArrangement = Arrangement.spacedBy(metrics.gridGapApps),
    ) {
        content.sections.forEach { section ->
            if (section.label.isNotEmpty()) {
                item(key = "header-${section.label}") {
                    SectionHeader(
                        text = stringResource(R.string.files_section_header, section.label, section.items.size),
                        actionIconRes = MorseIcons.check,
                        actionIconDescription = stringResource(R.string.files_select),
                        onActionIcon = { viewModel.selectGroup(section.items.map { it.id }) },
                    )
                }
            }
            items(section.items, key = { it.id }) { item ->
                MorseMediaRow(
                    item = item,
                    selected = item.id in state.selection,
                    selecting = selecting,
                    subtitle = rowSubtitle(item, viewModel, tab),
                    onClick = {
                        when {
                            selecting -> viewModel.toggleItem(item.id)
                            // A granted SAF folder is browsable; a MediaStore
                            // bucket row is not, so it keeps opening as a file.
                            isBrowsableFolder(item) -> item.uriString?.let(onOpenFolder)
                            else -> onOpen(item)
                        }
                    },
                    onToggleSelect = { viewModel.toggleItem(item.id) },
                    onRemove = if (item.isFolder && item.id.startsWith("saf:")) {
                        { item.id.removePrefix("saf:").toLongOrNull()?.let(onRemoveFolder) }
                    } else {
                        null
                    },
                    removeDescription = stringResource(R.string.files_remove_folder),
                )
            }
        }
    }
}

private fun rowSubtitle(item: MediaItem, viewModel: FilesViewModel, tab: FilesTab): String {
    val size = if (item.sizeBytes > 0L) viewModel.formatBytes(item.sizeBytes) else null
    val duration = if (item.durationMillis > 0L) viewModel.formatDuration(item.durationMillis) else null
    val detail = when (tab) {
        FilesTab.MUSIC -> listOfNotNull(item.artist, item.album, duration, size)
        FilesTab.APPS -> listOfNotNull(item.bucket, size)
        else -> listOfNotNull(item.bucket, duration, size)
    }
    return detail.joinToString(" · ")
}

@StringRes
private fun emptyTitleRes(tab: FilesTab): Int = when (tab) {
    FilesTab.PHOTOS -> R.string.files_empty_photos_title
    FilesTab.VIDEOS -> R.string.files_empty_videos_title
    FilesTab.MUSIC -> R.string.files_empty_music_title
    FilesTab.APPS -> R.string.files_empty_apps_title
    FilesTab.FILES -> R.string.files_empty_files_title
}

@StringRes
private fun emptyBodyRes(tab: FilesTab): Int = when (tab) {
    FilesTab.PHOTOS -> R.string.files_empty_photos_body
    FilesTab.VIDEOS -> R.string.files_empty_videos_body
    FilesTab.MUSIC -> R.string.files_empty_music_body
    FilesTab.APPS -> R.string.files_empty_apps_body
    FilesTab.FILES -> R.string.files_empty_files_body
}

@StringRes
private fun sortKeyLabelRes(key: SortKey): Int = when (key) {
    SortKey.DATE -> R.string.files_sort_date
    SortKey.NAME -> R.string.files_sort_name
    SortKey.SIZE -> R.string.files_sort_size
    SortKey.TYPE -> R.string.files_sort_type
}
