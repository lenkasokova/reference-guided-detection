/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.screen.detect.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import cz.vut.oneshotdetector.viewmodel.detect.DetectViewModel
import cz.vut.oneshotdetector.view.screen.detect.DetectConfig
import cz.vut.oneshotdetector.view.screen.detect.displayLabel
import cz.vut.oneshotdetector.view.theme.LocalSpacing

/**
 * Full-screen scrim overlay that lets the user pick a [DetectViewModel.DetectMode].
 *
 * Tapping anywhere outside the popup card dismisses it via [onDismiss].
 * To add a new mode, add an entry to [DetectViewModel.DetectMode] and extend [displayLabel].
 */
@Composable
internal fun DetectModeSelector(
    currentMode: DetectViewModel.DetectMode,
    onModeSelected: (DetectViewModel.DetectMode) -> Unit,
    onDismiss: () -> Unit
) {
    val spacing = LocalSpacing.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DetectConfig.overlayScrim)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onDismiss() },
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .statusBarsPadding()
                .padding(top = spacing.xxl)
                .background(
                    color = DetectConfig.modeSelectorBg,
                    shape = RoundedCornerShape(DetectConfig.modeSelectorCornerRadius)
                )
                .padding(vertical = spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            DetectViewModel.DetectMode.entries.forEach { type ->
                val isSelected = type == currentMode
                TextButton(
                    onClick = { onModeSelected(type) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.md)
                ) {
                    Text(
                        text = type.displayLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White
                    )
                }
            }
        }
    }
}
