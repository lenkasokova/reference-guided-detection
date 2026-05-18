/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.screen.detect.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import cz.vut.oneshotdetector.viewmodel.detect.DetectViewModel
import cz.vut.oneshotdetector.view.screen.detect.DetectConfig
import cz.vut.oneshotdetector.view.state.decodeImageWithExif
import cz.vut.oneshotdetector.view.theme.LocalSpacing

/**
 * Bottom results panel shown beneath the camera / image preview.
 *
 * Layout:
 * - Optional [statusText] line at the top.
 * - Two equal columns below:
 *   - **Left** – [previewImage]: the segmented mask, selected ROI crop, fixed-box crop, or the
 *     full captured image depending on the active model type.
 *   - **Right** – reserved for a future comparison / similar-image result.
 */
@Composable
internal fun DetectResultPanel(
    statusText: String?,
    previewImage: ImageBitmap?,
    galleryMatch: DetectViewModel.DetectGalleryMatch?,
    referenceImageUri: String?,
    referenceImageLabel: String?,
    similarityScore: Float?,
    onImageClicked: ((uri: String) -> Unit)? = null,
    onDetectedImageClicked: (() -> Unit)? = null,
    showReferenceImage: Boolean = true,
    modifier: Modifier = Modifier
) {
    val spacing = LocalSpacing.current
    val context = LocalContext.current

    val matchBitmap by produceState<ImageBitmap?>(null, galleryMatch?.imageUri) {
        value = galleryMatch?.let { match ->
            runCatching {
                decodeImageWithExif(context, match.imageUri.toUri())?.asImageBitmap()
            }.getOrNull()
        }
    }
    val referenceBitmap by produceState<ImageBitmap?>(null, referenceImageUri) {
        value = referenceImageUri?.let { uri ->
            runCatching {
                decodeImageWithExif(context, uri.toUri())?.asImageBitmap()
            }.getOrNull()
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        statusText?.let {
            Text(
                text = it,
                color = DetectConfig.onBackground.copy(alpha = DetectConfig.STATUS_TEXT_ALPHA),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.md, vertical = spacing.xs)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            // Left column — active crop result
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = spacing.sm, end = spacing.sm, top = spacing.xs, bottom = spacing.xs),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Detected image",
                    style = MaterialTheme.typography.labelSmall,
                    color = DetectConfig.onBackground.copy(alpha = DetectConfig.STATUS_TEXT_ALPHA)
                )
                Spacer(modifier = Modifier.height(spacing.xxs))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(spacing.xs))
                        .background(DetectConfig.onBackground.copy(alpha = DetectConfig.RESULT_CARD_TINT_ALPHA))
                        .then(
                            if (onDetectedImageClicked != null && previewImage != null)
                                Modifier.clickable { onDetectedImageClicked() }
                            else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (previewImage != null) {
                        Image(
                            bitmap = previewImage,
                            contentDescription = "Preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(spacing.xs)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(spacing.xxs))
                Text(
                    text = "Tap to save",
                    style = MaterialTheme.typography.labelSmall,
                    color = DetectConfig.onBackground.copy(alpha = DetectConfig.STATUS_TEXT_ALPHA)
                )
            }
            // Right column — best gallery match or selected reference image
            if (!showReferenceImage) return@Row
            val match = galleryMatch
            val bitmap = matchBitmap
            val selectedReferenceBitmap = referenceBitmap
            val rightUri: String? = when {
                match != null -> match.imageUri
                referenceImageUri != null -> referenceImageUri
                else -> null
            }
            val isClickable = onImageClicked != null && rightUri != null
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = spacing.sm, end = spacing.sm, top = spacing.xs, bottom = spacing.xs),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Reference image",
                    style = MaterialTheme.typography.labelSmall,
                    color = DetectConfig.onBackground.copy(alpha = DetectConfig.STATUS_TEXT_ALPHA)
                )
                Spacer(modifier = Modifier.height(spacing.xxs))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(spacing.xs))
                        .background(DetectConfig.onBackground.copy(alpha = DetectConfig.RESULT_CARD_TINT_ALPHA))
                        .then(
                            if (isClickable)
                                Modifier.clickable { onImageClicked!!(rightUri!!) }
                            else Modifier
                        )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(spacing.xs),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (match != null && bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = match.label,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.weight(1f).fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(spacing.xs))
                            Text(
                                text = match.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = DetectConfig.onBackground,
                                maxLines = 1
                            )
                            Text(
                                text = "%.3f".format(match.score),
                                style = MaterialTheme.typography.bodySmall,
                                color = DetectConfig.onBackground.copy(alpha = DetectConfig.STATUS_TEXT_ALPHA)
                            )
                        } else if (selectedReferenceBitmap != null && referenceImageUri != null) {
                            Image(
                                bitmap = selectedReferenceBitmap,
                                contentDescription = referenceImageLabel ?: "Reference image",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.weight(1f).fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(spacing.xs))
                            referenceImageLabel?.takeIf { it.isNotBlank() }?.let { label ->
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = DetectConfig.onBackground,
                                    maxLines = 1
                                )
                            }
                            similarityScore?.let { score ->
                                Text(
                                    text = "%.3f".format(score),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = DetectConfig.onBackground.copy(alpha = DetectConfig.STATUS_TEXT_ALPHA)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(spacing.xxs))
                Text(
                    text = "Tap to see details",
                    style = MaterialTheme.typography.labelSmall,
                    color = DetectConfig.onBackground.copy(alpha = DetectConfig.STATUS_TEXT_ALPHA)
                )
            }
        }
    }
}
