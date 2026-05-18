/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.screen.detect.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cz.vut.oneshotdetector.view.screen.detect.DetectConfig

/**
 * Floating icon button overlaid at the bottom-centre of the preview area.
 *
 * When [isLive] is `true` the button shows a camera icon and triggers image capture.
 * When `false` it shows a refresh icon and resumes the live camera stream.
 */
@Composable
internal fun DetectActionButton(
    isLive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(DetectConfig.actionButtonSize),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = DetectConfig.onBackground.copy(
                alpha = DetectConfig.ACTION_BTN_CONTAINER_ALPHA
            ),
            contentColor = DetectConfig.onBackground
        )
    ) {
        Icon(
            imageVector = if (isLive) Icons.Filled.Camera else Icons.Filled.Refresh,
            contentDescription = if (isLive) "Capture" else "Resume live",
            modifier = Modifier.size(DetectConfig.actionIconSize)
        )
    }
}
