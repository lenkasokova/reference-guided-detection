/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.screen.gallery.components

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.net.toUri
import cz.vut.oneshotdetector.view.state.decodeImageWithExif
import cz.vut.oneshotdetector.view.theme.LocalSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun GalleryThumbnail(uri: String, label: String = "") {
    val context = LocalContext.current
    val spacing = LocalSpacing.current
    val previewLabel = label
        .replace(Regex("\\s+"), " ")
        .trim()
        .let { text ->
            if (text.length > 24) "${text.take(21)}..." else text
        }
    val imageBitmap by produceState(initialValue = null as androidx.compose.ui.graphics.ImageBitmap?) {
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                decodeImageWithExif(context, uri.toUri(), inSampleSize = 8)
            }.getOrNull()
        }
        value = bitmap?.asImageBitmap()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            if (imageBitmap != null) {
                Image(
                    bitmap = imageBitmap!!,
                    contentDescription = "Saved gallery image",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(spacing.sm),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("...", color = MaterialTheme.colorScheme.onTertiary)
                }
            }
        }
        if (previewLabel.isNotEmpty()) {
            Text(
                text = previewLabel,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = spacing.xs, vertical = spacing.xs),
                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onTertiary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}
