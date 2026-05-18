/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.screen.actions

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cz.vut.oneshotdetector.view.layout.AppScreen
import cz.vut.oneshotdetector.view.layout.ScreenColumn
import cz.vut.oneshotdetector.view.state.PhotoPreview
import cz.vut.oneshotdetector.view.state.decodeImageForDisplay
import cz.vut.oneshotdetector.viewmodel.detect.SegmentationService
import cz.vut.oneshotdetector.viewmodel.model.ModelSelectionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun RemoveBackgroundScreen(
    photoUri: Uri,
    onApply: (resultFile: File) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var resultFile by remember { mutableStateOf<File?>(null) }
    var resultUri by remember { mutableStateOf<Uri?>(null) }
    var tapIndicatorNorm by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var processing by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { resultFile?.delete() }
    }

    val displayUri = resultUri ?: photoUri

    val onTap: (Float, Float) -> Unit = { normX, normY ->
        tapIndicatorNorm = normX to normY
        processing = true
        scope.launch {
            val file = withContext(Dispatchers.Default) {
                runCatching {
                    val segService = SegmentationService(context, ModelSelectionStore.getShared(context))
                    val bitmap = decodeImageForDisplay(context, photoUri) ?: return@runCatching null
                    val result = segService.segmentAtPoint(bitmap, normX, normY, removeBackground = true)
                    bitmap.recycle()
                    val f = File(context.cacheDir, "bg_removed_${System.currentTimeMillis()}.png")
                    f.outputStream().use { result.croppedBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    result.croppedBitmap.recycle()
                    f
                }.getOrNull()
            }
            processing = false
            if (file != null) {
                val old = resultFile
                resultFile = file
                resultUri = Uri.fromFile(file)
                tapIndicatorNorm = null
                withContext(Dispatchers.IO) { old?.delete() }
            } else {
                tapIndicatorNorm = null
            }
        }
    }

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
            ) {
                PhotoPreview(
                    uri = displayUri,
                    detections = emptyList(),
                    onTap = if (!processing) onTap else null,
                    tapIndicatorNorm = tapIndicatorNorm
                )
                if (processing) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color.White)
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
                if (resultFile != null) {
                    TextButton(
                        onClick = {
                            val old = resultFile
                            resultFile = null
                            resultUri = null
                            tapIndicatorNorm = null
                            scope.launch { withContext(Dispatchers.IO) { old?.delete() } }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, tint = Color.White)
                        Text(" Restore", color = Color.White)
                    }
                }
                Button(
                    onClick = { resultFile?.let { onApply(it) } },
                    modifier = Modifier.weight(1f),
                    enabled = resultFile != null && !processing
                ) {
                    Text("Apply")
                }
            }
        }
    }
}