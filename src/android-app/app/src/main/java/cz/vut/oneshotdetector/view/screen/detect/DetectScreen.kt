/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.screen.detect

import android.graphics.Bitmap
import android.net.Uri
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import android.os.SystemClock
import androidx.core.net.toUri
import cz.vut.oneshotdetector.viewmodel.detect.DetectViewModel
import cz.vut.oneshotdetector.model.data.gallery.GalleryImage
import cz.vut.oneshotdetector.view.components.camera.CameraPreviewCore
import cz.vut.oneshotdetector.view.layout.AppScreen
import cz.vut.oneshotdetector.view.screen.detect.components.DetectActionButton
import cz.vut.oneshotdetector.view.screen.detect.components.DetectModeSelector
import cz.vut.oneshotdetector.view.screen.detect.components.DetectResultPanel
import cz.vut.oneshotdetector.view.screen.detect.components.DetectTopBar
import cz.vut.oneshotdetector.view.screen.detect.components.PreviewArea
import cz.vut.oneshotdetector.view.screen.detect.components.ReferenceImagePickerDialog
import cz.vut.oneshotdetector.view.state.decodeImageWithExif
import cz.vut.oneshotdetector.view.theme.LocalSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Icon vector for each ROI detection target. Resolved in the composable layer. */
internal val DetectViewModel.RoiDetectionTarget.displayLabel: String
    get() = when (this) {
        DetectViewModel.RoiDetectionTarget.Plant -> "Plant"
        DetectViewModel.RoiDetectionTarget.All   -> "All"
    }

/** Human-readable label for each compare mode shown in the compare-mode pill. */
internal val DetectViewModel.CompareMode.compareModeDisplayLabel: String
    get() = when (this) {
        DetectViewModel.CompareMode.Reference    -> "Reference"
        DetectViewModel.CompareMode.WholeGallery -> "Gallery"
        DetectViewModel.CompareMode.None         -> "Compare"
    }

/** Human-readable label for each detect mode shown in the mode-selector pill. */
internal val DetectViewModel.DetectMode.displayLabel: String
    get() = when (this) {
        DetectViewModel.DetectMode.Roi        -> "Detect ROI"
        DetectViewModel.DetectMode.Segment    -> "Segment"
        DetectViewModel.DetectMode.Crop       -> "Crop"
        DetectViewModel.DetectMode.WholeImage -> "Whole Image"
    }

/**
 * Full-screen detect UI built for real-time ROI / segmentation workflows.
 *
 * Layout:
 * - Top [DetectConfig.PREVIEW_WEIGHT] fraction — camera or captured-image preview with
 *   a floating action button (capture / resume-live).
 * - Bottom [DetectConfig.PANEL_WEIGHT] fraction — status line + two-column result panel.
 * - Mode-selector overlay rendered on top when the user taps the mode pill.
 *
 * All visual constants are centralised in [DetectConfig]. All business logic lives in [DetectViewModel].
 */
