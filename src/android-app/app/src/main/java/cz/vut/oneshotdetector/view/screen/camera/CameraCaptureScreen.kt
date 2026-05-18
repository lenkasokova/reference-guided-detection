/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.screen.camera

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import cz.vut.oneshotdetector.view.components.camera.CameraPreviewCore
import cz.vut.oneshotdetector.view.layout.AppBackButton
import cz.vut.oneshotdetector.view.layout.AppScreen
import cz.vut.oneshotdetector.view.screen.detect.DetectConfig
import cz.vut.oneshotdetector.view.screen.detect.displayLabel
import cz.vut.oneshotdetector.view.screen.detect.components.DetectActionButton
import cz.vut.oneshotdetector.view.screen.detect.components.DetectModeSelector
import cz.vut.oneshotdetector.view.screen.detect.components.DetectResultPanel
import cz.vut.oneshotdetector.view.screen.detect.components.PreviewArea
import cz.vut.oneshotdetector.view.state.decodeImageWithExif
import cz.vut.oneshotdetector.view.theme.LocalSpacing
import cz.vut.oneshotdetector.viewmodel.detect.DetectViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun CameraCaptureScreen(
    onBack: () -> Unit,
    uiState: DetectViewModel.UiState,
    selectedRoiCrop: Bitmap?,
    onCaptureSaved: (Uri, String) -> Unit,
    onCaptureFailed: () -> Unit,
    onResumeLiveClicked: () -> Unit,
    onDetectModeSelected: (DetectViewModel.DetectMode) -> Unit,
    onRoiDetectionTargetSelected: (DetectViewModel.RoiDetectionTarget) -> Unit,
    onPreviewTapped: (normX: Float, normY: Float) -> Unit,
    onRoiTapped: (normX: Float, normY: Float) -> Unit,
    onCropTapped: suspend (normX: Float, normY: Float, frame: Bitmap?) -> Unit,
    onLiveFrameAvailable: (Bitmap) -> Unit,
    onLiveFrameForDetection: suspend (Bitmap) -> Unit,
    onLiveFrameForSegmentation: suspend (Bitmap) -> Unit,
    onDetectedImageClicked: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val spacing = LocalSpacing.current

    var showModeSelector by remember { mutableStateOf(false) }

    val isLive      = uiState.viewMode == DetectViewModel.ViewMode.CameraPreview
    val isSegment   = uiState.detectMode == DetectViewModel.DetectMode.Segment
    val isDetectRoi = uiState.detectMode == DetectViewModel.DetectMode.Roi
    val isCrop      = uiState.detectMode == DetectViewModel.DetectMode.Crop

    val capturedRawBitmap by produceState<Bitmap?>(null, uiState.capturedImageUri) {
        value = uiState.capturedImageUri?.let { uri ->
            runCatching { decodeImageWithExif(context, uri.toUri()) }.getOrNull()
        }
    }
    val capturedBitmap = remember(capturedRawBitmap) { capturedRawBitmap?.asImageBitmap() }

    val statusText: String? = when {
        isSegment   -> uiState.segmentStatus ?: "Tap anywhere to segment"
        isDetectRoi && uiState.detections.isNotEmpty() &&
            uiState.triggerMode == DetectViewModel.TriggerMode.OnTap ->
            (uiState.captureStatus ?: "") + " — tap a box to select"
        isCrop      -> "Tap anywhere to crop"
        uiState.detectMode == DetectViewModel.DetectMode.WholeImage ->
            if (isLive) "Live — whole image" else "Whole image captured"
        else        -> uiState.captureStatus
    }

    val previewImage = when {
        isSegment   -> uiState.segmentedCrop?.asImageBitmap()
        isDetectRoi -> selectedRoiCrop?.asImageBitmap()
        isCrop      -> selectedRoiCrop?.asImageBitmap()
        uiState.detectMode == DetectViewModel.DetectMode.WholeImage -> capturedBitmap
        else        -> null
    }

    fun buildOnTap(getFrame: (() -> Bitmap?)? = null): ((Float, Float) -> Unit)? = when {
        isSegment   -> onPreviewTapped
        isDetectRoi -> onRoiTapped
        isCrop      -> { normX, normY ->
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
                    // Top bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = spacing.sm, vertical = spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppBackButton(onClick = onBack)
                        Spacer(Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                            val roiEnabled = isDetectRoi
                            IconButton(
                                onClick = {
                                    val entries = DetectViewModel.RoiDetectionTarget.entries
                                    val next = entries[(entries.indexOf(uiState.roiDetectionTarget) + 1) % entries.size]
                                    onRoiDetectionTargetSelected(next)
                                },
                                enabled = roiEnabled
                            ) {
                                Icon(
                                    imageVector = when (uiState.roiDetectionTarget) {
                                        DetectViewModel.RoiDetectionTarget.Plant -> Icons.Filled.Eco
                                        DetectViewModel.RoiDetectionTarget.All   -> Icons.Filled.Apps
                                    },
                                    contentDescription = uiState.roiDetectionTarget.name,
                                    tint = if (roiEnabled) DetectConfig.onBackground
                                           else DetectConfig.onBackground.copy(alpha = 0.3f)
                                )
                            }
                            OutlinedButton(
                                onClick = { showModeSelector = true },
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = Color.Transparent,
                                    contentColor = DetectConfig.onBackground
                                ),
                                border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                                    brush = SolidColor(
                                        DetectConfig.onBackground.copy(alpha = DetectConfig.MODE_BORDER_ALPHA)
                                    )
                                )
                            ) {
                                Text(uiState.detectMode.displayLabel, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }

                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        val previewHeight = maxHeight

                        if (isLive) {
                            CameraPreviewCore(
                                permissionMessage = "Camera permission is required to show the preview.",
                                fileNamePrefix = "capture_",
                                previewScaleType = PreviewView.ScaleType.FIT_CENTER,
                                onCaptureSaved = onCaptureSaved,
                                onCaptureFailed = onCaptureFailed
                            ) { preview, onCapture, getCurrentFrameBitmap ->
                                var smoothedProcessingMs by remember(uiState.detectMode) {
                                    mutableStateOf(0.0)
                                }

                                LaunchedEffect(uiState.detectMode, uiState.triggerMode) {
                                    while (isActive) {
                                        val startedAtMs = SystemClock.elapsedRealtime()
                                        val frame = getCurrentFrameBitmap()
                                        if (frame != null) {
                                            when {
                                                uiState.detectMode == DetectViewModel.DetectMode.Segment ->
                                                    onLiveFrameForSegmentation(frame)
                                                uiState.detectMode == DetectViewModel.DetectMode.Roi ->
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
                                                compareMode = DetectViewModel.CompareMode.None,
                                                smoothedProcessingMs = smoothedProcessingMs
                                            )
                                            delay(delayMs)
                                        } else {
                                            delay(DetectConfig.LIVE_FRAME_NO_FRAME_RETRY_MS)
                                        }
                                    }
                                }

                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    PreviewArea(
                                        viewMode = uiState.viewMode,
                                        livePreview = preview,
                                        capturedBitmap = null,
                                        detections = uiState.detections,
                                        detectionSourceWidth = uiState.detectionSourceWidth,
                                        detectionSourceHeight = uiState.detectionSourceHeight,
                                        mediaHeight = previewHeight,
                                        horizontalPadding = DetectConfig.previewHorizontalPadding,
                                        tapIndicatorNorm = null,
                                        detectionMatches = emptyList(),
                                        onTap = buildOnTap(getFrame = getCurrentFrameBitmap)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(bottom = spacing.sm)
                                    ) {
                                        DetectActionButton(isLive = true, onClick = onCapture)
                                    }
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
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
                                    detectionMatches = uiState.detectionMatches,
                                    onTap = buildOnTap()
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = spacing.sm)
                                ) {
                                    DetectActionButton(isLive = false, onClick = onResumeLiveClicked)
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(
                    color = DetectConfig.onBackground.copy(alpha = DetectConfig.DIVIDER_ALPHA)
                )

                // ── Results panel ─────────────────────────────────────────────────────────────
                DetectResultPanel(
                    statusText = statusText,
                    previewImage = previewImage,
                    galleryMatch = null,
                    referenceImageUri = null,
                    referenceImageLabel = null,
                    similarityScore = null,
                    onDetectedImageClicked = onDetectedImageClicked,
                    showReferenceImage = false,
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
        }
    }
}
