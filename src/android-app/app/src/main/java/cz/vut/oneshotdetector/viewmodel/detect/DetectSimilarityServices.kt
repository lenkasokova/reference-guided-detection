/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.viewmodel.detect

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.core.net.toUri
import cz.vut.oneshotdetector.model.data.gallery.GalleryImage
import cz.vut.oneshotdetector.model.inference.Detection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SimilarityComparisonResult(
    val score: Float?,
    val status: String
)

/** Best gallery match for one detection box. */
data class DetectionMatch(
    val detection: Detection,
    val galleryLabel: String,
    val score: Float
)

data class GalleryComparisonResult(
    val bestImage: GalleryImage?,
    val bestScore: Float?,
    val status: String,
    /** ROI crop with the best score. Only used for ROI comparisons. */
    val bestRoiCrop: Bitmap? = null,
    /** Best gallery match for each detection. Only used for ROI comparisons. */
    val detectionMatches: List<DetectionMatch> = emptyList()
)

interface ReferenceSimilarityService {
    suspend fun compareCapturedToReference(
        capturedImageUri: String,
        referenceEmbedding: ByteArray
    ): SimilarityComparisonResult

    suspend fun compareCapturedRoisToReference(
        capturedImageUri: String,
        detections: List<Detection>,
        referenceEmbedding: ByteArray
    ): SimilarityComparisonResult

    suspend fun compareFrameToReference(
        frameBitmap: Bitmap,
        referenceEmbedding: ByteArray
    ): SimilarityComparisonResult

    suspend fun compareFrameRoisToReference(
        frameBitmap: Bitmap,
        detections: List<Detection>,
        referenceEmbedding: ByteArray
    ): SimilarityComparisonResult
}

interface GallerySimilarityService {
    suspend fun compareCapturedToGallery(capturedImageUri: String): GalleryComparisonResult
    suspend fun compareCapturedRoisToGallery(
        capturedImageUri: String,
        detections: List<Detection>
    ): GalleryComparisonResult
    suspend fun compareFrameToGallery(frameBitmap: Bitmap): GalleryComparisonResult
    suspend fun compareFrameRoisToGallery(
        frameBitmap: Bitmap,
        detections: List<Detection>
    ): GalleryComparisonResult
}

