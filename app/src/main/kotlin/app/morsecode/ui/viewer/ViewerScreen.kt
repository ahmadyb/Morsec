package app.morsecode.ui.viewer

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morsecode.R
import app.morsecode.core.design.component.MorseBodyText
import app.morsecode.core.design.component.MorseButton
import app.morsecode.core.design.component.MorseButtonVariant
import app.morsecode.core.design.component.MorseDialog
import app.morsecode.core.design.component.MorseEmptyState
import app.morsecode.core.design.component.MorseIconButton
import app.morsecode.core.design.component.MorseLoading
import app.morsecode.core.design.icon.MorseIcons
import app.morsecode.core.design.theme.MorseTextStyles
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.model.FeatureArea
import app.morsecode.core.model.MediaItem
import app.morsecode.ui.common.DeleteOutcome
import app.morsecode.ui.common.MediaDelete
import app.morsecode.ui.common.MediaFullImage
import app.morsecode.ui.common.ShareFiles
import app.morsecode.ui.common.rememberFeatureGate
import kotlinx.coroutines.launch

/** `.swipe .pic` and the deck's own backdrop: the reference is literal black here. */
private val ViewerBlack = Color.Black

/** `.swipe .hint` and the metadata line: the reference's own greys on black. */
private val ViewerHint = Color(0xFF9A9A9A)
private val ViewerMeta = Color(0xFF8A8A8A)
private val ViewerActionBackground = Color(0xFF1A1A1A)
private val ViewerActionGlyph = Color(0xFFDDDDDD)

/**
 * The image viewer (master prompt §4.5): a true-black deck of the device's
 * photographs, the name and metadata of the one being shown, its position in the
 * set, and five actions — share, edit, delete, information, send.
 *
 * Fidelity decisions, all recorded in doc/fidelity-notes.md §7:
 *
 * - The deck **wraps**. The reference builds three slides — previous, current,
 *   next — and takes each end modulo the set, so there is no first photograph and
 *   no last one. Wrapping is also what keeps a horizontal drag from ever meaning
 *   the same thing as the system back gesture, which owns the screen edge.
 * - There are no arrow buttons over the image and no overflow button. The
 *   reference has neither, and §4.5 forbids the overflow one by name.
 * - The top controls sit below the status bar and the actions above the navigation
 *   bar, so the viewer is full-bleed without putting a control under a system one.
 * - Position dots are drawn while the set is small enough for them to be readable
 *   (the reference has 15); past that the "n of m" line carries the position,
 *   because 200 dots are not an indicator.
 */
