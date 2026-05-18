/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.viewmodel.benchmark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads input bitmaps from the dataset path in the benchmark config.
 */
class BenchmarkDataset(
    private val context: Context,
    private val config: BenchmarkConfig
) {
    private val supportedExtensions = setOf("jpg", "jpeg", "png", "webp", "bmp")

    /**
     * Returns up to the configured maximum number of decoded bitmaps.
     * Falls back to a single blank bitmap if the asset folder is empty or missing.
     */
    suspend fun loadBitmaps(): List<Bitmap> = withContext(Dispatchers.IO) {
        val names = runCatching {
            context.assets.list(config.datasetAssetPath) ?: emptyArray()
        }.getOrDefault(emptyArray())

        val imageNames = names
            .filter { it.substringAfterLast('.').lowercase() in supportedExtensions }
            .take(config.maxDatasetImages)

        val bitmaps = imageNames.mapNotNull { name ->
            runCatching {
                context.assets.open("${config.datasetAssetPath}/$name").use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }.getOrNull()
        }

        bitmaps.ifEmpty {
            listOf(Bitmap.createBitmap(config.fallbackImageSize, config.fallbackImageSize, Bitmap.Config.ARGB_8888))
        }
    }
}