@Composable
fun DetectScreen(
    onBack: () -> Unit,
    uiState: DetectViewModel.UiState,
    galleryImages: List<GalleryImage>,
    selectedRoiCrop: Bitmap?,
    onCaptureSaved: (Uri, String) -> Unit,
    onCaptureFailed: () -> Unit,
    onResumeLiveClicked: () -> Unit,
    onDetectModeSelected: (DetectViewModel.DetectMode) -> Unit,
    onRoiDetectionTargetSelected: (DetectViewModel.RoiDetectionTarget) -> Unit,
    onCompareModeSelected: (DetectViewModel.CompareMode) -> Unit,
    onSelectReferenceImage: () -> Unit,
    onReferenceImageSelected: (GalleryImage) -> Unit,
    onReferencePickerDismissed: () -> Unit,
    onPreviewTapped: (normX: Float, normY: Float) -> Unit,
    onRoiTapped: (normX: Float, normY: Float) -> Unit,
    onCropTapped: suspend (normX: Float, normY: Float, frame: Bitmap?) -> Unit,
    onLiveFrameAvailable: (Bitmap) -> Unit,
    onLiveFrameForDetection: suspend (Bitmap) -> Unit,
    onLiveFrameForSegmentation: suspend (Bitmap) -> Unit,
    onReferenceImageClicked: ((uri: String) -> Unit)? = null,
    onDetectedImageClicked: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val spacing = LocalSpacing.current

    var showModeSelector by remember { mutableStateOf(false) }

    // ── Derived flags ─────────────────────────────────────────────────────────────────────────
    val isLive       = uiState.viewMode == DetectViewModel.ViewMode.CameraPreview
    val isSegment    = uiState.detectMode == DetectViewModel.DetectMode.Segment
    val isDetectRoi  = uiState.detectMode == DetectViewModel.DetectMode.Roi
    val isCrop       = uiState.detectMode == DetectViewModel.DetectMode.Crop
    val isWholeImage = uiState.detectMode == DetectViewModel.DetectMode.WholeImage

    // Decode the captured image asynchronously whenever the URI changes
    val capturedBitmap by produceState<ImageBitmap?>(null, uiState.capturedImageUri) {
        value = uiState.capturedImageUri?.let { uri ->
            runCatching { decodeImageWithExif(context, uri.toUri())?.asImageBitmap() }.getOrNull()
        }
    }

    // ── Derived UI values ─────────────────────────────────────────────────────────────────────

    val statusText: String? = when {
        isSegment    -> uiState.segmentStatus ?: "Tap anywhere to segment"
        isDetectRoi && uiState.detections.isNotEmpty() ->
            (uiState.captureStatus ?: "") + " — tap a box to select"
        uiState.compareMode == DetectViewModel.CompareMode.Reference ->
            uiState.similarityStatus?.takeIf { uiState.similarityScore == null } ?: uiState.captureStatus
        isCrop       -> "Tap anywhere to crop"
        isWholeImage -> if (isLive) "Live — whole image" else "Whole image captured"
        else         -> uiState.captureStatus
    }

    val previewImage: ImageBitmap? = when {
        isSegment    -> uiState.segmentedCrop?.asImageBitmap()
        isDetectRoi  -> selectedRoiCrop?.asImageBitmap()
        isCrop       -> selectedRoiCrop?.asImageBitmap()
        isWholeImage -> capturedBitmap
        else         -> null
    }

    // ── Tap handler factory ────────────────────────────────────────────────────────────────────
    // Returns the correct onTap lambda for PreviewArea based on the active mode.
    // The `liveFrame` provider is non-null only inside the CameraPreviewCore content lambda.
    fun buildOnTap(getFrame: (() -> Bitmap?)? = null): ((Float, Float) -> Unit)? = when {
        isSegment -> onPreviewTapped
        isDetectRoi -> onRoiTapped
        isCrop -> { normX, normY ->
            scope.launch { onCropTapped(normX, normY, getFrame?.invoke()) }
            Unit
        }
        else -> null
    }

    AppScreen { _ ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DetectConfig.background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
            ) {
                // ── Preview section ───────────────────────────────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(DetectConfig.PREVIEW_WEIGHT)
                ) {
                    DetectTopBar(
                        currentMode = uiState.detectMode,
                        roiDetectionTarget = uiState.roiDetectionTarget,
                        compareMode = uiState.compareMode,
                        onBack = onBack,
                        onModeClicked = { showModeSelector = true },
                        onRoiDetectionTargetClicked = {
                            val entries = DetectViewModel.RoiDetectionTarget.entries
                            val next = entries[(entries.indexOf(uiState.roiDetectionTarget) + 1) % entries.size]
                            onRoiDetectionTargetSelected(next)
                        },
                        onCompareModeClicked = {
                            if (uiState.compareMode == DetectViewModel.CompareMode.Reference) {
                                onCompareModeSelected(DetectViewModel.CompareMode.WholeGallery)
                            } else {
                                onSelectReferenceImage()
                            }
                        }
                    )

                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        val previewHeight = maxHeight

                        if (isLive) {
                            CameraPreviewCore(
                                permissionMessage = DetectConfig.CAMERA_PERMISSION_MSG,
                                fileNamePrefix = DetectConfig.CAMERA_FILE_PREFIX,
                                previewScaleType = PreviewView.ScaleType.FIT_CENTER,
                                onCaptureSaved = onCaptureSaved,
                                onCaptureFailed = onCaptureFailed
                            ) { preview, onCapture, getCurrentFrameBitmap ->
                                var smoothedProcessingMs by remember(
                                    uiState.detectMode,
                                    uiState.compareMode,
                                    uiState.triggerMode
                                ) { mutableStateOf(0.0) }
                                var debugLoopMs by remember { mutableStateOf(0L) }
                                var debugProcMs by remember { mutableStateOf(0L) }

                                // Continuously feed live frames to the active processing pipeline
                                LaunchedEffect(uiState.detectMode, uiState.compareMode, uiState.triggerMode) {
                                    while (isActive &&
                                        uiState.viewMode == DetectViewModel.ViewMode.CameraPreview
                                    ) {
                                        val startedAtMs = SystemClock.elapsedRealtime()
                                        val frame = getCurrentFrameBitmap()
                                        if (frame != null) {
                                            when {
                                                uiState.detectMode == DetectViewModel.DetectMode.Segment ->
                                                    onLiveFrameForSegmentation(frame)
                                                uiState.detectMode == DetectViewModel.DetectMode.Roi ->
                                                    onLiveFrameForDetection(frame)
                                                uiState.detectMode == DetectViewModel.DetectMode.WholeImage ->
                                                    onLiveFrameForDetection(frame)
                                                else ->
                                                    onLiveFrameAvailable(frame)
                                            }
                                            val elapsedMs = (SystemClock.elapsedRealtime() - startedAtMs).toDouble()
                                            smoothedProcessingMs = if (smoothedProcessingMs <= 0.0) {
                                                elapsedMs
                                            } else {
                                                val alpha = DetectConfig.LIVE_FRAME_SMOOTHING_ALPHA
                                                (smoothedProcessingMs * (1.0 - alpha)) + (elapsedMs * alpha)
                                            }
                                            val delayMs = DetectConfig.adaptiveLiveFrameDelayMs(
                                                detectMode = uiState.detectMode,
                                                compareMode = uiState.compareMode,
                                                smoothedProcessingMs = smoothedProcessingMs
                                            )
                                            if (DetectConfig.DEBUG) {
                                                debugProcMs = elapsedMs.toLong()
                                                debugLoopMs = elapsedMs.toLong() + delayMs
                                            }
                                            delay(delayMs)
                                        } else {
                                            delay(DetectConfig.LIVE_FRAME_NO_FRAME_RETRY_MS)
                                        }
                                    }
                                }

                                Box(modifier = Modifier.fillMaxSize()) {
                                    PreviewArea(
                                        viewMode = uiState.viewMode,
                                        livePreview = preview,
                                        capturedBitmap = capturedBitmap,
                                        detections = uiState.detections,
                                        detectionSourceWidth = uiState.detectionSourceWidth,
                                        detectionSourceHeight = uiState.detectionSourceHeight,
                                        mediaHeight = previewHeight,
                                        horizontalPadding = DetectConfig.previewHorizontalPadding,
                                        tapIndicatorNorm = null,
                                        detectionMatches = if (uiState.compareMode == DetectViewModel.CompareMode.WholeGallery)
                                            uiState.detectionMatches else emptyList(),
                                        onTap = buildOnTap(getFrame = getCurrentFrameBitmap)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(bottom = spacing.sm)
                                    ) {
                                        DetectActionButton(isLive = true, onClick = onCapture)
                                    }
                                    if (DetectConfig.DEBUG && debugLoopMs > 0L) {
                                        val fps = if (debugLoopMs > 0L) 1000.0 / debugLoopMs else 0.0
                                        Text(
                                            text = "Loop: $debugLoopMs ms\nRate: ${"%.1f".format(fps)} FPS\nProc: $debugProcMs ms",
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            lineHeight = 14.sp,
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(end = spacing.sm, bottom = spacing.xs)
                                                .background(Color.Black.copy(alpha = 0.55f))
                                                .padding(horizontal = spacing.xs, vertical = spacing.xxs)
                                        )
                                    }
                                }
                            }
                        } else {
                            Box(modifier = Modifier.fillMaxSize()) {
                                PreviewArea(
                                    viewMode = uiState.viewMode,
                                    livePreview = null,
                                    capturedBitmap = capturedBitmap,
                                    detections = uiState.detections,
                                    detectionSourceWidth = uiState.detectionSourceWidth,
                                    detectionSourceHeight = uiState.detectionSourceHeight,
                                    mediaHeight = previewHeight,
                                    horizontalPadding = DetectConfig.previewHorizontalPadding,
                                    tapIndicatorNorm = uiState.tapIndicatorNorm,
                                    detectionMatches = if (uiState.compareMode == DetectViewModel.CompareMode.WholeGallery)
                                        uiState.detectionMatches else emptyList(),
                                    onTap = buildOnTap()
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = spacing.sm)
                                ) {
                                    DetectActionButton(
                                        isLive = false,
                                        onClick = onResumeLiveClicked
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(
                    color = DetectConfig.onBackground.copy(alpha = DetectConfig.DIVIDER_ALPHA)
                )

                // ── Results panel ─────────────────────────────────────────────────────────────
                // Reference mode: always show the reference image regardless of mode or selection.
                // WholeImage: live frame is the implicit source so always show.
                // Roi/Crop/Segment + Gallery: only show once the user has selected a crop.
                val showRightContent = uiState.compareMode == DetectViewModel.CompareMode.Reference
                    || isWholeImage
                    || previewImage != null
                DetectResultPanel(
                    statusText = statusText,
                    previewImage = previewImage,
                    galleryMatch = if (uiState.compareMode == DetectViewModel.CompareMode.WholeGallery && showRightContent)
                        uiState.detectGalleryMatch else null,
                    referenceImageUri = if (uiState.compareMode == DetectViewModel.CompareMode.Reference && showRightContent)
                        uiState.referenceImageUri else null,
                    referenceImageLabel = if (uiState.compareMode == DetectViewModel.CompareMode.Reference && showRightContent)
                        uiState.referenceImageLabel else null,
                    similarityScore = if (showRightContent) uiState.similarityScore else null,
                    onImageClicked = onReferenceImageClicked,
                    onDetectedImageClicked = onDetectedImageClicked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(DetectConfig.PANEL_WEIGHT)
                )
            }

            // ── Mode selector overlay ──────────────────────────────────────────────────────────
            if (showModeSelector) {
                DetectModeSelector(
                    currentMode = uiState.detectMode,
                    onModeSelected = { type ->
                        onDetectModeSelected(type)
                        showModeSelector = false
                    },
                    onDismiss = { showModeSelector = false }
                )
            }

            if (uiState.overlay == DetectViewModel.Overlay.ReferencePicker) {
                ReferenceImagePickerDialog(
                    images = galleryImages,
                    onImageSelected = onReferenceImageSelected,
                    onDismiss = onReferencePickerDismissed
                )
            }

        }
    }
}
