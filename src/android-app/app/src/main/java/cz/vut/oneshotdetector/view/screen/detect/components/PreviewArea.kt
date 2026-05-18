/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.screen.detect.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cz.vut.oneshotdetector.viewmodel.detect.DetectViewModel
import cz.vut.oneshotdetector.viewmodel.detect.DetectionMatch
import cz.vut.oneshotdetector.model.inference.Detection
import kotlin.math.min

@Composable
fun PreviewArea(
    viewMode: DetectViewModel.ViewMode,
    livePreview: (@Composable () -> Unit)?,
    capturedBitmap: ImageBitmap?,
    detections: List<Detection>,
    detectionSourceWidth: Int?,
    detectionSourceHeight: Int?,
    mediaHeight: Dp,
    horizontalPadding: Dp,
    tapIndicatorNorm: Pair<Float, Float>? = null,
    detectionMatches: List<DetectionMatch> = emptyList(),
    onTap: ((normX: Float, normY: Float) -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
            .height(mediaHeight),
        contentAlignment = Alignment.Center
    ) {
        when (viewMode) {
            DetectViewModel.ViewMode.CameraPreview -> {
                val imgW = detectionSourceWidth
                val imgH = detectionSourceHeight
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .tapToNorm(imgW, imgH, onTap)
                ) {
                    livePreview?.invoke()
                    DetectionOverlay(
                        detections = detections,
                        sourceWidth = imgW,
                        sourceHeight = imgH,
                        tapIndicatorNorm = tapIndicatorNorm,
                        detectionMatches = detectionMatches,
                        modifier = Modifier.matchParentSize()
                    )
                }
            }
            DetectViewModel.ViewMode.CapturedImage,
            DetectViewModel.ViewMode.DetectedImage -> {
                if (capturedBitmap != null) {
                    val imgW = detectionSourceWidth ?: capturedBitmap.width
                    val imgH = detectionSourceHeight ?: capturedBitmap.height
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .tapToNorm(imgW, imgH, onTap),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = capturedBitmap,
                            contentDescription = "Captured detect image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(mediaHeight),
                            contentScale = ContentScale.Fit
                        )
                        DetectionOverlay(
                            detections = detections,
                            sourceWidth = imgW,
                            sourceHeight = imgH,
                            tapIndicatorNorm = tapIndicatorNorm,
                            detectionMatches = detectionMatches,
                            modifier = Modifier.matchParentSize()
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(mediaHeight),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Loading captured image...")
                    }

                }
            }
        }
    }
}

/**
 * Converts a raw pixel tap inside a letterboxed [imgW]×[imgH] image to
 * normalised [0,1] coordinates and forwards them to [onTap].
 */
private fun Modifier.tapToNorm(
    imgW: Int?,
    imgH: Int?,
    onTap: ((normX: Float, normY: Float) -> Unit)?
): Modifier {
    if (onTap == null) return this
    return this.pointerInput(imgW, imgH, onTap) {
        detectTapGestures { tapOffset ->
            val viewW = size.width.toFloat()
            val viewH = size.height.toFloat()
            val iW = imgW?.toFloat() ?: viewW
            val iH = imgH?.toFloat() ?: viewH
            val scale = min(viewW / iW, viewH / iH)
            val drawW = iW * scale
            val drawH = iH * scale
            val offX = (viewW - drawW) / 2f
            val offY = (viewH - drawH) / 2f
            val normX = ((tapOffset.x - offX) / drawW).coerceIn(0f, 1f)
            val normY = ((tapOffset.y - offY) / drawH).coerceIn(0f, 1f)
            onTap(normX, normY)
        }
    }
}

/**
 * Visual constants for the detection / tap-indicator overlay drawn on top of the preview.
 * Centralised here so colours and sizes can be adjusted without hunting through draw calls.
 */
private object DetectionOverlayDefaults {
    val boxColor        = Color(0xFFFF6F00)
    const val BOX_STROKE        = 3f

    val tapFillColor    = Color(0xCCFFFFFF)
    val tapAccentColor  = Color(0xFF2196F3)
    const val TAP_OUTER_RADIUS  = 18f
    const val TAP_STROKE_WIDTH  = 4f
    const val TAP_INNER_RADIUS  = 6f

