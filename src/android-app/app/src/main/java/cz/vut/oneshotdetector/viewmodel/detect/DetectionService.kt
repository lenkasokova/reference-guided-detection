/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.viewmodel.detect

import android.content.Context
import android.graphics.Bitmap
import androidx.core.net.toUri
import cz.vut.oneshotdetector.viewmodel.model.InferenceDevice
import cz.vut.oneshotdetector.viewmodel.model.ModelSelectionStore
import cz.vut.oneshotdetector.viewmodel.model.ModelType
import cz.vut.oneshotdetector.model.inference.wrappers.MediaPipeDetectionWrapper
import cz.vut.oneshotdetector.view.state.decodeImageWithExif
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class DetectionResult(
    val detections: List<cz.vut.oneshotdetector.model.inference.Detection>,
    val summary: String,
    val inferenceTimeMs: Long,
    val imageWidth: Int,
    val imageHeight: Int
)

interface DetectionService {
    suspend fun detectImage(targetImageUri: String): DetectionResult
    suspend fun detectFrame(frameBitmap: Bitmap): DetectionResult
}

/**
 * ROI detection service backed by MediaPipeDetectionWrapper.
 */
class ObjectDetectionService(
    private val context: Context,
    private val store: ModelSelectionStore = ModelSelectionStore.getShared(context)
) : DetectionService {

    private val mutex = Mutex()
    private var wrapper: MediaPipeDetectionWrapper? = null
    private var loadedAssetPath: String? = null
    private var loadedDevice: InferenceDevice? = null

    override suspend fun detectImage(targetImageUri: String): DetectionResult =
        withContext(Dispatchers.Default) {
            val bitmap = withContext(Dispatchers.IO) {
                decodeImageWithExif(context, targetImageUri.toUri())
            } ?: error("Failed to decode image from URI.")
            detectBitmap(bitmap)
        }

    override suspend fun detectFrame(frameBitmap: Bitmap): DetectionResult =
        withContext(Dispatchers.Default) { detectBitmap(frameBitmap) }

    private suspend fun detectBitmap(bitmap: Bitmap): DetectionResult {
        val startedAt = System.currentTimeMillis()
        val detections = mutex.withLock {
            getOrCreateWrapper().detect(bitmap)
        }
        val elapsedMs = System.currentTimeMillis() - startedAt
        val modelName = store.getActiveVariant(ModelType.Detection).label
        return DetectionResult(
            detections     = detections,
            summary        = if (detections.isEmpty()) "$modelName found no ROI."
                             else "$modelName found ${detections.size} ROI(s).",
            inferenceTimeMs = elapsedMs,
            imageWidth     = bitmap.width,
            imageHeight    = bitmap.height
        )
    }

    private fun getOrCreateWrapper(): MediaPipeDetectionWrapper {
        val assetPath = store.getActivePath(ModelType.Detection)
        val device = store.getActiveDevice(ModelType.Detection)
        if (wrapper != null && loadedAssetPath == assetPath && loadedDevice == device) return wrapper!!
        wrapper?.close()
        return MediaPipeDetectionWrapper(context, assetPath, device).also {
            wrapper = it
            loadedAssetPath = assetPath
            loadedDevice = device
            store.reportActualDevice(ModelType.Detection, it.activeDevice)
        }
    }
}
