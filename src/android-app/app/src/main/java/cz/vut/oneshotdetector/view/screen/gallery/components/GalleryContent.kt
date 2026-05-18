/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.screen.gallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.vut.oneshotdetector.model.data.gallery.GalleryImage
import cz.vut.oneshotdetector.view.components.OutlinedSelectableCard
import cz.vut.oneshotdetector.view.theme.LocalDimens
import cz.vut.oneshotdetector.view.theme.Spacing
import cz.vut.oneshotdetector.viewmodel.gallery.GalleryClass

@Composable
fun GalleryContent(
    hasImages: Boolean,
    classes: List<GalleryClass>,
    filterQuery: String,
    spacing: Spacing,
    onOpenImage: (GalleryImage) -> Unit,
) {
    when {
        !hasImages -> {
            Text("No images saved yet.", modifier = Modifier.padding(horizontal = spacing.md))
        }
        classes.isEmpty() -> {
            Text(
                "No classes match \"$filterQuery\".",
                modifier = Modifier.padding(horizontal = spacing.md)
            )
        }
        else -> {
            LazyColumn(
                modifier = Modifier.padding(horizontal = spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.lg)
            ) {
                items(classes, key = { it.label }) { galleryClass ->
                    ClassSection(
                        galleryClass = galleryClass,
                        spacing = spacing,
                        onOpenImage = onOpenImage,
                    )
                }
            }
        }
    }
}

@Composable
private fun ClassSection(
    galleryClass: GalleryClass,
    spacing: Spacing,
    onOpenImage: (GalleryImage) -> Unit,
) {
    val dimens = LocalDimens.current
    val thumbnailWidth = 90.dp

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = galleryClass.label.ifBlank { "(unlabelled)" },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${galleryClass.images.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiary.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = spacing.sm),
            )
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            items(galleryClass.images, key = { it.uri }) { image ->
                OutlinedSelectableCard(
                    onClick = { onOpenImage(image) },
                    modifier = Modifier
                        .width(thumbnailWidth)
                        .aspectRatio(dimens.galleryThumbnailAspectRatio),
                ) {
                    GalleryThumbnail(uri = image.uri, label = image.description)
                }
            }
        }
    }
}