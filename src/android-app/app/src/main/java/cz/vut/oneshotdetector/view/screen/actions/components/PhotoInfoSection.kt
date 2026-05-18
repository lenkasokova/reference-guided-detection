/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.screen.actions.components

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cz.vut.oneshotdetector.model.inference.Detection
import cz.vut.oneshotdetector.view.components.OutlinedBox
import cz.vut.oneshotdetector.view.state.PhotoPreview
import cz.vut.oneshotdetector.view.theme.LocalDimens
import cz.vut.oneshotdetector.view.theme.LocalSpacing

@Composable
fun PhotoInfoSection(
    photoUri: Uri?,
    detections: List<Detection>,
    label: String,
    description: String,
    onEdit: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val dimens = LocalDimens.current

    OutlinedBox(
        modifier = Modifier.aspectRatio(dimens.photoPreviewAspectRatio),
        innerPadding = spacing.sm
    ) {
        if (photoUri != null) {
            PhotoPreview(uri = photoUri, detections = detections)
        } else {
            Text("No photo captured yet.", color = MaterialTheme.colorScheme.onTertiary)
        }
    }

    Spacer(Modifier.height(spacing.sm))
    Text(
        text = "Label",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onTertiary,
        modifier = Modifier.clickable(onClick = onEdit),
    )
    Spacer(Modifier.height(spacing.xxs))
    OutlinedBox(modifier = Modifier.clickable(onClick = onEdit)) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onTertiary,
            modifier = Modifier.fillMaxWidth()
        )
    }

    Spacer(Modifier.height(spacing.sm))
    Text(
        text = "Description",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onTertiary,
        modifier = Modifier.clickable(onClick = onEdit),
    )
    Spacer(Modifier.height(spacing.xxs))
    OutlinedBox(modifier = Modifier.clickable(onClick = onEdit)) {
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiary,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
