/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.screen.actions

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import cz.vut.oneshotdetector.view.layout.AppScreen
import cz.vut.oneshotdetector.view.layout.ScreenColumn
import cz.vut.oneshotdetector.view.state.decodeImageWithExif
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun CropPhotoScreen(
    photoUri: Uri,
    onCrop: (normLeft: Float, normTop: Float, normRight: Float, normBottom: Float) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    val imageBitmap by produceState<ImageBitmap?>(null, photoUri) {
        value = withContext(Dispatchers.IO) {
            decodeImageWithExif(context, photoUri, inSampleSize = 2)?.asImageBitmap()
        }
    }

    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val imageRect = remember(imageBitmap, containerSize) {
        computeCropImageFitRect(imageBitmap, containerSize)
    }

    var cropLeft by remember { mutableFloatStateOf(0f) }
    var cropTop by remember { mutableFloatStateOf(0f) }
    var cropRight by remember { mutableFloatStateOf(0f) }
    var cropBottom by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(imageRect) {
        if (imageRect != Rect.Zero) {
            cropLeft = imageRect.left
            cropTop = imageRect.top
            cropRight = imageRect.right
            cropBottom = imageRect.bottom
        }
    }

    val minCropPx = with(density) { 40.dp.toPx() }
    val handleSize = 22.dp
    val handleHalfPx = with(density) { (handleSize / 2).toPx() }

    AppScreen { paddingValues ->
        ScreenColumn(
            paddingValues = paddingValues,
            modifier = Modifier.background(Color.Black)
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 12.dp)
                    .onSizeChanged { containerSize = it }
            ) {
                if (imageBitmap != null) {
                    Image(
                        bitmap = imageBitmap!!,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )

                    Canvas(Modifier.fillMaxSize()) {
                        val overlay = Color.Black.copy(alpha = 0.55f)
                        val cw = cropRight - cropLeft
                        val ch = cropBottom - cropTop
                        if (cropTop > imageRect.top)
                            drawRect(overlay, Offset(imageRect.left, imageRect.top), Size(imageRect.width, cropTop - imageRect.top))
                        if (cropBottom < imageRect.bottom)
                            drawRect(overlay, Offset(imageRect.left, cropBottom), Size(imageRect.width, imageRect.bottom - cropBottom))
                        if (cropLeft > imageRect.left)
                            drawRect(overlay, Offset(imageRect.left, cropTop), Size(cropLeft - imageRect.left, ch))
                        if (cropRight < imageRect.right)
                            drawRect(overlay, Offset(cropRight, cropTop), Size(imageRect.right - cropRight, ch))
                        drawRect(Color.White, Offset(cropLeft, cropTop), Size(cw, ch), style = Stroke(2.dp.toPx()))
                        val grid = Color.White.copy(alpha = 0.35f)
                        val gs = 0.7.dp.toPx()
                        drawLine(grid, Offset(cropLeft + cw / 3, cropTop), Offset(cropLeft + cw / 3, cropBottom), gs)
                        drawLine(grid, Offset(cropLeft + cw * 2 / 3, cropTop), Offset(cropLeft + cw * 2 / 3, cropBottom), gs)
                        drawLine(grid, Offset(cropLeft, cropTop + ch / 3), Offset(cropRight, cropTop + ch / 3), gs)
                        drawLine(grid, Offset(cropLeft, cropTop + ch * 2 / 3), Offset(cropRight, cropTop + ch * 2 / 3), gs)
                    }

                    val midX = (cropLeft + cropRight) / 2
                    val midY = (cropTop + cropBottom) / 2

                    CropHandle(cropLeft - handleHalfPx, cropTop - handleHalfPx, handleSize) { dx, dy ->
                        cropLeft = (cropLeft + dx).coerceIn(imageRect.left, cropRight - minCropPx)
                        cropTop = (cropTop + dy).coerceIn(imageRect.top, cropBottom - minCropPx)
                    }
                    CropHandle(cropRight - handleHalfPx, cropTop - handleHalfPx, handleSize) { dx, dy ->
                        cropRight = (cropRight + dx).coerceIn(cropLeft + minCropPx, imageRect.right)
                        cropTop = (cropTop + dy).coerceIn(imageRect.top, cropBottom - minCropPx)
                    }
                    CropHandle(cropLeft - handleHalfPx, cropBottom - handleHalfPx, handleSize) { dx, dy ->
                        cropLeft = (cropLeft + dx).coerceIn(imageRect.left, cropRight - minCropPx)
                        cropBottom = (cropBottom + dy).coerceIn(cropTop + minCropPx, imageRect.bottom)
                    }
                    CropHandle(cropRight - handleHalfPx, cropBottom - handleHalfPx, handleSize) { dx, dy ->
                        cropRight = (cropRight + dx).coerceIn(cropLeft + minCropPx, imageRect.right)
                        cropBottom = (cropBottom + dy).coerceIn(cropTop + minCropPx, imageRect.bottom)
                    }
                    CropHandle(midX - handleHalfPx, cropTop - handleHalfPx, handleSize) { _, dy ->
                        cropTop = (cropTop + dy).coerceIn(imageRect.top, cropBottom - minCropPx)
                    }
                    CropHandle(midX - handleHalfPx, cropBottom - handleHalfPx, handleSize) { _, dy ->
                        cropBottom = (cropBottom + dy).coerceIn(cropTop + minCropPx, imageRect.bottom)
                    }
                    CropHandle(cropLeft - handleHalfPx, midY - handleHalfPx, handleSize) { dx, _ ->
                        cropLeft = (cropLeft + dx).coerceIn(imageRect.left, cropRight - minCropPx)
                    }
                    CropHandle(cropRight - handleHalfPx, midY - handleHalfPx, handleSize) { dx, _ ->
                        cropRight = (cropRight + dx).coerceIn(cropLeft + minCropPx, imageRect.right)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text("Cancel", color = Color.White)
                }
                Button(
                    onClick = {
                        if (imageRect.width > 0f && imageRect.height > 0f) {
                            onCrop(
                                ((cropLeft - imageRect.left) / imageRect.width).coerceIn(0f, 1f),
                                ((cropTop - imageRect.top) / imageRect.height).coerceIn(0f, 1f),
                                ((cropRight - imageRect.left) / imageRect.width).coerceIn(0f, 1f),
                                ((cropBottom - imageRect.top) / imageRect.height).coerceIn(0f, 1f)
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = imageBitmap != null
                ) {
                    Text("Crop")
                }
            }
        }
    }
}

@Composable
private fun CropHandle(
    x: Float,
    y: Float,
    size: Dp,
    onDrag: (dx: Float, dy: Float) -> Unit
) {
    val currentOnDrag by rememberUpdatedState(onDrag)
    Box(
        Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .size(size)
            .background(Color.White, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures { _, dragAmount ->
                    currentOnDrag(dragAmount.x, dragAmount.y)
                }
            }
    )
}

private fun computeCropImageFitRect(bitmap: ImageBitmap?, containerSize: IntSize): Rect {
    if (bitmap == null || containerSize.width == 0 || containerSize.height == 0) return Rect.Zero
    val containerAspect = containerSize.width.toFloat() / containerSize.height
    val imageAspect = bitmap.width.toFloat() / bitmap.height
    return if (imageAspect > containerAspect) {
        val scale = containerSize.width.toFloat() / bitmap.width
        val scaledH = bitmap.height * scale
        val top = (containerSize.height - scaledH) / 2f
        Rect(0f, top, containerSize.width.toFloat(), top + scaledH)
    } else {
        val scale = containerSize.height.toFloat() / bitmap.height
        val scaledW = bitmap.width * scale
        val left = (containerSize.width - scaledW) / 2f
        Rect(left, 0f, left + scaledW, containerSize.height.toFloat())
    }
}
