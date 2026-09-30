package app.morsecode.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.morsecode.R
import app.morsecode.core.design.component.MorseButton
import app.morsecode.core.design.component.MorseButtonVariant
import app.morsecode.core.design.component.MorseDialog
import app.morsecode.core.design.component.MorseBodyText
import app.morsecode.core.design.theme.MorseTextStyles
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.model.FeatureArea
import app.morsecode.core.model.FeatureReadiness
import androidx.compose.material3.Text

/**
 * Honest gating for actions this build cannot perform yet.
 *
 * The product forbids placeholders: a control either does its stated job or
 * explains plainly why it cannot. [rememberFeatureGate] returns a handler that
 * either runs the real action (when [FeatureReadiness] says the area is live) or
 * opens a dialog naming the milestone that delivers it. No progress is ever
 * simulated and no button is silently dead.
 */
@Composable
public fun rememberFeatureGate(): FeatureGate {
    var pending by remember { mutableStateOf<FeatureArea?>(null) }

    val gate = remember {
        FeatureGate(
            onBlocked = { area -> pending = area },
        )
    }

    pending?.let { area ->
        FeatureGateDialog(area = area, onDismiss = { pending = null })
    }
    return gate
}

/** Hands an action to [run], or reports the blocked area instead. */
public class FeatureGate(private val onBlocked: (FeatureArea) -> Unit) {

    /** Runs [action] when [area] is live in this build. */
    public fun run(area: FeatureArea, action: () -> Unit) {
        if (FeatureReadiness.isAvailable(area)) action() else onBlocked(area)
    }

    public fun isAvailable(area: FeatureArea): Boolean = FeatureReadiness.isAvailable(area)
}

@Composable
private fun FeatureGateDialog(area: FeatureArea, onDismiss: () -> Unit) {
    MorseDialog(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.gated_title),
                style = MorseTextStyles.listTitle,
                color = MorseTheme.colors.textPrimary,
            )
            MorseBodyText(
                text = stringResource(
                    R.string.gated_body,
                    stringResource(areaNameRes(area)),
                    area.deliveredInMilestone,
                    FeatureReadiness.CURRENT_MILESTONE,
                ),
            )
            MorseButton(
                text = stringResource(R.string.gated_action),
                onClick = onDismiss,
                variant = MorseButtonVariant.WASH,
                fillWidth = true,
            )
        }
    }
}

@StringRes
private fun areaNameRes(area: FeatureArea): Int = when (area) {
    FeatureArea.TRANSFER_ENGINE, FeatureArea.SESSIONS_AND_BROADCAST ->
        R.string.gated_area_send

    // Backgrounding a session is the foreground service's job, and saying so names what is
    // actually missing rather than repeating the sending message.
    FeatureArea.BACKGROUND_SERVICE -> R.string.gated_area_background

    FeatureArea.LAN_TRANSPORT, FeatureArea.NEARBY_TRANSPORT, FeatureArea.DOCTOR_NEARBY ->
        R.string.gated_area_discovery

    FeatureArea.WEBSHARE_SERVER, FeatureArea.WEBSHARE_CLIENT -> R.string.gated_area_webshare
    FeatureArea.MEDIA_PLAYBACK -> R.string.gated_area_playback
    else -> R.string.gated_area_send
}
