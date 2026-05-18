/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.state

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import cz.vut.oneshotdetector.model.inference.Detection
import android.graphics.BitmapFactory
import kotlin.math.min

private val bitmapCache = mutableMapOf<String, androidx.compose.ui.graphics.ImageBitmap>()

@Composable
fun PhotoPreview(
    uri: Uri,
    detections: List<Detection>,
    onTap: ((normX: Float, normY: Float) -> Unit)? = null,
    tapIndicatorNorm: Pair<Float, Float>? = null
) {
    val context = LocalContext.current
    val imageBitmap by produceState(initialValue = bitmapCache[uri.toString()], key1 = uri) {
        val cached = bitmapCache[uri.toString()]
        if (cached != null) {
            value = cached
        } else {
            value = withContext(Dispatchers.IO) {
                runCatching { decodeImageForDisplay(context, uri) }.getOrNull()?.asImageBitmap()
            }
            value?.let { bitmapCache[uri.toString()] = it }
        }
    }

    if (imageBitmap != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (onTap != null) {
                        Modifier.pointerInput(imageBitmap) {
                            detectTapGestures { tapOffset ->
                                val bmp = imageBitmap ?: return@detectTapGestures
                                val viewW = size.width.toFloat()
                                val viewH = size.height.toFloat()
                                val imgW = bmp.width.toFloat()
                                val imgH = bmp.height.toFloat()
                                val scale = min(viewW / imgW, viewH / imgH)
                                val drawW = imgW * scale
                                val drawH = imgH * scale
                                val offX = (viewW - drawW) / 2f
                                val offY = (viewH - drawH) / 2f
                                val normX = ((tapOffset.x - offX) / drawW).coerceIn(0f, 1f)
                                val normY = ((tapOffset.y - offY) / drawH).coerceIn(0f, 1f)
                                onTap(normX, normY)
                            }
                        }
                    } else Modifier
                )
        ) {
            Image(
                bitmap = imageBitmap!!,
                contentDescription = "Captured photo",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
            // Small hint label at the bottom when awaiting a tap
            if (onTap != null && tapIndicatorNorm == null) {
                Text(
                    text = "Tap to select subject",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color(0x88000000))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            // Detection boxes + tap dot overlay
            if (detections.isNotEmpty() || tapIndicatorNorm != null) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    val img = imageBitmap!!
                    val imgW = img.width.toFloat()
                    val imgH = img.height.toFloat()
                    if (imgW <= 0f || imgH <= 0f) return@Canvas

                    val scale = min(size.width / imgW, size.height / imgH)
                    val drawW = imgW * scale
                    val drawH = imgH * scale
                    val offsetX = (size.width - drawW) / 2f
                    val offsetY = (size.height - drawH) / 2f

                    detections.forEach { detection ->
                        val box = detection.box
                        val isNormalized = box.all { it in 0f..1f }
                        val ymin = box[0] * (if (isNormalized) imgH else 1f)
                        val xmin = box[1] * (if (isNormalized) imgW else 1f)
                        val ymax = box[2] * (if (isNormalized) imgH else 1f)
                        val xmax = box[3] * (if (isNormalized) imgW else 1f)
                        drawRect(
                            color = Color(0xFFFF6F00),
                            topLeft = androidx.compose.ui.geometry.Offset(offsetX + xmin * scale, offsetY + ymin * scale),
                            size = androidx.compose.ui.geometry.Size(
                                width = ((xmax - xmin) * scale).coerceAtLeast(0f),
                                height = ((ymax - ymin) * scale).coerceAtLeast(0f)
                            ),
                            style = Stroke(width = 3f)
                        )
                    }

                    tapIndicatorNorm?.let { (nx, ny) ->
                        val cx = offsetX + nx * drawW
                        val cy = offsetY + ny * drawH
                        drawCircle(color = Color(0xCCFFFFFF), radius = 18f, center = Offset(cx, cy))
                        drawCircle(color = Color(0xFF2196F3), radius = 18f, center = Offset(cx, cy), style = Stroke(width = 4f))
                        drawCircle(color = Color(0xFF2196F3), radius = 6f, center = Offset(cx, cy))
                    }
                }
            }
        }
    } else {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("Loading photo...")
        }
    }
}
