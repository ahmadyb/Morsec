package app.morsecode.ui.logs

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morsecode.R
import app.morsecode.core.design.component.MorseButton
import app.morsecode.core.design.component.MorseButtonVariant
import app.morsecode.core.design.component.MorseEmptyState
import app.morsecode.core.design.component.MorseIconButton
import app.morsecode.core.design.component.MorseMetaText
import app.morsecode.core.design.component.MorseScreenHeader
import app.morsecode.core.design.component.morseScrollbar
import app.morsecode.core.design.icon.MorseIcons
import app.morsecode.core.design.theme.MorseTextStyles
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.model.LogEntry
import app.morsecode.core.model.LogLevel
import app.morsecode.navigation.MorseDestination
import app.morsecode.ui.common.Exports
import app.morsecode.ui.common.MorseTabScaffold
import kotlinx.coroutines.launch

/**
 * The Logs destination: timestamped rows, an errors-only filter, export and clear.
 *
 * Rows are the same ones the logger wrote (already redacted), the export writes
 * the identical text into a file shared through the FileProvider, and clear
 * deletes them for real.
 */
@Composable
public fun LogsScreen(
    onBack: () -> Unit,
    onNavigate: (MorseDestination) -> Unit,
    viewModel: LogsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    MorseTabScaffold(selected = MorseDestination.SETTINGS, onNavigate = onNavigate) {
        MorseScreenHeader(
            title = stringResource(R.string.logs_title),
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
                    iconRes = MorseIcons.trash,
                    contentDescription = stringResource(R.string.action_clear),
                    onClick = {
                        viewModel.clear()
                        Toast.makeText(context, R.string.logs_cleared, Toast.LENGTH_SHORT).show()
                    },
                )
            },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = metrics.screenPaddingHorizontal, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MorseButton(
                text = stringResource(R.string.logs_export),
                iconRes = MorseIcons.share,
                small = true,
                onClick = {
                    scope.launch {
                        val uri = Exports.writeText(context, "morsecode-log.txt", viewModel.exportText())
                        if (uri == null) {
                            Toast.makeText(context, R.string.error_export_failed, Toast.LENGTH_SHORT).show()
                        } else {
                            Exports.shareUri(context, uri, context.getString(R.string.logs_shared_subject))
                        }
                    }
                },
            )
            MorseButton(
                text = stringResource(R.string.logs_errors_only),
                small = true,
                variant = if (state.errorsOnly) MorseButtonVariant.FILLED else MorseButtonVariant.GHOST,
                onClick = viewModel::toggleErrorsOnly,
            )
            MorseButton(
                text = stringResource(R.string.action_clear),
                small = true,
                variant = MorseButtonVariant.GHOST,
                onClick = {
                    viewModel.clear()
                    Toast.makeText(context, R.string.logs_cleared, Toast.LENGTH_SHORT).show()
                },
            )
        }

        if (state.entries.isEmpty()) {
            MorseEmptyState(
                title = stringResource(R.string.logs_empty_title),
                message = stringResource(R.string.logs_empty_body),
                iconRes = MorseIcons.list,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().weight(1f).morseScrollbar(listState),
                contentPadding = PaddingValues(horizontal = metrics.screenPaddingHorizontal),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(state.entries, key = { it.id }) { entry ->
                    LogRow(entry = entry, timestamp = viewModel.stamp(entry))
                }
            }
        }

        MorseMetaText(
            text = stringResource(R.string.logs_export_header, "${BuildConfigLabel.VERSION} (${BuildConfigLabel.CODE})"),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = metrics.screenPaddingHorizontal, vertical = 14.dp),
        )
    }
}

@Composable
private fun LogRow(entry: LogEntry, timestamp: String) {
    val colors = MorseTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = timestamp,
            style = MorseTextStyles.logRow,
            color = colors.textTertiary,
            modifier = Modifier.width(76.dp),
        )
        Box(
            modifier = Modifier
                .width(48.dp)
                .background(levelColor(entry.level, colors), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = entry.level.id,
                style = MorseTextStyles.logRow,
                color = Color.White,
            )
        }
        Text(
            text = entry.message,
            style = MorseTextStyles.logRow,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun levelColor(level: LogLevel, colors: app.morsecode.core.design.tokens.MorseColorTokens): Color =
    when (level) {
        LogLevel.ERROR -> colors.error
        LogLevel.WARN -> colors.warn
        LogLevel.INFO -> colors.info
        LogLevel.DEBUG -> colors.textTertiary
    }

/** Version header printed under the list and at the top of every export. */
private object BuildConfigLabel {
    val VERSION: String = app.morsecode.BuildConfig.VERSION_NAME
    val CODE: Int = app.morsecode.BuildConfig.VERSION_CODE
}
