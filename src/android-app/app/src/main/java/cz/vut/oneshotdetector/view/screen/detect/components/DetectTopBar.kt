/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.screen.detect.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import cz.vut.oneshotdetector.viewmodel.detect.DetectViewModel
import cz.vut.oneshotdetector.view.layout.AppBackButton
import cz.vut.oneshotdetector.view.screen.detect.DetectConfig
import cz.vut.oneshotdetector.view.screen.detect.compareModeDisplayLabel
import cz.vut.oneshotdetector.view.screen.detect.displayLabel
import cz.vut.oneshotdetector.view.theme.LocalSpacing

/**
 * Top controls row: back navigation button on the left, current-mode selector pill on the right.
 *
 * Tapping [onModeClicked] should show the [DetectModeSelector] overlay.
 */
@Composable
internal fun DetectTopBar(
    currentMode: DetectViewModel.DetectMode,
    roiDetectionTarget: DetectViewModel.RoiDetectionTarget,
    compareMode: DetectViewModel.CompareMode,
    onBack: () -> Unit,
    onModeClicked: () -> Unit,
    onRoiDetectionTargetClicked: () -> Unit,
    onCompareModeClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = spacing.sm, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppBackButton(onClick = onBack)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            val roiEnabled = currentMode == DetectViewModel.DetectMode.Roi
            IconButton(
                onClick = onRoiDetectionTargetClicked,
                enabled = roiEnabled
            ) {
                Icon(
                    imageVector = when (roiDetectionTarget) {
                        DetectViewModel.RoiDetectionTarget.Plant -> Icons.Filled.Eco
                        DetectViewModel.RoiDetectionTarget.All   -> Icons.Filled.Apps
                    },
                    contentDescription = roiDetectionTarget.displayLabel,
                    tint = if (roiEnabled) DetectConfig.onBackground
                           else DetectConfig.onBackground.copy(alpha = 0.3f)
                )
            }
            IconButton(onClick = onCompareModeClicked) {
                Icon(
                    imageVector = if (compareMode == DetectViewModel.CompareMode.WholeGallery)
                        Icons.Filled.PhotoLibrary
                    else
                        Icons.Filled.Image,
                    contentDescription = if (compareMode == DetectViewModel.CompareMode.WholeGallery)
                        "Gallery compare" else "Reference compare",
                    tint = DetectConfig.onBackground
                )
            }
            OutlinedButton(
                onClick = onModeClicked,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = DetectConfig.onBackground
                ),
                border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                    brush = SolidColor(
                        DetectConfig.onBackground.copy(alpha = DetectConfig.MODE_BORDER_ALPHA)
                    )
                )
            ) {
                Text(currentMode.displayLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