class DefaultReferenceSimilarityService(
    private val embeddingEngine: EmbeddingEngine,
    private val imageDecoder: ImageDecoder
) : ReferenceSimilarityService {

    override suspend fun compareCapturedToReference(
        capturedImageUri: String,
        referenceEmbedding: ByteArray
    ): SimilarityComparisonResult = withContext(Dispatchers.Default) {
        val sourceBitmap = imageDecoder.decode(capturedImageUri.toUri())
            ?: return@withContext SimilarityComparisonResult(
                score = null,
                status = "Failed to decode captured image."
            )
        compareBitmapToReference(sourceBitmap, referenceEmbedding)
    }

    override suspend fun compareCapturedRoisToReference(
        capturedImageUri: String,
        detections: List<Detection>,
        referenceEmbedding: ByteArray
    ): SimilarityComparisonResult = withContext(Dispatchers.Default) {
        val sourceBitmap = imageDecoder.decode(capturedImageUri.toUri())
            ?: return@withContext SimilarityComparisonResult(
                score = null,
                status = "Failed to decode captured image."
            )
        compareRoisToReference(sourceBitmap, detections, referenceEmbedding)
    }

    override suspend fun compareFrameToReference(
        frameBitmap: Bitmap,
        referenceEmbedding: ByteArray
    ): SimilarityComparisonResult = withContext(Dispatchers.Default) {
        compareBitmapToReference(frameBitmap, referenceEmbedding)
    }

    override suspend fun compareFrameRoisToReference(
        frameBitmap: Bitmap,
        detections: List<Detection>,
        referenceEmbedding: ByteArray
    ): SimilarityComparisonResult = withContext(Dispatchers.Default) {
        compareRoisToReference(frameBitmap, detections, referenceEmbedding)
    }

    private fun compareBitmapToReference(
        sourceBitmap: Bitmap,
        referenceEmbedding: ByteArray
    ): SimilarityComparisonResult {
        val notes = mutableListOf<String>()
        val sourceEmbedding = embeddingEngine.computeEmbedding(sourceBitmap, notes)
            ?: return SimilarityComparisonResult(
                score = null,
                status = notes.lastOrNull() ?: "Failed to compute source embedding."
            )
        val referenceVector = embeddingEngine.decodeStoredEmbedding(referenceEmbedding)
            ?: return SimilarityComparisonResult(
                score = null,
                status = "Reference embedding is invalid."
            )
        val score = embeddingEngine.cosineSimilarity(sourceEmbedding, referenceVector)
            ?: return SimilarityComparisonResult(
                score = null,
                status = "Failed to compute similarity."
            )
        return SimilarityComparisonResult(
            score = score,
            status = "Similarity updated."
        )
    }

    private fun compareRoisToReference(
        sourceBitmap: Bitmap,
        detections: List<Detection>,
        referenceEmbedding: ByteArray
    ): SimilarityComparisonResult {
        if (detections.isEmpty()) {
            return SimilarityComparisonResult(
                score = null,
                status = "No ROI available for comparison."
            )
        }

        val referenceVector = embeddingEngine.decodeStoredEmbedding(referenceEmbedding)
            ?: return SimilarityComparisonResult(
                score = null,
                status = "Reference embedding is invalid."
            )

        var bestScore: Float? = null
        var bestRoiLabel: String? = null

        detections.forEachIndexed { _, detection ->
            val roiBitmap = cropDetectionBitmap(sourceBitmap, detection) ?: return@forEachIndexed
            val notes = mutableListOf<String>()
            val sourceEmbedding = embeddingEngine.computeEmbedding(roiBitmap, notes) ?: return@forEachIndexed
            val score = embeddingEngine.cosineSimilarity(sourceEmbedding, referenceVector)
                ?: return@forEachIndexed
            val currentBest = bestScore
            if (currentBest == null || score > currentBest) {
                bestScore = score
                bestRoiLabel = detection.label.ifBlank { "obj" }
            }
        }

        return if (bestScore != null && bestRoiLabel != null) {
            SimilarityComparisonResult(
                score = bestScore,
                status = "Best ROI label: $bestRoiLabel"
            )
        } else {
            SimilarityComparisonResult(
                score = null,
                status = "No comparable ROI embeddings found."
            )
        }
    }
}

