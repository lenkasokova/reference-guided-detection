/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.viewmodel.gallery

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cz.vut.oneshotdetector.di.AppContainer
import cz.vut.oneshotdetector.model.data.gallery.GalleryImage
import cz.vut.oneshotdetector.model.data.gallery.GalleryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class GalleryViewModel(
    private val galleryRepository: GalleryRepository
) : ViewModel() {
    val galleryImages: StateFlow<List<GalleryImage>> = galleryRepository.galleryImages

    val galleryImagesByClass: StateFlow<List<GalleryClass>> = galleryRepository.galleryImages
        .map { images ->
            images.groupBy { it.label }
                .map { (label, imgs) -> GalleryClass(label, imgs) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    suspend fun updateImageMetadata(uri: String, label: String, description: String): Boolean =
        galleryRepository.updateImageMetadata(uri = uri, label = label, description = description)

    suspend fun deleteImage(uri: String): Boolean =
        galleryRepository.deleteImage(uri)

    suspend fun saveImageFromUri(sourceUri: Uri, label: String, description: String): Result<GalleryImage> =
        galleryRepository.saveImageFromUri(sourceUri, label, description)

    suspend fun cropImage(
        uri: String,
        normLeft: Float,
        normTop: Float,
        normRight: Float,
        normBottom: Float,
        label: String,
        description: String
    ): Result<String> = galleryRepository.cropImage(uri, normLeft, normTop, normRight, normBottom, label, description)

    suspend fun recomputeAllEmbeddings(
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Int = galleryRepository.recomputeAllEmbeddings(onProgress)

    companion object {
        fun factory(context: Context) = viewModelFactory {
            initializer {
                GalleryViewModel(AppContainer.get(context).galleryRepository)
            }
        }
    }
}