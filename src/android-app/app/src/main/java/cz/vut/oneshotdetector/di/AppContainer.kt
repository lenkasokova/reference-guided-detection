/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.di

import android.content.Context
import cz.vut.oneshotdetector.model.data.gallery.GalleryImage
import cz.vut.oneshotdetector.model.data.gallery.GalleryRepository
import cz.vut.oneshotdetector.viewmodel.benchmark.BenchmarkExporter
import cz.vut.oneshotdetector.viewmodel.detect.DefaultGallerySimilarityService
import cz.vut.oneshotdetector.viewmodel.detect.DefaultReferenceSimilarityService
import cz.vut.oneshotdetector.viewmodel.detect.DetectionService
import cz.vut.oneshotdetector.viewmodel.detect.ExifImageDecoder
import cz.vut.oneshotdetector.viewmodel.detect.GalleryDataSource
import cz.vut.oneshotdetector.viewmodel.detect.GallerySimilarityService
import cz.vut.oneshotdetector.viewmodel.detect.DefaultEmbeddingEngine
import cz.vut.oneshotdetector.viewmodel.detect.ObjectDetectionService
import cz.vut.oneshotdetector.viewmodel.detect.ReferenceSimilarityService
import cz.vut.oneshotdetector.viewmodel.detect.SegmentationService
import cz.vut.oneshotdetector.viewmodel.model.ModelSelectionStore

/**
 * Holds app-wide dependencies and factories.
 */
class AppContainer private constructor(context: Context) {
    val appContext: Context = context.applicationContext

    val modelSelectionStore: ModelSelectionStore by lazy {
        ModelSelectionStore.getShared(appContext)
    }

    val galleryRepository: GalleryRepository by lazy {
        GalleryRepository.getShared(appContext)
    }

    fun benchmarkExporter(): BenchmarkExporter = BenchmarkExporter(appContext)

    fun detectViewModelDependencies(): DetectViewModelDependencies {
        val embeddingEngine = DefaultEmbeddingEngine(appContext, modelSelectionStore)
        val imageDecoder = ExifImageDecoder(appContext)
        val galleryDataSource = object : GalleryDataSource {
            override fun getImages(): List<GalleryImage> = galleryRepository.galleryImages.value
            override suspend fun getEmbedding(uri: String): ByteArray? =
                galleryRepository.getStoredEmbedding(uri)
        }

        return DetectViewModelDependencies(
            galleryRepository = galleryRepository,
            referenceSimilarityService = DefaultReferenceSimilarityService(
                embeddingEngine,
                imageDecoder
            ),
            gallerySimilarityService = DefaultGallerySimilarityService(
                embeddingEngine,
                imageDecoder,
                galleryDataSource
            ),
            detectionService = ObjectDetectionService(appContext, modelSelectionStore),
            segmentationService = SegmentationService(appContext, modelSelectionStore)
        )
    }

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context.applicationContext).also { instance = it }
            }
    }
}

data class DetectViewModelDependencies(
    val galleryRepository: GalleryRepository,
    val referenceSimilarityService: ReferenceSimilarityService,
    val gallerySimilarityService: GallerySimilarityService,
    val detectionService: DetectionService,
    val segmentationService: SegmentationService
)
