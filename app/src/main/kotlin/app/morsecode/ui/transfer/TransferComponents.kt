package app.morsecode.ui.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.morsecode.R
import app.morsecode.core.design.component.ChipStyle
import app.morsecode.core.design.component.FileKindIcon
import app.morsecode.core.design.component.MorseBodyText
import app.morsecode.core.design.component.MorseButton
import app.morsecode.core.design.component.MorseButtonVariant
import app.morsecode.core.design.component.MorseChip
import app.morsecode.core.design.component.MorseDialog
import app.morsecode.core.design.component.MorseIconButton
import app.morsecode.core.design.component.MorseWash
import app.morsecode.core.design.component.PeerAvatar
import app.morsecode.core.design.component.ProgressFlavour
import app.morsecode.core.design.component.TransferProgressBar
import app.morsecode.core.design.icon.MorseIcons
import app.morsecode.core.design.theme.MorseTextStyles
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.design.tokens.MorseMetrics

/**
 * The pieces the transfer and broadcast screens are both made of, in one place.
 *
 * These were the duplex views' own until the broadcast screens needed them: a broadcast
 * delivery to one phone *is* a transfer, so its progress bar, its status chip, its second line
 * and its per-file controls have to mean exactly what they mean on the duplex screens, and the
 * screen that watches a batch fan out needs the same header, the same peer card and the same
 * "end this?" question. Sharing them here — rather than copying them into a second package —
 * is what keeps one row's vocabulary from drifting into two, and it is why the per-file matrix
 * lives in one function ([TransferActions.of]) that both call.
 */

/**
 * One file, with the controls its state permits and no others.
 *
 * The direction arrow is drawn when the row has room for it. The reference draws one on every
 * transfer row, and this app reports a 48 dp touch box for each icon button, so at the narrow
 * end of the phone range two leading glyphs, a status chip and up to two controls cannot all
 * fit beside a file name worth reading. The file-kind icon is the one that stays — it says
 * what the file *is* — and the arrow is the one that can go, because which way a file is
 * going is already stated by the section it is in and by its status chip. Nothing is dropped
 * on a 411 dp phone, and the trade is asserted at 360 dp.
 *
 * [showControls] is false where a row is a report rather than a control: the broadcast
 * receiver's rows carry none, exactly as the reference draws them, because the batch's single
 * Pause all / Resume all is where a receiver acts on the batch.
 */
@Composable
internal fun TransferRowView(
    row: TransferRow,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    showControls: Boolean = true,
) {
    val item = row.item
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    val actions = item.actions

    val meta = transferMetaText(row)
    val progressDescription = stringResource(
        R.string.transfer_progress_description,
        row.transferred,
        row.total,
        row.percent,
    )

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val controls = when {
            !showControls || !actions.any -> 0
            actions.pause && actions.cancel || actions.resume && actions.cancel -> 2
            else -> 1
        }
        val roomForDirection = maxWidth >= rowWidthNeeded(
            metrics = metrics,
            controls = controls,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding, vertical = RowPaddingVertical),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GlyphGap),
        ) {
            if (roomForDirection) DirectionGlyph(item)

            // Tagged so a test can assert which glyph a row drew. The icon itself carries no
            // description: the file's name is announced beside it, and "Video" said twice adds
            // nothing to a screen reader reading the row in order.
            FileKindIcon(
                kind = item.kind,
                size = metrics.fileIconSize,
                modifier = Modifier.testTag(TransferKindTagPrefix + item.kind.id),
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(RowInnerGap),
            ) {
                Text(
                    text = item.fileName,
                    style = MorseTextStyles.listTitle,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = meta,
                    style = MorseTextStyles.meta,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TransferProgressBar(
                    fraction = item.fraction,
                    flavour = progressFlavourFor(item.state),
                    modifier = Modifier.semantics {
                        contentDescription = progressDescription
                        progressBarRangeInfo = ProgressBarRangeInfo(item.fraction, 0f..1f)
                    },
                )
            }

            MorseChip(text = transferChipText(item.state), style = transferChipStyle(item.state))

            if (showControls && actions.any) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ToolsGap),
                ) {
                    if (actions.pause) {
                        RowControl(
                            iconRes = MorseIcons.pause,
                            description = stringResource(R.string.transfer_pause_file, item.fileName),
                            onClick = onPause,
                        )
                    }
                    if (actions.resume) {
                        RowControl(
                            iconRes = MorseIcons.play,
                            description = stringResource(R.string.transfer_resume_file, item.fileName),
                            onClick = onResume,
                        )
                    }
                    if (actions.retry) {
                        RowControl(
                            iconRes = MorseIcons.refresh,
                            description = stringResource(R.string.transfer_retry_file, item.fileName),
                            onClick = onRetry,
                        )
                    }
                    if (actions.cancel) {
                        RowControl(
                            iconRes = MorseIcons.close,
                            description = stringResource(R.string.transfer_cancel_file, item.fileName),
                            onClick = onCancel,
                        )
                    }
                }
            }
        }
    }
}

