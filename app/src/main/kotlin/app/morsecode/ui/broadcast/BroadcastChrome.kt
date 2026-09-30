package app.morsecode.ui.broadcast

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.morsecode.R
import app.morsecode.core.design.icon.MorseIcons
import app.morsecode.ui.common.MorseActionBar
import app.morsecode.ui.common.MorseActionBarItem
import app.morsecode.ui.common.MorseActionBarTone
import app.morsecode.ui.transfer.TransferAllLabel

/**
 * The broadcast bar: the same four cells on every broadcast screen.
 *
 * A batch is a transfer with more than one receiver, so it keeps the transfer bar exactly —
 * Add files, the single whole-batch Pause all / Resume all, Background, End — rather than
 * inventing a broadcast-shaped one. The shared function is what keeps the sender and the
 * receiver from drifting: the receiver's bar and the sender's bar are one piece of code, and
 * so is the completion screens', where the all-action is simply disabled because nothing is
 * left to hold.
 */
@Composable
internal fun BroadcastActionBar(
    allAction: BroadcastAllAction,
    onAddFiles: () -> Unit,
    onToggleAll: () -> Unit,
    onBackground: () -> Unit,
    onRequestEnd: () -> Unit,
) {
    MorseActionBar(
        items = listOf(
            MorseActionBarItem(
                id = "add",
                label = stringResource(R.string.transfer_add_files),
                iconRes = MorseIcons.plus,
                tone = MorseActionBarTone.PRIMARY,
                onClick = onAddFiles,
            ),
            MorseActionBarItem(
                id = "all",
                label = when (allAction.label) {
                    TransferAllLabel.PAUSE_ALL -> stringResource(R.string.transfer_pause_all)
                    TransferAllLabel.RESUME_ALL -> stringResource(R.string.transfer_resume_all)
                },
                iconRes = when (allAction.label) {
                    TransferAllLabel.PAUSE_ALL -> MorseIcons.pause
                    TransferAllLabel.RESUME_ALL -> MorseIcons.play
                },
                enabled = allAction.enabled,
                onClick = onToggleAll,
            ),
            MorseActionBarItem(
                id = "background",
                label = stringResource(R.string.transfer_background),
                iconRes = MorseIcons.minus,
                onClick = onBackground,
            ),
            MorseActionBarItem(
                id = "end",
                label = stringResource(R.string.transfer_end),
                iconRes = MorseIcons.close,
                tone = MorseActionBarTone.DANGER,
                onClick = onRequestEnd,
            ),
        ),
    )
}