    val labelTextColor  = Color.White
    val labelBgColor    = Color(0xCC000000)
    const val LABEL_OFFSET_Y    = 24f
}

@Composable
private fun DetectionOverlay(
    detections: List<Detection>,
    sourceWidth: Int?,
    sourceHeight: Int?,
    tapIndicatorNorm: Pair<Float, Float>?,
    detectionMatches: List<DetectionMatch> = emptyList(),
    modifier: Modifier = Modifier
) {
    val hasContent = detections.isNotEmpty() || tapIndicatorNorm != null
    if (!hasContent || sourceWidth == null || sourceHeight == null) return

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val imgW = sourceWidth.toFloat()
        val imgH = sourceHeight.toFloat()
        val boxWpx = with(density) { maxWidth.toPx() }
        val boxHpx = with(density) { maxHeight.toPx() }
        val scale = if (imgW > 0f && imgH > 0f) {
            min(boxWpx / imgW, boxHpx / imgH)
        } else {
            1f
        }
        val drawW = imgW * scale
        val drawH = imgH * scale
        val offsetX = (boxWpx - drawW) / 2f
        val offsetY = (boxHpx - drawH) / 2f

        Canvas(modifier = Modifier.fillMaxSize()) {
            if (imgW <= 0f || imgH <= 0f) return@Canvas

            detections.forEach { detection ->
                val box = detection.box
                if (box.size < 4) return@forEach
                val isNormalized = box.all { it in 0f..1f }
                val ymin = box[0] * (if (isNormalized) imgH else 1f)
                val xmin = box[1] * (if (isNormalized) imgW else 1f)
                val ymax = box[2] * (if (isNormalized) imgH else 1f)
                val xmax = box[3] * (if (isNormalized) imgW else 1f)

                val left = offsetX + xmin * scale
                val top = offsetY + ymin * scale
                val right = offsetX + xmax * scale
                val bottom = offsetY + ymax * scale

                drawRect(
                    color = DetectionOverlayDefaults.boxColor,
                    topLeft = Offset(left, top),
                    size = Size(
                        width = (right - left).coerceAtLeast(0f),
                        height = (bottom - top).coerceAtLeast(0f)
                    ),
                    style = Stroke(width = DetectionOverlayDefaults.BOX_STROKE)
                )
            }

            // Tap indicator dot
            tapIndicatorNorm?.let { (nx, ny) ->
                val cx = offsetX + nx * drawW
                val cy = offsetY + ny * drawH
                drawCircle(
                    color = DetectionOverlayDefaults.tapFillColor,
                    radius = DetectionOverlayDefaults.TAP_OUTER_RADIUS,
                    center = Offset(cx, cy)
                )
                drawCircle(
                    color = DetectionOverlayDefaults.tapAccentColor,
                    radius = DetectionOverlayDefaults.TAP_OUTER_RADIUS,
                    center = Offset(cx, cy),
                    style = Stroke(width = DetectionOverlayDefaults.TAP_STROKE_WIDTH)
                )
                drawCircle(
                    color = DetectionOverlayDefaults.tapAccentColor,
                    radius = DetectionOverlayDefaults.TAP_INNER_RADIUS,
                    center = Offset(cx, cy)
                )
            }
        }

        detections.forEach { detection ->
            val box = detection.box
            if (box.size < 4) return@forEach
            val isNormalized = box.all { it in 0f..1f }
            val ymin = box[0] * (if (isNormalized) imgH else 1f)
            val xmin = box[1] * (if (isNormalized) imgW else 1f)
            val left = offsetX + xmin * scale
            val top = offsetY + ymin * scale
            val match = detectionMatches.find { it.detection.box.contentEquals(detection.box) }
                ?: return@forEach
            val text = "${match.galleryLabel} ${"%.2f".format(match.score)}"

            Text(
                text = text,
                color = DetectionOverlayDefaults.labelTextColor,
                modifier = Modifier
                    .offset(
                        x = with(density) { left.coerceAtLeast(0f).toDp() },
                        y = with(density) {
                            (top - DetectionOverlayDefaults.LABEL_OFFSET_Y)
                                .coerceAtLeast(0f).toDp()
                        }
                    )
                    .background(DetectionOverlayDefaults.labelBgColor)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}