/** One per-file control, drawn small and reported at the app's full touch size. */
@Composable
private fun RowScope.RowControl(
    iconRes: Int,
    description: String,
    onClick: () -> Unit,
) {
    val metrics = MorseTheme.metrics
    MorseIconButton(
        iconRes = iconRes,
        contentDescription = description,
        onClick = onClick,
        tint = MorseTheme.colors.textSecondary,
        size = metrics.iconButtonSizeSmall,
        glyph = metrics.iconButtonGlyphSmall,
    )
}

/** The arrow the reference draws before a file: up while sending, down while arriving. */
@Composable
private fun DirectionGlyph(item: TransferItem) {
    MorseIconButton(
        iconRes = when {
            item.state.isComplete -> MorseIcons.check
            item.direction == TransferDirection.INCOMING -> MorseIcons.down
            else -> MorseIcons.up
        },
        contentDescription = null,
        onClick = {},
        enabled = false,
        tint = MorseTheme.colors.textSecondary,
        size = MorseTheme.metrics.transferDirectionGlyph,
        glyph = MorseTheme.metrics.iconButtonGlyphSmall,
    )
}

/**
 * The connected peer: avatar, name, what the connection is, and its transport label.
 *
 * [subtitle] and [badge] are handed in rather than derived here, because the two screens that
 * draw this card describe the same relationship differently: a duplex session says
 * "Connected · Phone · LAN", while a broadcast receiver is looking at the phone that fanned
 * the batch out and says "Broadcast · Phone · LAN" over a FROM chip. The transport itself is
 * never written twice — both callers pass it in from the peer.
 */
@Composable
internal fun TransferPeerCard(
    peer: TransferPeer,
    subtitle: String,
    badge: String,
) {
    val colors = MorseTheme.colors
    MorseWash(modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GlyphGap),
        ) {
            PeerAvatar(letter = peer.letter.first(), colorSeed = peer.id, large = true)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.name,
                    style = MorseTextStyles.listTitle,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MorseTextStyles.meta,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MorseChip(text = badge, style = ChipStyle.OUTLINE)
        }
    }
}

/**
 * Ending a session asks before it acts.
 *
 * The body says the two things a user needs: how much is still unfinished and what happens to
 * it, and that nothing already delivered is taken back. Dismissing — by either button, by the
 * back gesture or by tapping outside — changes nothing at all. A broadcast session asks the
 * same question with the same words, because it is the same promise about the same files.
 */
@Composable
internal fun EndSessionDialog(
    unfinishedCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    MorseDialog(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.transfer_end_title),
                style = MorseTextStyles.listTitle,
                color = MorseTheme.colors.textPrimary,
            )
            MorseBodyText(
                text = if (unfinishedCount > 0) {
                    pluralStringResource(
                        R.plurals.transfer_end_unfinished,
                        unfinishedCount,
                        unfinishedCount,
                    )
                } else {
                    stringResource(R.string.transfer_end_nothing_unfinished)
                },
            )
            MorseBodyText(text = stringResource(R.string.transfer_end_kept))
            MorseButton(
                text = stringResource(R.string.transfer_end_confirm),
                onClick = onConfirm,
                variant = MorseButtonVariant.DANGER,
                fillWidth = true,
            )
            MorseButton(
                text = stringResource(R.string.transfer_end_keep),
                onClick = onDismiss,
                variant = MorseButtonVariant.GHOST,
                fillWidth = true,
            )
        }
    }
}

