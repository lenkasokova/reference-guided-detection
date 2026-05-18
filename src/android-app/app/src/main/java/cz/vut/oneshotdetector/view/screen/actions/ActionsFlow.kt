/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.screen.actions

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import cz.vut.oneshotdetector.view.state.cropImageToFile
import cz.vut.oneshotdetector.viewmodel.gallery.GalleryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun PhotoActionsFlow(
    imageUri: String,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: GalleryViewModel = viewModel(factory = GalleryViewModel.factory(context))
    val galleryImages by viewModel.galleryImages.collectAsState()
    val existingLabels = remember(galleryImages) { galleryImages.map { it.label }.distinct() }
    var currentUri by remember { mutableStateOf(Uri.parse(imageUri)) }
    var label by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var showCropScreen by remember { mutableStateOf(false) }
    var showRemoveBgScreen by remember { mutableStateOf(false) }
    var bgRemovedFile by remember { mutableStateOf<File?>(null) }
    var bgRemovedUri by remember { mutableStateOf<Uri?>(null) }

    DisposableEffect(Unit) {
        onDispose { bgRemovedFile?.delete() }
    }

    val displayUri = bgRemovedUri ?: currentUri

    when {
        showCropScreen -> CropPhotoScreen(
            photoUri = currentUri,
            onCrop = { normLeft, normTop, normRight, normBottom ->
                scope.launch {
                    val file = withContext(Dispatchers.IO) {
                        cropImageToFile(context, currentUri, normLeft, normTop, normRight, normBottom)
                    }
                    if (file != null) {
                        currentUri = Uri.fromFile(file)
                        val old = bgRemovedFile
                        bgRemovedFile = null
                        bgRemovedUri = null
                        withContext(Dispatchers.IO) { old?.delete() }
                    }
                    showCropScreen = false
                }
            },
            onCancel = { showCropScreen = false }
        )
        showRemoveBgScreen -> RemoveBackgroundScreen(
            photoUri = currentUri,
            onApply = { file ->
                val old = bgRemovedFile
                bgRemovedFile = file
                bgRemovedUri = Uri.fromFile(file)
                scope.launch { withContext(Dispatchers.IO) { old?.delete() } }
                showRemoveBgScreen = false
            },
            onCancel = { showRemoveBgScreen = false }
        )
        else -> PhotoActionsScreen(
            photoUri = displayUri,
            label = label,
            description = description,
            onLabelChange = { label = it },
            onDescriptionChange = { description = it },
            onRetake = onBack,
            onCropPhoto = { showCropScreen = true },
            onRemoveBackground = { showRemoveBgScreen = true },
            onSave = {
                scope.launch {
                    val result = viewModel.saveImageFromUri(
                        sourceUri = bgRemovedUri ?: currentUri,
                        label = label,
                        description = description
                    )
                    if (result.isSuccess) onSaved()
                }
            },
            onBack = onBack,
            existingLabels = existingLabels
        )
    }
}

@Composable
fun DetailFlow(
    plantId: String,
    onEdit: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: GalleryViewModel = viewModel(factory = GalleryViewModel.factory(context))
    val galleryImages by viewModel.galleryImages.collectAsState()
    val image = galleryImages.firstOrNull { it.uri == plantId }

    if (image == null) {
        Text("Loading...")
        return
    }

    PhotoPreviewScreen(
        image = image,
        label = image.label,
        description = image.description,
        onEdit = onEdit,
        onDelete = {
            scope.launch {
                val deleted = viewModel.deleteImage(plantId)
                if (deleted) onBack()
            }
        },
        onBack = onBack
    )
}
