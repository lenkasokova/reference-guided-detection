/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.screen.detect.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cz.vut.oneshotdetector.model.data.gallery.GalleryImage
import cz.vut.oneshotdetector.view.components.rememberProgressiveGridState
import cz.vut.oneshotdetector.view.screen.gallery.components.GalleryThumbnail

@Composable
fun ReferenceImagePickerDialog(
    images: List<GalleryImage>,
    onImageSelected: (GalleryImage) -> Unit,
    onDismiss: () -> Unit
) {
    var filterQuery by remember { mutableStateOf("") }
    val filteredImages = remember(images, filterQuery) {
        val query = filterQuery.trim()
        if (query.isEmpty()) {
            images
        } else {
            images.filter { image ->
                image.label.contains(query, ignoreCase = true) ||
                    image.description.contains(query, ignoreCase = true)
            }
        }
    }
    val progressiveGrid = rememberProgressiveGridState(
        items = filteredImages,
        initialCount = 2,
        smallChunkSize = 2,
        largeChunkSize = 20,
        switchToLargeChunksAt = 20,
        loadTriggerOffset = 4
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Reference Image") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = filterQuery,
                    onValueChange = { filterQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search gallery") }
                )
                when {
                    images.isEmpty() -> {
                        Text("No images saved in OneShotDetector gallery yet.")
                    }
                    filteredImages.isEmpty() -> {
                        Text("No images match \"$filterQuery\".")
                    }
                    else -> {
                        LazyVerticalGrid(
                            state = progressiveGrid.gridState,
                            columns = GridCells.Fixed(3),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.height(300.dp)
                        ) {
                            items(progressiveGrid.visibleItems, key = { it.uri }) { image ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .clickable { onImageSelected(image) }
                                ) {
                                    GalleryThumbnail(uri = image.uri)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
