package app.morsecode.ui.doctor

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import app.morsecode.core.design.component.MorseButton
import app.morsecode.core.design.component.MorseButtonVariant
import app.morsecode.core.design.component.MorseDivider
import app.morsecode.core.design.component.MorseIconButton
import app.morsecode.core.design.component.MorseListRow
import app.morsecode.core.design.component.MorseMetaText
import app.morsecode.core.design.component.MorseScreenHeader
import app.morsecode.core.design.icon.MorseIcons
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.model.CheckStatus
import app.morsecode.core.model.DiagnosticCheck
import app.morsecode.navigation.MorseDestination
import app.morsecode.ui.common.MorseTabScaffold

/**
 * The Connection Doctor: one row per real check, each with a corrective action
 * when the platform offers one.
 */
@Composable
public fun DoctorScreen(
    onBack: () -> Unit,
    onNavigate: (MorseDestination) -> Unit,
    viewModel: DoctorViewModel = hiltViewModel(),
) {
    val checks by viewModel.checks.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    val context = LocalContext.current

    val problems = checks.count { it.status.isProblem }

    MorseTabScaffold(selected = MorseDestination.SETTINGS, onNavigate = onNavigate) {
        MorseScreenHeader(
            title = stringResource(R.string.doctor_title),
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
                    iconRes = MorseIcons.refresh,
                    contentDescription = stringResource(R.string.doctor_refresh),
                    onClick = viewModel::refresh,
                    enabled = !running,
                )
            },
        )

        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = metrics.screenPaddingHorizontal),
            ) {
                items(checks, key = { it.id }) { check ->
                    CheckRow(check = check, runAction = viewModel::runAction) { handled ->
                        if (!handled) {
                            Toast.makeText(context, R.string.error_generic, Toast.LENGTH_SHORT).show()
                        }
                    }
                    MorseDivider()
                }
                item(key = "summary") {
                    MorseMetaText(
                        text = if (problems == 0 && checks.isNotEmpty()) {
                            stringResource(R.string.doctor_all_ok)
                        } else {
                            stringResource(R.string.doctor_check_summary, checks.size, problems)
                        },
                        modifier = Modifier.padding(vertical = 14.dp),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = metrics.screenPaddingHorizontal, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MorseButton(
                    text = stringResource(R.string.doctor_battery_action),
                    iconRes = MorseIcons.battery,
                    variant = MorseButtonVariant.WASH,
                    fillWidth = true,
                    onClick = {
                        runCatching { context.startActivity(viewModel.batterySettingsIntent()) }
                        viewModel.refresh()
                    },
                )
                MorseButton(
                    text = stringResource(R.string.doctor_refresh),
                    iconRes = MorseIcons.refresh,
                    variant = MorseButtonVariant.GHOST,
                    fillWidth = true,
                    enabled = !running,
                    onClick = viewModel::refresh,
                )
            }
        }
    }
}

@Composable
private fun CheckRow(
    check: DiagnosticCheck,
    runAction: (DiagnosticCheck) -> Boolean,
    onAction: (Boolean) -> Unit,
) {
    val colors = MorseTheme.colors
    val statusColor = when (check.status) {
        CheckStatus.OK -> colors.ok
        CheckStatus.WARN -> colors.warn
        CheckStatus.ERROR -> colors.error
        CheckStatus.UNKNOWN -> colors.textTertiary
    }
    val statusIcon = when (check.status) {
        CheckStatus.OK -> MorseIcons.check
        CheckStatus.WARN -> MorseIcons.info
        else -> MorseIcons.close
    }

    MorseListRow(
        title = stringResource(check.titleId),
        meta = if (check.detailArgs.isEmpty()) {
            stringResource(check.detailId)
        } else {
            stringResource(check.detailId, *check.detailArgs.toTypedArray())
        },
        leading = {
            Box(
                modifier = Modifier.size(28.dp).background(statusColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(statusIcon),
                    contentDescription = check.status.id,
                    tint = Color(BADGE_INK),
                    modifier = Modifier.size(16.dp),
                )
            }
        },
        trailing = if (check.action != null) {
            {
                Text(
                    text = check.actionId?.let { stringResource(it) } ?: stringResource(R.string.action_grant),
                    style = app.morsecode.core.design.theme.MorseTextStyles.meta,
                    color = colors.accent,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        } else {
            null
        },
        onClick = if (check.action != null) {
            { onAction(runAction(check)) }
        } else {
            null
        },
    )
}

/** The reference draws status glyphs in near-black ink on the status colour. */
private const val BADGE_INK: Long = 0xFF0B0B0B