class DefaultGallerySimilarityService(
    private val embeddingEngine: EmbeddingEngine,
    private val imageDecoder: ImageDecoder,
    private val galleryDataSource: GalleryDataSource
) : GallerySimilarityService {

    private val galleryEmbeddingCache = GalleryEmbeddingCache(embeddingEngine)

    override suspend fun compareCapturedToGallery(capturedImageUri: String): GalleryComparisonResult =
        withContext(Dispatchers.Default) {
            val sourceBitmap = imageDecoder.decode(capturedImageUri.toUri())
                ?: return@withContext GalleryComparisonResult(
                    bestImage = null,
                    bestScore = null,
                    status = "Failed to decode captured image."
                )
            compareBitmapToGallery(sourceBitmap)
        }

    override suspend fun compareCapturedRoisToGallery(
        capturedImageUri: String,
        detections: List<Detection>
    ): GalleryComparisonResult = withContext(Dispatchers.Default) {
        val sourceBitmap = imageDecoder.decode(capturedImageUri.toUri())
            ?: return@withContext GalleryComparisonResult(
                bestImage = null,
                bestScore = null,
                status = "Failed to decode captured image."
            )
        compareRoisToGallery(sourceBitmap, detections)
    }

    override suspend fun compareFrameToGallery(frameBitmap: Bitmap): GalleryComparisonResult =
        withContext(Dispatchers.Default) {
            compareBitmapToGallery(frameBitmap)
        }

    override suspend fun compareFrameRoisToGallery(
        frameBitmap: Bitmap,
        detections: List<Detection>
    ): GalleryComparisonResult = withContext(Dispatchers.Default) {
        compareRoisToGallery(frameBitmap, detections)
    }

    private suspend fun compareBitmapToGallery(sourceBitmap: Bitmap): GalleryComparisonResult {
        val images = galleryDataSource.getImages()
        if (images.isEmpty()) {
            return GalleryComparisonResult(
                bestImage = null,
                bestScore = null,
                status = "Gallery is empty."
            )
        }

        val notes = mutableListOf<String>()
        val sourceEmbedding = embeddingEngine.computeEmbedding(sourceBitmap, notes)
            ?: return GalleryComparisonResult(
                bestImage = null,
                bestScore = null,
                status = notes.lastOrNull() ?: "Failed to compute source embedding."
            )

        val galleryVectors = galleryEmbeddingCache.get(images, galleryDataSource)
        if (galleryVectors.isEmpty()) {
            return GalleryComparisonResult(
                bestImage = null,
                bestScore = null,
                status = "No comparable embeddings found in gallery."
            )
        }

        val byClass = galleryVectors.groupBy { it.image.label }
        var bestImage: GalleryImage? = null
        var bestScore: Float? = null

        byClass.forEach { (_, classVectors) ->
            val scores = classVectors.map { dotProduct(sourceEmbedding, it.embedding) }
            val score = classScore(scores)
            if (bestScore == null || score > bestScore!!) {
                bestScore = score
                bestImage = classVectors[scores.indices.maxByOrNull { scores[it] }!!].image
            }
        }

        if (bestImage == null || bestScore == null) {
            return GalleryComparisonResult(
                bestImage = null,
                bestScore = null,
                status = "No comparable embeddings found in gallery."
            )
        }

        return GalleryComparisonResult(
            bestImage = bestImage,
            bestScore = bestScore,
            status = "Best gallery match: ${bestImage.label}"
        )
    }

    private fun compareRoisToGalleryOptimized(
        sourceBitmap: Bitmap,
        detections: List<Detection>,
        galleryVectors: List<CachedGalleryEmbedding>
    ): GalleryComparisonResult {
        var bestImage: GalleryImage? = null
        var bestScore: Float? = null
        var bestRoiCrop: Bitmap? = null

        val byClass = galleryVectors.groupBy { it.image.label }
        val detectionMatches = mutableListOf<DetectionMatch>()

        detections.forEach { detection ->
            val roiBitmap = cropDetectionBitmap(sourceBitmap, detection) ?: return@forEach
            val notes = mutableListOf<String>()
            val roiEmbedding = embeddingEngine.computeEmbedding(roiBitmap, notes) ?: return@forEach

            // Find the best gallery match for this detection.
            var detBestScore: Float? = null
            var detBestImage: GalleryImage? = null

            byClass.forEach { (_, classVectors) ->
                val scores = classVectors.map { dotProduct(roiEmbedding, it.embedding) }
                val score = classScore(scores)
                if (detBestScore == null || score > detBestScore!!) {
                    detBestScore = score
                    detBestImage = classVectors[scores.indices.maxByOrNull { scores[it] }!!].image
                }
                if (score >= SIMILARITY_THRESHOLD && (bestScore == null || score > bestScore!!)) {
                    bestScore = score
                    bestImage = detBestImage
                    bestRoiCrop = roiBitmap
                }
            }

            if (detBestScore != null && detBestScore!! >= SIMILARITY_THRESHOLD && detBestImage != null) {
                detectionMatches.add(DetectionMatch(detection, detBestImage!!.label, detBestScore!!))
            }
        }

        if (bestImage == null || bestScore == null) {
            return GalleryComparisonResult(
                bestImage = null,
                bestScore = null,
                status = "No comparable ROI embeddings found."
            )
        }

        return GalleryComparisonResult(
            bestImage = bestImage,
            bestScore = bestScore,
            status = "Best match: ${bestImage!!.label}",
            bestRoiCrop = bestRoiCrop,
            detectionMatches = detectionMatches
        )
    }

    private suspend fun compareRoisToGallery(
        sourceBitmap: Bitmap,
        detections: List<Detection>
    ): GalleryComparisonResult {
        if (detections.isEmpty()) {
            return GalleryComparisonResult(
                bestImage = null,
                bestScore = null,
                status = "No ROI available for comparison."
            )
        }

        val images = galleryDataSource.getImages()
        if (images.isEmpty()) {
            return GalleryComparisonResult(
                bestImage = null,
                bestScore = null,
                status = "Gallery is empty."
            )
        }

        val galleryVectors = galleryEmbeddingCache.get(images, galleryDataSource)
        if (galleryVectors.isEmpty()) {
            return GalleryComparisonResult(
                bestImage = null,
                bestScore = null,
                status = "No comparable embeddings found in gallery."
            )
        }

        return compareRoisToGalleryOptimized(sourceBitmap, detections, galleryVectors)
    }

}

