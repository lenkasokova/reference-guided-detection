/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.model.inference.wrappers

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import cz.vut.oneshotdetector.viewmodel.model.InferenceDevice
import cz.vut.oneshotdetector.model.inference.Detection
import cz.vut.oneshotdetector.viewmodel.model.shouldUseGpuDelegate
import cz.vut.oneshotdetector.viewmodel.model.shouldUseNnapiDelegate

/**
 * Wraps an object detection model: loading, inference, and result mapping.
 */

interface DetectionWrapper : AutoCloseable {
    /** Run detection on bitmap and return the mapped detections. */
    fun detect(bitmap: Bitmap): List<Detection>

    /** The delegate that was actually loaded*/
    val activeDevice: InferenceDevice
}

fun createDetectionWrapper(
    context: Context,
    assetPath: String,
    device: InferenceDevice = InferenceDevice.CPU
): DetectionWrapper = MediaPipeDetectionWrapper(context, assetPath, device)

/**
 * MediaPipe implementation of DetectionWrapper.
 */
class MediaPipeDetectionWrapper(
    private val context: Context,
    private val assetPath: String,
    device: InferenceDevice = InferenceDevice.CPU,
    private val scoreThreshold: Float = 0.3f,
    private val maxResults: Int = 5
) : DetectionWrapper {

    private var _activeDevice: InferenceDevice
    override val activeDevice: InferenceDevice get() = _activeDevice

    private var detector: ObjectDetector

    private var usingNonCpu: Boolean

    init {
        val preferredDelegate = when {
            device.shouldUseNnapiDelegate() -> Delegate.NPU
            device.shouldUseGpuDelegate()   -> Delegate.GPU
            else                            -> Delegate.CPU
        }
        var usedDevice = device
        detector = setupDetector(preferredDelegate) { fallbackDevice ->
            usedDevice = fallbackDevice
        }
        _activeDevice = usedDevice
        usingNonCpu = usedDevice != InferenceDevice.CPU
    }

    /**
     * Creates an Object Detection with delegate, falling back to CPU on RuntimeException
     */
    private fun setupDetector(
        delegate: Delegate,
        onFallback: (InferenceDevice) -> Unit = {}
    ): ObjectDetector {
        return try {
            createDetector(delegate)
        } catch (e: RuntimeException) {
            // GPU_ERROR: delegate initialisation failed (driver issue, unsupported model, etc.)
            Log.w(TAG, "GPU_ERROR: failed to init $delegate delegate, falling back to CPU", e)
            onFallback(InferenceDevice.CPU)
            try {
                createDetector(Delegate.CPU)
            } catch (e2: IllegalStateException) {
                Log.e(TAG, "OTHER_ERROR: CPU fallback also failed", e2)
                throw e2
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "OTHER_ERROR: detector setup failed", e)
            throw e
        }
    }

    private fun createDetector(delegate: Delegate): ObjectDetector {
        val baseBuilder = BaseOptions.builder().setModelAssetPath(assetPath)
        if (delegate != Delegate.CPU) baseBuilder.setDelegate(delegate)
        return ObjectDetector.createFromOptions(
            context,
            ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseBuilder.build())
                .setScoreThreshold(scoreThreshold)
                .setMaxResults(maxResults)
                .build()
        )
    }

    override fun detect(bitmap: Bitmap): List<Detection> {
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result =
            try {
                detector.detect(mpImage)
            } catch (e: RuntimeException) {
                // GPU_ERROR at inference time, switch to CPU
                if (usingNonCpu) {
                    Log.w(TAG, "GPU_ERROR during inference, switching to CPU", e)
                    runCatching { detector.close() }
                    usingNonCpu = false
                    _activeDevice = InferenceDevice.CPU
                    detector = try {
                        createDetector(Delegate.CPU)
                    } catch (e2: IllegalStateException) {
                        Log.e(TAG, "OTHER_ERROR: CPU fallback detector creation failed", e2)
                        return emptyList()
                    }
                    try {
                        detector.detect(BitmapImageBuilder(bitmap).build())
                    } catch (e2: RuntimeException) {
                        Log.e(TAG, "CPU inference also failed", e2)
                        return emptyList()
                    }
                } else {
                    Log.e(TAG, "OTHER_ERROR: CPU inference failed", e)
                    return emptyList()
                }
            }

        return result.detections().mapNotNull { raw ->
            val top = raw.categories().maxByOrNull(Category::score) ?: return@mapNotNull null
            val label = top.categoryName()?.ifBlank { top.displayName() }.orEmpty()
            val box = mapBox(raw.boundingBox(), bitmap.width, bitmap.height)
                ?: return@mapNotNull null
            Detection(
                label            = label,
                score            = top.score(),
                box              = box,
                alphaScore       = top.score(),
                similarityCosine = null
            )
        }
    }

    override fun close() = detector.close()

    companion object {
        private const val TAG = "DetectionWrapper"
    }

    private fun mapBox(
        rect: android.graphics.RectF,
        imageWidth: Int,
        imageHeight: Int
    ): FloatArray? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        return floatArrayOf(
            (rect.top    / imageHeight).coerceIn(0f, 1f),
            (rect.left   / imageWidth).coerceIn(0f, 1f),
            (rect.bottom / imageHeight).coerceIn(0f, 1f),
            (rect.right  / imageWidth).coerceIn(0f, 1f)
        )
    }
}
