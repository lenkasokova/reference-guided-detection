/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.screen.actions

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import cz.vut.oneshotdetector.view.state.cropImageToFile
import cz.vut.oneshotdetector.viewmodel.gallery.GalleryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun EditFlow(
    plantId: String,
    pendingCropRect: FloatArray?,
    onPendingCropRectConsumed: () -> Unit,
    onCropPhoto: () -> Unit,
    onSaved: (newPlantId: String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: GalleryViewModel = viewModel(factory = GalleryViewModel.factory(context))
    val galleryImages by viewModel.galleryImages.collectAsState()
    val image = galleryImages.firstOrNull { it.uri == plantId }
    var statusText by remember { mutableStateOf<String?>(null) }
    var label by remember(plantId) { mutableStateOf("") }
    var description by remember(plantId) { mutableStateOf("") }

    LaunchedEffect(image) {
        if (image != null && label.isEmpty()) {
            label = image.label
            description = image.description
        }
    }

    var tempPreviewFile by remember { mutableStateOf<File?>(null) }
    var previewUri by remember { mutableStateOf<Uri?>(null) }
    var bgRemovedFile by remember { mutableStateOf<File?>(null) }
    var bgRemovedUri by remember { mutableStateOf<Uri?>(null) }
    var showRemoveBgScreen by remember { mutableStateOf(false) }

    LaunchedEffect(pendingCropRect) {
        val oldFile = tempPreviewFile
        val oldBg = bgRemovedFile
        bgRemovedFile = null
        bgRemovedUri = null
        withContext(Dispatchers.IO) { oldBg?.delete() }

        if (pendingCropRect != null) {
            val file = withContext(Dispatchers.IO) {
                oldFile?.delete()
                cropImageToFile(
                    context, plantId.toUri(),
                    pendingCropRect[0], pendingCropRect[1],
                    pendingCropRect[2], pendingCropRect[3],
                    fileNamePrefix = "crop_preview_"
                )
            }
            tempPreviewFile = file
            previewUri = file?.let { Uri.fromFile(it) }
        } else {
            withContext(Dispatchers.IO) { oldFile?.delete() }
            tempPreviewFile = null
            previewUri = null
        }
    }

    DisposableEffect(plantId) {
        onDispose {
            tempPreviewFile?.delete()
            bgRemovedFile?.delete()
        }
    }

    if (image == null) {
        Text("Loading...")
        return
    }

    val displayUri = bgRemovedUri ?: previewUri ?: image.uri.toUri()

    if (showRemoveBgScreen) {
        RemoveBackgroundScreen(
            photoUri = previewUri ?: image.uri.toUri(),
            onApply = { file ->
                val old = bgRemovedFile
                bgRemovedFile = file
                bgRemovedUri = Uri.fromFile(file)
                scope.launch { withContext(Dispatchers.IO) { old?.delete() } }
                showRemoveBgScreen = false
            },
            onCancel = { showRemoveBgScreen = false }
        )
        return
    }

    PhotoEditScreen(
        photoUri = displayUri,
        label = label,
        description = description,
        onLabelChange = { label = it },
        onDescriptionChange = { description = it },
        existingLabels = remember(galleryImages) { galleryImages.map { it.label }.distinct() },
        statusText = statusText,
        onSave = {
            scope.launch {
                val bgUri = bgRemovedUri
                val cropRect = pendingCropRect
                when {
                    bgUri != null -> {
                        val result = viewModel.saveImageFromUri(bgUri, label, description)
                        if (result.isSuccess) {
                            viewModel.deleteImage(plantId)
                            onPendingCropRectConsumed()
                            onSaved(result.getOrThrow().uri)
                        }
                    }
                    cropRect != null -> {
                        val result = viewModel.cropImage(
                            uri = plantId,
                            normLeft = cropRect[0], normTop = cropRect[1],
                            normRight = cropRect[2], normBottom = cropRect[3],
                            label = label,
                            description = description
                        )
                        if (result.isSuccess) {
                            onPendingCropRectConsumed()
                            onSaved(result.getOrThrow())
                        } else {
                            statusText = "Save failed."
                        }
                    }
                    else -> {
                        val updated = viewModel.updateImageMetadata(
                            uri = plantId,
                            label = label,
                            description = description
                        )
                        if (updated) onSaved(plantId)
                    }
                }
            }
        },
        onDelete = {
            scope.launch {
                if (viewModel.deleteImage(plantId)) onBack()
            }
        },
        onCropPhoto = onCropPhoto,
        onRemoveBackground = { showRemoveBgScreen = true },
        onBack = onBack
    )
}
