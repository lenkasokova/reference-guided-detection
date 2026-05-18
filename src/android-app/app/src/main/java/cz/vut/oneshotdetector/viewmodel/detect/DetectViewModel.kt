/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.viewmodel.detect

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cz.vut.oneshotdetector.di.AppContainer
import cz.vut.oneshotdetector.di.DetectViewModelDependencies
import cz.vut.oneshotdetector.model.data.gallery.GalleryImage
import cz.vut.oneshotdetector.model.inference.Detection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

class DetectViewModel(
    dependencies: DetectViewModelDependencies
) : ViewModel() {

    private val galleryRepository = dependencies.galleryRepository
    private val referenceSimilarityService = dependencies.referenceSimilarityService
    private val gallerySimilarityService = dependencies.gallerySimilarityService
    private val detectionService = dependencies.detectionService
    val segmentationService: SegmentationService = dependencies.segmentationService

    val galleryImages: StateFlow<List<GalleryImage>> =
        dependencies.galleryRepository.galleryImages

    enum class ViewMode { CameraPreview, CapturedImage, DetectedImage }
    enum class CompareMode { None, WholeGallery, Reference }
    enum class CompareTarget { WholeImage, EachRoi }
    enum class Overlay { None, ReferencePicker }
    enum class DetectMode { Roi, Segment, Crop, WholeImage }
    enum class TriggerMode { Auto, OnTap }

    enum class RoiDetectionTarget {
        Plant, All;

        fun accepts(label: String): Boolean = when (this) {
            Plant -> PLANT_LABELS.any { label.contains(it, ignoreCase = true) }
            All   -> true
        }

        companion object {
            private val PLANT_LABELS = setOf(
                "plant", "flower", "tree", "grass", "leaf", "bush", "shrub", "fern", "potted plant"
            )
        }
    }

    data class DetectGalleryMatch(
        val imageUri: String,
        val label: String,
        val score: Float
    )

    data class UiState(
        val viewMode: ViewMode,
        val compareMode: CompareMode,
        val compareTarget: CompareTarget,
        val capturedImageUri: String?,
        val referenceImageUri: String?,
        val referenceImageLabel: String?,
        val referenceImageEmbedding: ByteArray?,
        val overlay: Overlay,
        val detectMode: DetectMode,
        val triggerMode: TriggerMode,
        val roiDetectionTarget: RoiDetectionTarget,
        val detections: List<Detection>,
        val detectionSourceWidth: Int?,
        val detectionSourceHeight: Int?,
        val captureStatus: String?,
        val similarityScore: Float?,
        val similarityStatus: String?,
        val tapIndicatorNorm: Pair<Float, Float>?,
        val segmentedCrop: Bitmap?,
        val segmentStatus: String?,
        val removeBackground: Boolean,
        val detectGalleryMatch: DetectGalleryMatch?,
        val detectionMatches: List<DetectionMatch>
    )

    private val _uiState = MutableStateFlow(
        UiState(
            viewMode = ViewMode.CameraPreview,
            compareMode = CompareMode.WholeGallery,
            compareTarget = CompareTarget.WholeImage,
            capturedImageUri = null,
            referenceImageUri = null,
            referenceImageLabel = null,
            referenceImageEmbedding = null,
            overlay = Overlay.None,
            detectMode = DetectMode.Roi,
            triggerMode = TriggerMode.OnTap,
            roiDetectionTarget = RoiDetectionTarget.Plant,
            detections = emptyList(),
            detectionSourceWidth = null,
            detectionSourceHeight = null,
            captureStatus = null,
            similarityScore = null,
            similarityStatus = null,
            tapIndicatorNorm = null,
            segmentedCrop = null,
            segmentStatus = null,
            removeBackground = false,
            detectGalleryMatch = null,
            detectionMatches = emptyList()
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val state get() = _uiState.value

    private data class RoiCache(
        val uri: String,
        val detections: List<Detection>,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val captureStatus: String
    )

    private var roiCache: RoiCache? = null

    var latestLiveFrame: Bitmap? = null
    var samCapturedBitmap: Bitmap? = null
    var samCapturedBitmapUri: String? = null

    private val activeRoiService: DetectionService
        get() = when (state.detectMode) {
            DetectMode.Segment -> segmentationService
            DetectMode.Roi, DetectMode.Crop, DetectMode.WholeImage -> detectionService
        }

    fun onDetectModeSelected(type: DetectMode) {
        val clearDetections = type != DetectMode.Roi
        val leavingSegment = state.detectMode == DetectMode.Segment && type != DetectMode.Segment
        val constrainedTrigger = when (type) {
            DetectMode.Segment, DetectMode.Crop, DetectMode.Roi -> TriggerMode.OnTap
            DetectMode.WholeImage                             -> TriggerMode.Auto
        }
        _uiState.update {
            it.copy(
                detectMode = type,
                triggerMode = constrainedTrigger,
                detections = if (clearDetections) emptyList() else it.detections,
                detectionSourceWidth = if (clearDetections) null else it.detectionSourceWidth,
                detectionSourceHeight = if (clearDetections) null else it.detectionSourceHeight,
                captureStatus = if (clearDetections) null else it.captureStatus,
                tapIndicatorNorm = if (leavingSegment) null else it.tapIndicatorNorm,
            )
        }
        if (type == DetectMode.Roi) applyRoiCache()
    }

    fun onRoiDetectionTargetSelected(target: RoiDetectionTarget) {
        if (state.roiDetectionTarget == target) return
        _uiState.update { it.copy(roiDetectionTarget = target, detectGalleryMatch = null) }
        applyRoiCache()
    }

    fun updateSourceDimensions(width: Int, height: Int) {
        if (state.detectionSourceWidth == width && state.detectionSourceHeight == height) return
        _uiState.update { it.copy(detectionSourceWidth = width, detectionSourceHeight = height) }
    }

    fun onToggleRemoveBackground() {
        _uiState.update { it.copy(removeBackground = !it.removeBackground) }
    }

    fun setSegmentStatus(status: String) {
        _uiState.update { it.copy(segmentStatus = status) }
    }

    private val segmentMutex = Mutex()

    suspend fun onTapOnPreview(
        normX: Float,
        normY: Float,
        bitmap: Bitmap,
        keepTapIndicator: Boolean = true
    ) {
        if (state.detectMode != DetectMode.Segment) return
        if (!segmentMutex.tryLock()) return
        try {
            _uiState.update {
                it.copy(tapIndicatorNorm = normX to normY, segmentedCrop = null, segmentStatus = "Segmenting...")
            }
            val result = runCatching {
                segmentationService.segmentAtPoint(
                    bitmap, normX, normY, state.removeBackground,
                    onStatus = { msg -> _uiState.update { it.copy(segmentStatus = msg) } }
                )
            }.getOrNull()
            _uiState.update {
                if (result != null) {
                    it.copy(
                        tapIndicatorNorm = if (keepTapIndicator) it.tapIndicatorNorm else null,
                        segmentedCrop = result.croppedBitmap,
                        segmentStatus = "Segmented in ${result.inferenceTimeMs} ms"
                    )
                } else {
                    it.copy(
                        tapIndicatorNorm = if (keepTapIndicator) it.tapIndicatorNorm else null,
                        segmentStatus = "Segmentation failed."
                    )
                }
            }
        } finally {
            segmentMutex.unlock()
        }
    }

    suspend fun preEncodeForSegmentation(bitmap: Bitmap) {
        segmentationService.preEncodeImage(bitmap) { msg -> setSegmentStatus(msg) }
    }

    fun onCaptureSaved(uri: Uri, fileName: String) {
        samCapturedBitmap = null
        samCapturedBitmapUri = null
        roiCache = null
        val capturedImageUri = uri.toString()
        _uiState.update {
            it.copy(
                viewMode = ViewMode.CapturedImage,
                capturedImageUri = capturedImageUri,
                detections = emptyList(),
                detectGalleryMatch = null,
                detectionSourceWidth = null,
                detectionSourceHeight = null,
                captureStatus = null,
                similarityScore = null,
                similarityStatus = null
            )
        }
    }

    fun onCaptureFailed() {
        _uiState.update {
            it.copy(
                viewMode = ViewMode.CameraPreview,
                captureStatus = "Capture failed.",
                similarityScore = null,
                similarityStatus = "Capture failed."
            )
        }
    }

    fun onResumeLiveClicked() {
        samCapturedBitmap = null
        samCapturedBitmapUri = null
        roiCache = null
        _uiState.update {
            it.copy(
                viewMode = ViewMode.CameraPreview,
                capturedImageUri = null,
                detections = emptyList(),
                detectGalleryMatch = null,
                detectionSourceWidth = null,
                detectionSourceHeight = null,
                captureStatus = null,
                similarityScore = null,
                similarityStatus = "Capture an image to compare."
            )
        }
    }

    fun onCompareModeSelected(mode: CompareMode) {
        _uiState.update {
            it.copy(
                compareMode = mode,
                detectGalleryMatch = if (mode == CompareMode.Reference) null else it.detectGalleryMatch
            )
        }
    }

    fun onCompareWithWholeGallery() {
        _uiState.update { it.copy(compareMode = CompareMode.WholeGallery) }
    }

    fun onCompareWholeImageSelected() {
        _uiState.update { it.copy(compareTarget = CompareTarget.WholeImage) }
    }

    fun onCompareEachRoiSelected() {
        _uiState.update { it.copy(compareTarget = CompareTarget.EachRoi) }
    }

    fun onSelectReferenceImage() {
        _uiState.update { it.copy(compareMode = CompareMode.Reference, overlay = Overlay.ReferencePicker) }
    }

    fun onReferenceImageSelected(image: GalleryImage) {
        _uiState.update {
            it.copy(
                referenceImageUri = image.uri,
                referenceImageLabel = image.label,
                referenceImageEmbedding = null,
                compareMode = CompareMode.Reference,
                overlay = Overlay.None,
                similarityScore = null,
                similarityStatus = "Loading reference embedding..."
            )
        }
        viewModelScope.launch {
            val embedding = galleryRepository.getStoredEmbedding(image.uri)
            _uiState.update {
                if (it.referenceImageUri != image.uri) {
                    it
                } else if (embedding != null && embedding.isNotEmpty()) {
                    it.copy(referenceImageEmbedding = embedding, similarityStatus = null)
                } else {
                    it.copy(
                        referenceImageEmbedding = null,
                        similarityStatus = "Reference embedding is unavailable. Recompute gallery embeddings."
                    )
                }
            }
        }
    }

    fun onReferencePickerDismissed() {
        _uiState.update { it.copy(overlay = Overlay.None) }
    }

    suspend fun detectAndCacheRoi() {
        val targetUri = state.capturedImageUri ?: return
        if (roiCache?.uri == targetUri) {
            if (state.detectMode == DetectMode.Roi) applyRoiCache()
            return
        }
        val outcome = runCatching { detectionService.detectImage(targetUri) }
        val result = outcome.getOrNull()
        if (result == null) {
            if (state.detectMode == DetectMode.Roi) {
                _uiState.update {
                    it.copy(captureStatus = "ROI detection failed: ${outcome.exceptionOrNull()?.message ?: "unknown error"}")
                }
            }
            return
        }
        roiCache = RoiCache(
            uri = targetUri,
            detections = result.detections,
            sourceWidth = result.imageWidth,
            sourceHeight = result.imageHeight,
            captureStatus = "${result.summary} ${result.inferenceTimeMs} ms"
        )
        if (state.detectMode == DetectMode.Roi) applyRoiCache()
    }

    private fun applyRoiCache() {
        val cache = roiCache ?: return
        if (cache.uri != state.capturedImageUri) return
        val filtered = cache.detections.filter { state.roiDetectionTarget.accepts(it.label) }
        _uiState.update {
            it.copy(
                detections = filtered,
                detectionSourceWidth = cache.sourceWidth,
                detectionSourceHeight = cache.sourceHeight,
                captureStatus = cache.captureStatus,
                viewMode = if (filtered.isNotEmpty()) ViewMode.DetectedImage else ViewMode.CapturedImage
            )
        }
    }

    suspend fun compareCapturedWithReferenceIfReady() {
        if (state.compareMode != CompareMode.Reference) return
        val sourceUri = state.capturedImageUri ?: return
        val referenceEmbedding = state.referenceImageEmbedding ?: return

        val result = when (state.compareTarget) {
            CompareTarget.WholeImage -> referenceSimilarityService.compareCapturedToReference(
                capturedImageUri = sourceUri,
                referenceEmbedding = referenceEmbedding
            )
            CompareTarget.EachRoi -> referenceSimilarityService.compareCapturedRoisToReference(
                capturedImageUri = sourceUri,
                detections = state.detections,
                referenceEmbedding = referenceEmbedding
            )
        }

        _uiState.update {
            if (result.score != null) it.copy(similarityScore = result.score, similarityStatus = null)
            else it.copy(similarityScore = null, similarityStatus = result.status)
        }
    }

    suspend fun compareLiveFrameWithCurrentMode(frameBitmap: Bitmap) {
        when (state.compareMode) {
            CompareMode.None -> return
            CompareMode.Reference -> {
                val referenceEmbedding = state.referenceImageEmbedding ?: return
                val result = when (state.compareTarget) {
                    CompareTarget.WholeImage -> referenceSimilarityService.compareFrameToReference(
                        frameBitmap = frameBitmap,
                        referenceEmbedding = referenceEmbedding
                    )
                    CompareTarget.EachRoi -> referenceSimilarityService.compareFrameRoisToReference(
                        frameBitmap = frameBitmap,
                        detections = state.detections,
                        referenceEmbedding = referenceEmbedding
                    )
                }
                _uiState.update {
                    if (result.score != null) {
                        it.copy(
                            similarityScore = result.score,
                            similarityStatus = if (it.compareTarget == CompareTarget.EachRoi)
                                "${result.status} (${"%.4f".format(result.score)})"
                            else "Similarity: ${"%.4f".format(result.score)}"
                        )
                    } else {
                        it.copy(similarityScore = null, similarityStatus = result.status)
                    }
                }
            }
            CompareMode.WholeGallery -> {
                val result = when (state.compareTarget) {
                    CompareTarget.WholeImage -> gallerySimilarityService.compareFrameToGallery(frameBitmap)
                    CompareTarget.EachRoi -> gallerySimilarityService.compareFrameRoisToGallery(
                        frameBitmap = frameBitmap,
                        detections = state.detections
                    )
                }
                _uiState.update {
                    if (result.bestImage != null && result.bestScore != null) {
                        it.copy(
                            referenceImageUri = result.bestImage.uri,
                            referenceImageLabel = result.bestImage.label,
                            referenceImageEmbedding = result.bestImage.embedding,
                            similarityScore = result.bestScore,
                            similarityStatus = if (it.compareTarget == CompareTarget.EachRoi)
                                "${result.status} (${"%.4f".format(result.bestScore)})"
                            else "Similarity: ${"%.4f".format(result.bestScore)}"
                        )
                    } else {
                        it.copy(similarityScore = null, similarityStatus = result.status)
                    }
                }
            }
        }
    }

    suspend fun detectLiveFrameRoi(frameBitmap: Bitmap) {
        val outcome = runCatching { activeRoiService.detectFrame(frameBitmap) }
        val result = outcome.getOrNull()
        if (result == null) {
            _uiState.update {
                it.copy(captureStatus = "ROI detection failed: ${outcome.exceptionOrNull()?.message ?: "unknown error"}")
            }
            return
        }
        val filtered = result.detections.filter { state.roiDetectionTarget.accepts(it.label) }
        _uiState.update {
            it.copy(
                detections = filtered,
                detectionSourceWidth = result.imageWidth,
                detectionSourceHeight = result.imageHeight,
                captureStatus = if (filtered.isEmpty()) "Live ROI: no detections"
                    else "Live ROI: ${filtered.size} in ${result.inferenceTimeMs} ms"
            )
        }
    }

    /** Compares every detected ROI against the gallery and stores per-box matches.
     *  This does not change detectGalleryMatch, so the result panel stays unchanged. */
    suspend fun matchDetectedRoisToGallery(sourceBitmap: Bitmap) {
        val detections = state.detections
        if (detections.isEmpty()) return
        val result = runCatching {
            gallerySimilarityService.compareFrameRoisToGallery(sourceBitmap, detections)
        }.getOrNull() ?: return
        _uiState.update { it.copy(detectionMatches = result.detectionMatches) }
    }

    suspend fun autoSelectBestRoi(sourceBitmap: Bitmap): Bitmap? {
        val detections = state.detections
        if (detections.isEmpty()) return null
        val result = runCatching {
            gallerySimilarityService.compareFrameRoisToGallery(sourceBitmap, detections)
        }.getOrNull() ?: return null
        if (result.bestImage != null && result.bestScore != null) {
            _uiState.update {
                it.copy(
                    detectGalleryMatch = DetectGalleryMatch(
                        imageUri = result.bestImage.uri,
                        label = result.bestImage.label,
                        score = result.bestScore
                    ),
                    detectionMatches = result.detectionMatches
                )
            }
        }
        return result.bestRoiCrop
    }

    suspend fun compareCropToGallery(cropBitmap: Bitmap) {
        val result = runCatching {
            gallerySimilarityService.compareFrameToGallery(cropBitmap)
        }.getOrNull() ?: return
        if (result.bestImage != null && result.bestScore != null) {
            _uiState.update {
                it.copy(
                    detectGalleryMatch = DetectGalleryMatch(
                        imageUri = result.bestImage.uri,
                        label = result.bestImage.label,
                        score = result.bestScore
                    ),
                    similarityScore = result.bestScore,
                    similarityStatus = "Similarity: ${"%.4f".format(result.bestScore)}"
                )
            }
        }
    }

    suspend fun compareCapturedWithWholeGallery() {
        val sourceUri = state.capturedImageUri
        if (sourceUri == null) {
            _uiState.update { it.copy(similarityScore = null, similarityStatus = "Capture an image first.") }
            return
        }

        _uiState.update {
            it.copy(
                similarityScore = null,
                similarityStatus = when (it.compareTarget) {
                    CompareTarget.WholeImage -> "Comparing with whole gallery..."
                    CompareTarget.EachRoi -> "Comparing each ROI with whole gallery..."
                }
            )
        }

        val result = when (state.compareTarget) {
            CompareTarget.WholeImage -> gallerySimilarityService.compareCapturedToGallery(sourceUri)
            CompareTarget.EachRoi -> gallerySimilarityService.compareCapturedRoisToGallery(
                capturedImageUri = sourceUri,
                detections = state.detections
            )
        }

        _uiState.update {
            if (result.bestImage != null && result.bestScore != null) {
                it.copy(
                    referenceImageUri = result.bestImage.uri,
                    referenceImageLabel = result.bestImage.label,
                    referenceImageEmbedding = result.bestImage.embedding,
                    similarityScore = result.bestScore,
                    similarityStatus = if (it.compareTarget == CompareTarget.EachRoi)
                        "${result.status} (${"%.4f".format(result.bestScore)})"
                    else result.status
                )
            } else {
                it.copy(similarityScore = null, similarityStatus = result.status)
            }
        }
    }

    suspend fun compareBitmapWithReference(bitmap: Bitmap) {
        if (state.compareMode != CompareMode.Reference) return
        val referenceEmbedding = state.referenceImageEmbedding ?: return

        val result = referenceSimilarityService.compareFrameToReference(
            frameBitmap = bitmap,
            referenceEmbedding = referenceEmbedding
        )

        _uiState.update {
            if (result.score != null) {
                it.copy(similarityScore = result.score, similarityStatus = null, detectGalleryMatch = null)
            } else {
                it.copy(similarityScore = null, similarityStatus = result.status, detectGalleryMatch = null)
            }
        }
    }

    companion object {
        fun factory(context: Context) = viewModelFactory {
            initializer {
                DetectViewModel(AppContainer.get(context).detectViewModelDependencies())
            }
        }
    }
}
