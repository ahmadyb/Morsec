package app.morsecode.ui.help

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import app.morsecode.core.design.component.MorseBodyText
import app.morsecode.core.design.component.MorseDivider
import app.morsecode.core.design.component.MorseIconButton
import app.morsecode.core.design.component.MorseListRow
import app.morsecode.core.design.component.MorseScreenHeader
import app.morsecode.core.design.component.SectionHeader
import app.morsecode.core.design.icon.MorseIcons
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.navigation.MorseDestination
import app.morsecode.ui.common.MorseTabScaffold

private data class FaqEntry(@StringRes val question: Int, @StringRes val answer: Int)

private val faqEntries = listOf(
    FaqEntry(R.string.help_q1, R.string.help_a1),
    FaqEntry(R.string.help_q2, R.string.help_a2),
    FaqEntry(R.string.help_q3, R.string.help_a3),
    FaqEntry(R.string.help_q4, R.string.help_a4),
    FaqEntry(R.string.help_q5, R.string.help_a5),
    FaqEntry(R.string.help_q6, R.string.help_a6),
)

/**
 * Help & FAQ: an accordion of the six questions the reference lists, plus a
 * troubleshooting entry that deep-links to the Connection Doctor.
 */
@Composable
public fun HelpScreen(
    onBack: () -> Unit,
    onNavigate: (MorseDestination) -> Unit,
    onOpenDoctor: () -> Unit,
) {
    val metrics = MorseTheme.metrics
    var expanded by remember { mutableStateOf<Int?>(null) }

    MorseTabScaffold(selected = MorseDestination.SETTINGS, onNavigate = onNavigate) {
        MorseScreenHeader(
            title = stringResource(R.string.help_title),
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
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = metrics.screenPaddingHorizontal),
        ) {
            faqEntries.forEachIndexed { index, entry ->
                val isOpen = expanded == index
                MorseListRow(
                    title = stringResource(entry.question),
                    onClick = { expanded = if (isOpen) null else index },
                    trailing = {
                        MorseIconButton(
                            iconRes = if (isOpen) MorseIcons.chevD else MorseIcons.chevron,
                            contentDescription = null,
                            onClick = { expanded = if (isOpen) null else index },
                            size = 32.dp,
                            glyph = 16.dp,
                        )
                    },
                )
                if (isOpen) {
                    MorseBodyText(
                        text = stringResource(entry.answer),
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp),
                    )
                }
                MorseDivider()
            }

            SectionHeader(text = stringResource(R.string.help_troubleshoot))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MorseButton(
                    text = stringResource(R.string.help_open_doctor),
                    iconRes = MorseIcons.shield,
                    variant = MorseButtonVariant.WASH,
                    fillWidth = true,
                    onClick = onOpenDoctor,
                )
            }
        }
    }
}
