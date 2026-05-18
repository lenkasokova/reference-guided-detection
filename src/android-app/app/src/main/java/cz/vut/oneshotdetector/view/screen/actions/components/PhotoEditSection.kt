/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.screen.actions.components

import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cz.vut.oneshotdetector.model.inference.Detection
import cz.vut.oneshotdetector.view.components.LabelAutocompleteField
import cz.vut.oneshotdetector.view.components.OutlinedBox
import cz.vut.oneshotdetector.view.state.PhotoPreview
import cz.vut.oneshotdetector.view.theme.LocalDimens
import cz.vut.oneshotdetector.view.theme.LocalSpacing
import cz.vut.oneshotdetector.view.theme.onTertiaryOutlinedTextFieldColors

@Composable
fun PhotoEditSection(
    photoUri: Uri?,
    detections: List<Detection>,
    label: String,
    description: String,
    onLabelChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    existingLabels: List<String> = emptyList(),
    onTap: ((normX: Float, normY: Float) -> Unit)? = null,
    tapIndicatorNorm: Pair<Float, Float>? = null,
) {
    val spacing = LocalSpacing.current
    val dimens = LocalDimens.current

    OutlinedBox(
        modifier = Modifier.aspectRatio(dimens.photoPreviewAspectRatio),
        innerPadding = spacing.sm
    ) {
        if (photoUri != null) {
            PhotoPreview(uri = photoUri, detections = detections, onTap = onTap, tapIndicatorNorm = tapIndicatorNorm)
        } else {
            Text("No photo captured yet.", color = MaterialTheme.colorScheme.onTertiary)
        }
    }

    Spacer(Modifier.height(spacing.sm))
    Text(
        text = "Label",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onTertiary
    )
    Spacer(Modifier.height(spacing.xxs))
    LabelAutocompleteField(
        value = label,
        onValueChange = onLabelChange,
        existingLabels = existingLabels,
        colors = onTertiaryOutlinedTextFieldColors()
    )
    Spacer(Modifier.height(spacing.sm))
    Text(
        text = "Description",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onTertiary
    )
    Spacer(Modifier.height(spacing.xxs))
    OutlinedTextField(
        value = description,
        onValueChange = onDescriptionChange,
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
        colors = onTertiaryOutlinedTextFieldColors()
    )
}
