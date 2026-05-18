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
import cz.vut.oneshotdetector.view.screen.detect.compareModeDisplayLabel
import cz.vut.oneshotdetector.view.theme.LocalSpacing

/**
 * Full-screen scrim overlay for picking a [DetectViewModel.CompareMode].
 *
 * Only [DetectViewModel.CompareMode.Reference] and [DetectViewModel.CompareMode.WholeGallery]
 * are shown. The active mode is highlighted with the primary colour.
 * Tapping outside the card dismisses via [onDismiss].
 */
@Composable
internal fun DetectCompareModeSelector(
    currentMode: DetectViewModel.CompareMode,
    onModeSelected: (DetectViewModel.CompareMode) -> Unit,
    onDismiss: () -> Unit
) {
    val spacing = LocalSpacing.current
    val options = listOf(
        DetectViewModel.CompareMode.Reference,
        DetectViewModel.CompareMode.WholeGallery
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DetectConfig.overlayScrim)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onDismiss() },
        contentAlignment = Alignment.TopEnd
    ) {
        Column(
            modifier = Modifier
                .statusBarsPadding()
                .padding(top = spacing.xxl, end = spacing.sm)
                .background(
                    color = DetectConfig.modeSelectorBg,
                    shape = RoundedCornerShape(DetectConfig.modeSelectorCornerRadius)
                )
                .padding(vertical = spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            options.forEach { mode ->
                TextButton(
                    onClick = { onModeSelected(mode) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.md)
                ) {
                    Text(
                        text = mode.compareModeDisplayLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (mode == currentMode) MaterialTheme.colorScheme.primary
                                else Color.White
                    )
                }
            }
        }
    }
}
