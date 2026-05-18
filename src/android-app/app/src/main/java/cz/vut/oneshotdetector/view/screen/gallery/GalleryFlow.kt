/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.screen.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import cz.vut.oneshotdetector.viewmodel.gallery.GalleryViewModel

@Composable
fun GalleryFlow(
    onAddImage: () -> Unit,
    onOpenImage: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: GalleryViewModel = viewModel(factory = GalleryViewModel.factory(context))
    val galleryImages by viewModel.galleryImages.collectAsState()
    val galleryClasses by viewModel.galleryImagesByClass.collectAsState()

    GalleryScreen(
        hasImages = galleryImages.isNotEmpty(),
        classes = galleryClasses,
        onAddImage = onAddImage,
        onOpenImage = { image -> onOpenImage(image.uri) },
        onBack = onBack
    )
}