private data class CachedGalleryEmbedding(
    val image: GalleryImage,
    val embedding: FloatArray
)

private class GalleryEmbeddingCache(
    private val embeddingEngine: EmbeddingEngine
) {
    suspend fun get(
        images: List<GalleryImage>,
        galleryDataSource: GalleryDataSource
    ): List<CachedGalleryEmbedding> {
        return images.mapNotNull { image ->
            val embedding = galleryDataSource.getEmbedding(image.uri) ?: return@mapNotNull null
            val vector = embeddingEngine.decodeStoredEmbedding(embedding) ?: return@mapNotNull null
            CachedGalleryEmbedding(image = image, embedding = vector)
        }
    }
}

private const val TOP_K = 3
private const val CONSISTENCY_BONUS = 0.75f

/** Smallest cosine similarity that still counts as a valid ROI match. */
private const val SIMILARITY_THRESHOLD = 0.64f

/**
 * Final class score based on the best score and the top-k average.
 */
private fun classScore(scores: List<Float>): Float {

    if (scores.isEmpty()) return 0f
    val max = scores.max()
    if (scores.size == 1) return max
    val topKMean = scores.sortedDescending().take(TOP_K).average().toFloat()
    return max + CONSISTENCY_BONUS * (topKMean - max)
}


private fun dotProduct(a: FloatArray, b: FloatArray): Float {
    val size = minOf(a.size, b.size)
    var dot = 0f
    for (i in 0 until size) {
        dot += a[i] * b[i]
    }
    return dot
}

private fun cropDetectionBitmap(
    sourceBitmap: Bitmap,
    detection: Detection
): Bitmap? {
    val box = detection.box
    if (box.size < 4) return null

    val width = sourceBitmap.width
    val height = sourceBitmap.height
    if (width <= 0 || height <= 0) return null

    val isNormalized = box.all { it in 0f..1f }
    val top = if (isNormalized) (box[0] * height).toInt() else box[0].toInt()
    val left = if (isNormalized) (box[1] * width).toInt() else box[1].toInt()
    val bottom = if (isNormalized) (box[2] * height).toInt() else box[2].toInt()
    val right = if (isNormalized) (box[3] * width).toInt() else box[3].toInt()

    val safeRect = Rect(
        left.coerceIn(0, width - 1),
        top.coerceIn(0, height - 1),
        right.coerceIn(1, width),
        bottom.coerceIn(1, height)
    )
    if (safeRect.width() <= 1 || safeRect.height() <= 1) return null

    return Bitmap.createBitmap(
        sourceBitmap,
        safeRect.left,
        safeRect.top,
        safeRect.width(),
        safeRect.height()
    )
}
