/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.screen.camera

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
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
import cz.vut.oneshotdetector.view.screen.detect.DetectConfig
import cz.vut.oneshotdetector.view.state.decodeImageWithExif
import cz.vut.oneshotdetector.view.state.saveBitmapToFile
import cz.vut.oneshotdetector.viewmodel.detect.DetectViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CameraFlow(
    onBack: () -> Unit,
    onPhotoCaptured: (uri: String) -> Unit
) {
    val context = LocalContext.current
    val viewModel: DetectViewModel = viewModel(factory = DetectViewModel.factory(context))
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()

    var selectedRoiCrop by remember { mutableStateOf<Bitmap?>(null) }

    // Clear crop when switching away from detection modes or when resuming live
    LaunchedEffect(uiState.capturedImageUri, uiState.detectMode) {
        if (uiState.capturedImageUri == null ||
            (uiState.detectMode != DetectViewModel.DetectMode.Roi &&
             uiState.detectMode != DetectViewModel.DetectMode.Crop &&
             uiState.detectMode != DetectViewModel.DetectMode.Segment &&
             uiState.detectMode != DetectViewModel.DetectMode.WholeImage)
        ) {
            selectedRoiCrop = null
        }
    }

    // Sync segmented crop from state
    LaunchedEffect(uiState.segmentedCrop, uiState.detectMode) {
        if (uiState.detectMode == DetectViewModel.DetectMode.Segment) {
            selectedRoiCrop = uiState.segmentedCrop
        }
    }

    // Decode whole image into selectedRoiCrop when in WholeImage mode
    LaunchedEffect(uiState.capturedImageUri, uiState.detectMode) {
        if (uiState.detectMode != DetectViewModel.DetectMode.WholeImage) return@LaunchedEffect
        val uri = uiState.capturedImageUri ?: return@LaunchedEffect
        selectedRoiCrop = withContext(Dispatchers.IO) { decodeImageWithExif(context, uri.toUri()) }
    }

    // Run ROI detection on the captured image
    LaunchedEffect(uiState.capturedImageUri, uiState.roiDetectionTarget) {
        if (uiState.capturedImageUri == null) return@LaunchedEffect
        if (uiState.viewMode == DetectViewModel.ViewMode.CameraPreview) return@LaunchedEffect
        viewModel.detectAndCacheRoi()
    }

    // After detections arrive, compare each ROI crop to gallery to get per-box labels + scores
    LaunchedEffect(uiState.detections, uiState.capturedImageUri) {
        if (uiState.detectMode != DetectViewModel.DetectMode.Roi) return@LaunchedEffect
        if (uiState.viewMode == DetectViewModel.ViewMode.CameraPreview) return@LaunchedEffect
        if (uiState.detections.isEmpty()) return@LaunchedEffect
        val uri = uiState.capturedImageUri ?: return@LaunchedEffect
        val bitmap = withContext(Dispatchers.IO) { decodeImageWithExif(context, uri.toUri()) }
            ?: return@LaunchedEffect
        viewModel.autoSelectBestRoi(bitmap)
    }

    // Pre-encode captured image for segmentation
    LaunchedEffect(uiState.capturedImageUri, uiState.detectMode) {
        val uri = uiState.capturedImageUri ?: return@LaunchedEffect
        if (uiState.detectMode != DetectViewModel.DetectMode.Segment) return@LaunchedEffect
        if (uiState.viewMode == DetectViewModel.ViewMode.CameraPreview) return@LaunchedEffect
        if (viewModel.samCapturedBitmapUri == uri && viewModel.samCapturedBitmap != null) return@LaunchedEffect
        val bitmap = withContext(Dispatchers.IO) { decodeImageWithExif(context, uri.toUri()) }
            ?: return@LaunchedEffect
        viewModel.samCapturedBitmap = bitmap
        viewModel.samCapturedBitmapUri = uri
        viewModel.preEncodeForSegmentation(bitmap)
    }

    CameraCaptureScreen(
        onBack = onBack,
        uiState = uiState,
        selectedRoiCrop = selectedRoiCrop,
        onCaptureSaved = viewModel::onCaptureSaved,
        onCaptureFailed = viewModel::onCaptureFailed,
        onResumeLiveClicked = viewModel::onResumeLiveClicked,
        onDetectModeSelected = viewModel::onDetectModeSelected,
        onRoiDetectionTargetSelected = viewModel::onRoiDetectionTargetSelected,
        onCropTapped = { normX, normY, liveFrame ->
            val bitmap = liveFrame ?: withContext(Dispatchers.IO) {
                val uri = uiState.capturedImageUri ?: return@withContext null
                decodeImageWithExif(context, uri.toUri())
            } ?: return@CameraCaptureScreen
            val halfW = (bitmap.width  * DetectConfig.CROP_HALF_FRACTION).toInt().coerceAtLeast(1)
            val halfH = (bitmap.height * DetectConfig.CROP_HALF_FRACTION).toInt().coerceAtLeast(1)
            val cx = (normX * bitmap.width).toInt()
            val cy = (normY * bitmap.height).toInt()
            val xmin = (cx - halfW).coerceIn(0, bitmap.width)
            val ymin = (cy - halfH).coerceIn(0, bitmap.height)
            val xmax = (cx + halfW).coerceIn(0, bitmap.width)
            val ymax = (cy + halfH).coerceIn(0, bitmap.height)
            val w = (xmax - xmin).coerceAtLeast(1)
            val h = (ymax - ymin).coerceAtLeast(1)
            selectedRoiCrop = withContext(Dispatchers.IO) {
                Bitmap.createBitmap(bitmap, xmin, ymin, w, h)
            }
        },
        onPreviewTapped = { normX, normY ->
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    if (uiState.viewMode == DetectViewModel.ViewMode.CameraPreview) {
                        viewModel.latestLiveFrame
                    } else {
                        val uri = uiState.capturedImageUri ?: return@withContext null
                        val cached = viewModel.samCapturedBitmap
                        if (cached != null && viewModel.samCapturedBitmapUri == uri) cached
                        else decodeImageWithExif(context, uri.toUri())
                    }
                } ?: return@launch
                viewModel.onTapOnPreview(
                    normX = normX,
                    normY = normY,
                    bitmap = bitmap,
                    keepTapIndicator = uiState.viewMode != DetectViewModel.ViewMode.CameraPreview
                )
            }
        },
        onRoiTapped = { normX, normY ->
            scope.launch {
                val detections = uiState.detections
                val srcW = uiState.detectionSourceWidth?.toFloat() ?: return@launch
                val srcH = uiState.detectionSourceHeight?.toFloat() ?: return@launch

                val hit = detections
                    .filter { det ->
                        val box = det.box
                        if (box.size < 4) return@filter false
                        val isNorm = box.all { it in 0f..1f }
                        val ymin = if (isNorm) box[0] else box[0] / srcH
                        val xmin = if (isNorm) box[1] else box[1] / srcW
                        val ymax = if (isNorm) box[2] else box[2] / srcH
                        val xmax = if (isNorm) box[3] else box[3] / srcW
                        normX in xmin..xmax && normY in ymin..ymax
                    }
                    .minByOrNull { det ->
                        val box = det.box
                        val isNorm = box.all { it in 0f..1f }
                        val w = if (isNorm) box[3] - box[1] else (box[3] - box[1]) / srcW
                        val h = if (isNorm) box[2] - box[0] else (box[2] - box[0]) / srcH
                        w * h
                    } ?: return@launch

                val bitmap = withContext(Dispatchers.IO) {
                    if (uiState.viewMode == DetectViewModel.ViewMode.CameraPreview) {
                        viewModel.latestLiveFrame
                    } else {
                        val uri = uiState.capturedImageUri?.toUri() ?: return@withContext null
                        decodeImageWithExif(context, uri)
                    }
                } ?: return@launch

                val box = hit.box
                val isNorm = box.all { it in 0f..1f }
                val ymin = (if (isNorm) box[0] * bitmap.height else box[0]).toInt().coerceIn(0, bitmap.height)
                val xmin = (if (isNorm) box[1] * bitmap.width  else box[1]).toInt().coerceIn(0, bitmap.width)
                val ymax = (if (isNorm) box[2] * bitmap.height else box[2]).toInt().coerceIn(0, bitmap.height)
                val xmax = (if (isNorm) box[3] * bitmap.width  else box[3]).toInt().coerceIn(0, bitmap.width)
                val w = (xmax - xmin).coerceAtLeast(1)
                val h = (ymax - ymin).coerceAtLeast(1)

                selectedRoiCrop = withContext(Dispatchers.IO) {
                    Bitmap.createBitmap(bitmap, xmin, ymin, w, h)
                }
            }
        },
        onLiveFrameAvailable = { frame ->
            viewModel.latestLiveFrame = frame
            viewModel.updateSourceDimensions(frame.width, frame.height)
        },
        onLiveFrameForDetection = { frame ->
            viewModel.latestLiveFrame = frame
            viewModel.detectLiveFrameRoi(frame)
        },
        onLiveFrameForSegmentation = { frame ->
            viewModel.latestLiveFrame = frame
            val (normX, normY) = uiState.tapIndicatorNorm ?: return@CameraCaptureScreen
            viewModel.onTapOnPreview(
                normX = normX,
                normY = normY,
                bitmap = frame,
                keepTapIndicator = false
            )
        },
        onDetectedImageClicked = {
            scope.launch {
                val bitmap = selectedRoiCrop
                    ?: viewModel.latestLiveFrame
                    ?: return@launch
                val file = withContext(Dispatchers.IO) {
                    saveBitmapToFile(context, bitmap, "capture_")
                }
                onPhotoCaptured(android.net.Uri.fromFile(file).toString())
            }
        }
    )
}