@Composable
public fun ViewerScreen(
    onBack: () -> Unit,
    viewModel: ViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val motion = MorseTheme.motion
    val colors = MorseTheme.colors

    // Resolved in composition: the click lambdas below must not read resources
    // through LocalContext, which is what lint's resource-in-lambda check rejects.
    val appName = stringResource(R.string.app_name)
    val backDescription = stringResource(R.string.action_back)
    val shareDescription = stringResource(R.string.action_share)
    val editDescription = stringResource(R.string.viewer_edit)
    val deleteDescription = stringResource(R.string.viewer_delete)
    val infoDescription = stringResource(R.string.viewer_info)
    val sendDescription = stringResource(R.string.viewer_send)
    val hintText = stringResource(R.string.viewer_hint)
    val loadingText = stringResource(R.string.viewer_loading)
    val noShareTarget = stringResource(R.string.error_share_no_app)
    val editFailed = stringResource(R.string.viewer_edit_failed)
    val deletedText = stringResource(R.string.viewer_deleted)
    val deleteFailed = stringResource(R.string.viewer_delete_failed)
    val deleteCancelled = stringResource(R.string.viewer_delete_cancelled)

    val gate = rememberFeatureGate()
    val items = state.items
    val count = items.size
    val current = state.current

    // Android asks the user before an app deletes media it did not contribute; this
    // is that question coming back, together with the photograph it was about.
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val pending = pendingDelete
        pendingDelete = null
        when {
            pending == null -> Unit

            result.resultCode != Activity.RESULT_OK ->
                Toast.makeText(context, deleteCancelled, Toast.LENGTH_SHORT).show()

            // On API 29 the answer is a permission, not a deletion: the row is still
            // there, so it is deleted now and only then reported as gone.
            pending.deleteAfterGrant -> scope.launch {
                val deleted = MediaDelete.completeAfterConsent(context, pending.uri)
                val text = if (deleted == DeleteOutcome.Deleted) deletedText else deleteFailed
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }

            else -> Toast.makeText(context, deletedText, Toast.LENGTH_SHORT).show()
        }
    }

    val pagerState = rememberPagerState(
        initialPage = if (count > 1) alignedPage(count, state.index) else 0,
        pageCount = { if (count > 1) ENDLESS_PAGES else count },
    )

    // The list arrives after the first composition, so the deck is moved to the
    // photograph that was tapped. Reduced motion jumps instead of animating.
    LaunchedEffect(count, state.index) {
        if (count == 0) return@LaunchedEffect
        // Only move the deck when it is not already showing this photograph. After a
        // swipe the settled page and the state agree, and re-aligning then would
        // drag the deck back through a million pages of its endless range.
        if (pagerState.currentPage % count == state.index) return@LaunchedEffect
        val target = if (count > 1) alignedPage(count, state.index) else state.index
        if (motion.reduced) {
            pagerState.scrollToPage(target)
        } else {
            pagerState.animateScrollToPage(target)
        }
    }

    // The settled page, not every page the finger travels past: the header would
    // otherwise count through photographs the user never stopped on.
    LaunchedEffect(pagerState, count) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (count > 0) viewModel.showPage(page % count)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(ViewerBlack)) {
        when {
            state.isEmpty -> MorseEmptyState(
                title = stringResource(R.string.viewer_empty_title),
                message = stringResource(R.string.viewer_empty_body),
                iconRes = MorseIcons.image,
                modifier = Modifier.fillMaxSize(),
            )

            state.loading -> MorseLoading(
                contentDescription = loadingText,
                modifier = Modifier.fillMaxSize(),
            )

            else -> Column(modifier = Modifier.fillMaxSize()) {
                ViewerHeader(
                    name = current?.displayName.orEmpty(),
                    position = if (count > 0) {
                        stringResource(R.string.viewer_position, state.index + 1, count)
                    } else {
                        ""
                    },
                    metadata = current?.let(viewModel::metadataFor).orEmpty(),
                    backDescription = backDescription,
                    onBack = onBack,
                )

                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        beyondViewportPageCount = 1,
                    ) { page ->
                        val item = items.getOrNull(page % count)
                        if (item != null) {
                            val position = (page % count) + 1
                            // The page names itself, so a screen reader hears which
                            // photograph this is whether or not the frame has decoded
                            // yet — and the frame inside stays decorative rather
                            // than announcing the same file a second time.
                            val pageDescription = stringResource(
                                R.string.viewer_image_description,
                                item.displayName,
                                position,
                                count,
                            )
                            // .swipe .slide{padding:0 6px} — a photograph is never
                            // flush against the edge of the phone.
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 6.dp)
                                    .semantics { contentDescription = pageDescription },
                                contentAlignment = Alignment.Center,
                            ) {
                                MediaFullImage(
                                    item = item,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(6.dp)),
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // .swipe .hint, above .swipe .dots.
                        Text(
                            text = hintText,
                            style = MorseTextStyles.monospacedAddress,
                            color = ViewerHint,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color.Black.copy(alpha = 0.45f))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                        if (count in 2..MAX_DOTS) {
                            Spacer(modifier = Modifier.height(15.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                repeat(count) { position ->
                                    val on = position == state.index
                                    Box(
                                        modifier = Modifier
                                            .height(5.dp)
                                            .then(if (on) Modifier.size(width = 14.dp, height = 5.dp) else Modifier.size(5.dp))
                                            .clip(RoundedCornerShape(if (on) 3.dp else 999.dp))
                                            .background(
                                                if (on) colors.accent else Color.White.copy(alpha = 0.3f),
                                            ),
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(top = 18.dp, bottom = 26.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
                ) {
                    ViewerAction(
                        iconRes = MorseIcons.share,
                        description = shareDescription,
                        onClick = {
                            val item = current ?: return@ViewerAction
                            scope.launch {
                                val uris = ShareFiles.prepare(context, listOf(item))
                                if (uris.isEmpty()) {
                                    Toast.makeText(context, noShareTarget, Toast.LENGTH_SHORT).show()
                                } else {
                                    ShareFiles.share(context, uris, appName)
                                }
                            }
                        },
                    )
                    ViewerAction(
                        iconRes = MorseIcons.edit,
                        description = editDescription,
                        onClick = {
                            val item = current ?: return@ViewerAction
                            // §4.5: editing goes to a compatible external editor
                            // where one exists, and says so where none does.
                            if (!ShareFiles.edit(context, item)) {
                                Toast.makeText(context, editFailed, Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                    ViewerAction(
                        iconRes = MorseIcons.trash,
                        description = deleteDescription,
                        onClick = { if (current != null) viewModel.confirmDelete(true) },
                    )
                    ViewerAction(
                        iconRes = MorseIcons.info,
                        description = infoDescription,
                        onClick = { if (current != null) viewModel.showInfo(true) },
                    )
                    ViewerAction(
                        iconRes = MorseIcons.send,
                        description = sendDescription,
                        accent = true,
                        onClick = { gate.run(FeatureArea.TRANSFER_ENGINE) { } },
                    )
                }
            }
        }

        if (state.infoVisible && current != null) {
            ViewerInfoDialog(item = current, viewModel = viewModel, onDismiss = { viewModel.showInfo(false) })
        }

        if (state.deleteConfirmVisible && current != null) {
            val item = current
            MorseDialog(onDismiss = { viewModel.confirmDelete(false) }) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.viewer_delete_title),
                        style = MorseTextStyles.listTitle,
                        color = colors.textPrimary,
                    )
                    MorseBodyText(text = stringResource(R.string.viewer_delete_body, item.displayName))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MorseButton(
                            text = stringResource(R.string.action_cancel),
                            onClick = { viewModel.confirmDelete(false) },
                            variant = MorseButtonVariant.GHOST,
                            modifier = Modifier.weight(1f),
                        )
                        MorseButton(
                            text = deleteDescription,
                            onClick = {
                                viewModel.confirmDelete(false)
                                val uri = item.uriString?.let { runCatching { it.toUri() }.getOrNull() }
                                if (uri == null) {
                                    Toast.makeText(context, deleteFailed, Toast.LENGTH_SHORT).show()
                                    return@MorseButton
                                }
                                scope.launch {
                                    when (val outcome = MediaDelete.request(context, uri)) {
                                        DeleteOutcome.Deleted ->
                                            Toast.makeText(context, deletedText, Toast.LENGTH_SHORT).show()

                                        is DeleteOutcome.Consent -> {
                                            pendingDelete = PendingDelete(uri, outcome.deleteAfterGrant)
                                            consentLauncher.launch(
                                                IntentSenderRequest.Builder(outcome.sender).build(),
                                            )
                                        }

                                        DeleteOutcome.Refused ->
                                            Toast.makeText(context, deleteFailed, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The sticky top controls: back, the file name, and what the platform knows about
 * it — its position in the set, its frame size and its size on disk.
 */
@Composable
private fun ViewerHeader(
    name: String,
    position: String,
    metadata: String,
    backDescription: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The reference puts the back control on a circle of white at 8%.
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            MorseIconButton(
                iconRes = MorseIcons.back,
                contentDescription = backDescription,
                onClick = onBack,
                tint = Color.White,
                size = 40.dp,
                glyph = 20.dp,
                pressedBackground = Color.White.copy(alpha = 0.16f),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MorseTextStyles.listTitle,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(position, metadata).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MorseTextStyles.meta,
                color = ViewerMeta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One 44 dp circular action on the black backdrop; the send action carries the accent. */
@Composable
private fun ViewerAction(
    iconRes: Int,
    description: String,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MorseTheme.colors
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (accent) colors.accent else ViewerActionBackground),
        contentAlignment = Alignment.Center,
    ) {
        MorseIconButton(
            iconRes = iconRes,
            contentDescription = description,
            onClick = onClick,
            tint = if (accent) colors.onAccent else ViewerActionGlyph,
            size = 44.dp,
            glyph = 20.dp,
            pressedBackground = if (accent) {
                colors.onAccent.copy(alpha = 0.16f)
            } else {
                Color.White.copy(alpha = 0.08f)
            },
        )
    }
}

/** §4.5's "file information": what the platform reported, and nothing invented. */
@Composable
private fun ViewerInfoDialog(
    item: MediaItem,
    viewModel: ViewerViewModel,
    onDismiss: () -> Unit,
) {
    val colors = MorseTheme.colors
    val unknown = stringResource(R.string.viewer_info_unknown)
    val rows = listOf(
        stringResource(R.string.viewer_info_type) to
            (item.mimeType ?: item.kind.id).ifEmpty { unknown },
        stringResource(R.string.viewer_info_size) to
            if (item.sizeBytes > 0L) viewModel.formatBytes(item.sizeBytes) else unknown,
        stringResource(R.string.viewer_info_dimensions) to
            (viewModel.dimensionsFor(item) ?: unknown),
        stringResource(R.string.viewer_info_modified) to
            if (item.dateModifiedEpochMillis > 0L) {
                viewModel.formatModified(item.dateModifiedEpochMillis)
            } else {
                unknown
            },
        stringResource(R.string.viewer_info_location) to (item.bucket ?: unknown),
    )

    MorseDialog(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.viewer_info_title),
                style = MorseTextStyles.listTitle,
                color = colors.textPrimary,
            )
            Text(
                text = item.displayName,
                style = MorseTextStyles.monospacedMeta,
                color = colors.textSecondary,
            )
            rows.forEach { (label, value) ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = label,
                        style = MorseTextStyles.meta,
                        color = colors.textTertiary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = value,
                        style = MorseTextStyles.meta,
                        color = colors.textPrimary,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
            MorseButton(
                text = stringResource(R.string.action_close),
                onClick = onDismiss,
                variant = MorseButtonVariant.WASH,
                fillWidth = true,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** The photograph a platform consent question was about, and what its answer still requires. */
private class PendingDelete(val uri: Uri, val deleteAfterGrant: Boolean)

/**
 * A page in an endless deck that shows the photograph at [index].
 *
 * The deck is a pager over a page count far larger than the set, started in the
 * middle of it and aligned so that page modulo the set size is the photograph being
 * shown. That is what makes both ends wrap without a seam the finger can find.
 */
private fun alignedPage(count: Int, index: Int): Int {
    val middle = ENDLESS_PAGES / 2
    return middle - (middle % count) + (index % count)
}

/** Large enough to be endless on a phone, small enough never to overflow a page index. */
private const val ENDLESS_PAGES = 1_000_003

/** Past this many photographs, dots stop being an indicator and the count line carries it. */
private const val MAX_DOTS = 20
