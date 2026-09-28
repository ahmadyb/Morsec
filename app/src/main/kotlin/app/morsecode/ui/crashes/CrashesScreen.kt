package app.morsecode.ui.crashes

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import app.morsecode.core.design.component.MorseMetaText
import app.morsecode.core.design.component.MorseScreenHeader
import app.morsecode.core.design.component.MorseWash
import app.morsecode.core.design.icon.MorseIcons
import app.morsecode.core.design.theme.MorseTextStyles
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.model.CrashReport
import app.morsecode.navigation.MorseDestination
import app.morsecode.ui.common.Exports
import app.morsecode.ui.common.MorseTabScaffold
import kotlinx.coroutines.launch

/**
 * The Crash reports destination.
 *
 * Reports were written locally by [app.morsecode.core.data.crash.CrashRecorder]
 * and are never uploaded: the only way one leaves the device is the user pressing
 * export and choosing a destination.
 */
@Composable
public fun CrashesScreen(
    onBack: () -> Unit,
    onNavigate: (MorseDestination) -> Unit,
    viewModel: CrashesViewModel = hiltViewModel(),
) {
    val reports by viewModel.reports.collectAsStateWithLifecycle()
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    MorseTabScaffold(selected = MorseDestination.SETTINGS, onNavigate = onNavigate) {
        MorseScreenHeader(
            title = stringResource(R.string.crashes_title),
            nested = true,
            leading = {
                MorseIconButton(
                    iconRes = MorseIcons.back,
                    contentDescription = stringResource(R.string.action_back),
                    onClick = onBack,
                )
            },
        )

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = metrics.screenPaddingHorizontal),
        ) {
            MorseWash(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.crashes_privacy_title),
                    style = MorseTextStyles.listTitle,
                    color = colors.textPrimary,
                )
                MorseMetaText(text = stringResource(R.string.crashes_privacy_body))
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MorseButton(
                    text = stringResource(R.string.action_share),
                    iconRes = MorseIcons.share,
                    small = true,
                    onClick = {
                        scope.launch {
                            val uri = Exports.writeText(
                                context,
                                "morsecode-crashes.txt",
                                viewModel.exportText(),
                            )
                            if (uri == null) {
                                Toast.makeText(context, R.string.error_export_failed, Toast.LENGTH_SHORT).show()
                            } else {
                                Exports.shareUri(
                                    context,
                                    uri,
                                    context.getString(R.string.crashes_shared_subject),
                                )
                            }
                        }
                    },
                )
                MorseButton(
                    text = stringResource(R.string.action_clear),
                    iconRes = MorseIcons.trash,
                    small = true,
                    variant = MorseButtonVariant.GHOST,
                    onClick = {
                        viewModel.clear()
                        Toast.makeText(context, R.string.crashes_cleared, Toast.LENGTH_SHORT).show()
                    },
                )
            }

            if (reports.isEmpty()) {
                MorseEmptyState(
                    title = stringResource(R.string.crashes_empty_title),
                    message = stringResource(R.string.crashes_empty_body),
                    iconRes = MorseIcons.shield,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    contentPadding = PaddingValues(bottom = metrics.screenPaddingBottom),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    items(reports, key = { it.id }) { report ->
                        CrashCard(report = report, whenLabel = viewModel.whenLabel(report)) {
                            viewModel.delete(report.id)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CrashCard(report: CrashReport, whenLabel: String, onDelete: () -> Unit) {
    val colors = MorseTheme.colors
    MorseCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            MorseChip(text = stringResource(R.string.crashes_badge), style = ChipStyle.ERROR)
            Text(
                text = report.component,
                style = MorseTextStyles.listTitle,
                color = colors.textPrimary,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            MorseMetaText(text = whenLabel)
            MorseIconButton(
                iconRes = MorseIcons.trash,
                contentDescription = stringResource(R.string.action_clear),
                onClick = onDelete,
                size = 32.dp,
                glyph = 16.dp,
            )
        }
        Text(
            text = report.exceptionType,
            style = MorseTextStyles.monospacedMeta,
            color = colors.error,
            modifier = Modifier.padding(top = 9.dp, bottom = 4.dp),
        )
        report.recoveryNote?.let { MorseMetaText(text = it) }
        Text(
            text = report.stackTrace,
            style = MorseTextStyles.logRow,
            color = colors.textTertiary,
            modifier = Modifier
                .padding(top = 8.dp)
                .horizontalScroll(rememberScrollState()),
            maxLines = 8,
        )
    }
}