/** The reference's `.hd` row: the way back, and which screen this is. */
@Composable
internal fun TransferScreenHeader(
    title: String,
    backDescription: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MorseIconButton(
            iconRes = MorseIcons.back,
            contentDescription = backDescription,
            onClick = onBack,
        )
        Text(
            text = title,
            style = MorseTextStyles.screenTitle,
            color = MorseTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The second line of a row, written from the facts its state states. */
@Composable
internal fun transferMetaText(row: TransferRow): String = when (val meta = row.item.meta) {
    is TransferMeta.Transferring -> stringResource(
        R.string.transfer_meta_transferring,
        row.transferred,
        row.total,
        row.speed,
    )

    is TransferMeta.Waiting -> stringResource(R.string.transfer_meta_waiting, row.total)
    is TransferMeta.Held -> stringResource(
        R.string.transfer_meta_paused,
        row.transferred,
        row.total,
        row.transferred,
    )

    is TransferMeta.Verifying -> stringResource(R.string.transfer_meta_verifying, row.total)
    is TransferMeta.Delivered -> stringResource(
        if (meta.verification == VerificationOutcome.MISMATCH) {
            R.string.transfer_meta_mismatch
        } else {
            R.string.transfer_meta_delivered
        },
        row.total,
    )

    is TransferMeta.Broken -> stringResource(R.string.transfer_meta_failed, row.transferred, row.total)
    is TransferMeta.Abandoned -> stringResource(R.string.transfer_meta_cancelled, row.transferred, row.total)
    is TransferMeta.Passed -> stringResource(R.string.transfer_meta_skipped, row.total)
}

/** The reference's `chipFor`: the state, in one word, in its own colour. */
@Composable
internal fun transferChipText(state: TransferState): String = stringResource(
    when (state) {
        TransferState.SENDING -> R.string.transfer_chip_sending
        TransferState.RECEIVING -> R.string.transfer_chip_receiving
        TransferState.QUEUED -> R.string.transfer_chip_queued
        TransferState.PAUSED -> R.string.transfer_chip_paused
        TransferState.VERIFYING -> R.string.transfer_chip_verifying
        TransferState.DONE -> R.string.transfer_chip_done
        TransferState.FAILED -> R.string.transfer_chip_failed
        TransferState.CANCELLED -> R.string.transfer_chip_cancelled
        TransferState.SKIPPED -> R.string.transfer_chip_skipped
    },
)

internal fun transferChipStyle(state: TransferState): ChipStyle = when (state) {
    TransferState.SENDING, TransferState.VERIFYING -> ChipStyle.ACCENT
    TransferState.RECEIVING -> ChipStyle.RECEIVE
    TransferState.PAUSED -> ChipStyle.WARN
    TransferState.DONE -> ChipStyle.OK
    TransferState.FAILED -> ChipStyle.ERROR
    TransferState.QUEUED, TransferState.CANCELLED, TransferState.SKIPPED -> ChipStyle.NEUTRAL
}

/** `.bar` and its variants, by state. */
internal fun progressFlavourFor(state: TransferState): ProgressFlavour = when (state) {
    TransferState.RECEIVING -> ProgressFlavour.RECEIVE
    TransferState.PAUSED, TransferState.CANCELLED, TransferState.SKIPPED -> ProgressFlavour.PAUSED
    TransferState.DONE -> ProgressFlavour.DONE
    TransferState.FAILED -> ProgressFlavour.ERROR
    TransferState.SENDING, TransferState.QUEUED, TransferState.VERIFYING -> ProgressFlavour.ACTIVE
}

/**
 * The width a transfer row needs before it can also draw the direction arrow.
 *
 * The screen's own padding, both leading glyphs, the four gaps between cells, a file name
 * worth reading, the longest status chip, and one or two 48 dp controls. Everything here is a
 * token except the name and the chip: the two are estimates of how much room text needs, and
 * they are the reason the arrow moves rather than the name or a control.
 */
private fun rowWidthNeeded(
    metrics: MorseMetrics,
    controls: Int,
): Dp = ScreenPadding * 2 +
    metrics.transferDirectionGlyph +
    metrics.fileIconSize +
    GlyphGap * 4 +
    NameBudget +
    ChipBudget +
    metrics.touchTarget * controls

/** The reference's `.li` gap of 12 px between everything in a row. */
internal val GlyphGap = 12.dp

/** `.li{padding:12px 0}`. */
private val RowPaddingVertical = 12.dp

/** The gap between a row's name, its second line and its bar. */
private val RowInnerGap = 3.dp

/** `.tx-tools` sits its controls closer together than the row's own gap. */
private val ToolsGap = 2.dp

/** The screen's own horizontal padding (`.scr`), shared by the list and its headings. */
internal val ScreenPadding = 16.dp

/** How much room a file name is worth before the arrow gives up its place. */
private val NameBudget = 96.dp

/** The longest status chip ("VERIFYING"), measured at the chip's own type. */
private val ChipBudget = 72.dp

/**
 * The prefix a transfer row's file-kind icon is tagged with, so a test can say which glyph a
 * given kind draws. There is no user-facing text for a kind anywhere in the app yet, and
 * inventing seven labels to make one assertion possible would be inventing copy.
 */
internal const val TransferKindTagPrefix = "transfer-kind-"
