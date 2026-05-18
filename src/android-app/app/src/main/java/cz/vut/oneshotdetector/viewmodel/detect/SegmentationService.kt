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
import cz.vut.oneshotdetector.model.inference.wrappers.DecoderOutput
import cz.vut.oneshotdetector.model.inference.wrappers.DecoderWrapper
import cz.vut.oneshotdetector.model.inference.wrappers.EncoderOutput
import cz.vut.oneshotdetector.model.inference.wrappers.EncoderWrapper
import cz.vut.oneshotdetector.model.inference.wrappers.createDecoderWrapper
import cz.vut.oneshotdetector.model.inference.wrappers.createEncoderWrapper
import cz.vut.oneshotdetector.view.state.decodeImageWithExif
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * ROI detection service backed by a SAM-compatible encoder + decoder pair.
 */

data class SegmentAtPointResult(
    val croppedBitmap: Bitmap,
    /** Normalised bounding box in the source image, ordered as ymin, xmin, ymax, xmax. */
    val box: FloatArray,
    val inferenceTimeMs: Long
)

class SegmentationService(
    private val context: Context,
    private val store: ModelSelectionStore = ModelSelectionStore.getShared(context)
) : DetectionService {

    private var encoderWrapper: EncoderWrapper? = null
    private var decoderWrapper: DecoderWrapper? = null
    private var loadedEncoderPath: String? = null
    private var loadedEncoderTag: String? = null
    private var loadedDecoderPath: String? = null
    private var loadedDecoderTag: String? = null
    private var loadedEncoderDevice: InferenceDevice? = null
    private var loadedDecoderDevice: InferenceDevice? = null

    /** Cached encoder output keyed by bitmap identity hash. */
    private var encoderCache: Pair<Int, EncoderOutput>? = null

    // ── DetectionService ───────────────────────────────────────────────────

    override suspend fun detectImage(targetImageUri: String): DetectionResult =
        withContext(Dispatchers.Default) {
            val bitmap = withContext(Dispatchers.IO) {
                decodeImageWithExif(context, targetImageUri.toUri())
            } ?: error("Failed to decode image from URI.")
            emptyResult(bitmap)
        }

    override suspend fun detectFrame(frameBitmap: Bitmap): DetectionResult =
        withContext(Dispatchers.Default) { emptyResult(frameBitmap) }

    private fun emptyResult(bitmap: Bitmap) = DetectionResult(
        detections      = emptyList(),
        summary         = "${store.getActiveVariant(ModelType.SegmentEncoder).label}: tap the preview to segment.",
        inferenceTimeMs = 0L,
        imageWidth      = bitmap.width,
        imageHeight     = bitmap.height
    )

    // Tap-to-segment

    /**
     * Segments the object at the given normalized x and y coordinates in the range 0 to 1.
     */
    suspend fun segmentAtPoint(
        bitmap: Bitmap,
        normX: Float,
        normY: Float,
        removeBackground: Boolean = false,
        onStatus: ((String) -> Unit)? = null
    ): SegmentAtPointResult = withContext(Dispatchers.Default) {
        val startedAt = System.currentTimeMillis()
        val (enc, dec) = getOrCreateWrappers()
        val bitmapId = System.identityHashCode(bitmap)

        val encoderOutput: EncoderOutput = encoderCache
            ?.takeIf { it.first == bitmapId }?.second
            ?: run {
                onStatus?.invoke("Encoding image (slow first time)…")
                enc.encode(bitmap).also { encoderCache = bitmapId to it }
            }

        onStatus?.invoke("Decoding mask…")
        val decoderOutput = dec.decode(encoderOutput, normX, normY)

        val (crop, box) = extractCrop(bitmap, encoderOutput, decoderOutput, normX, normY, removeBackground)
        SegmentAtPointResult(crop, box, System.currentTimeMillis() - startedAt)
    }

    /**
     * Pre-encodes the bitmap and stores the result in cache.
     */
    suspend fun preEncodeImage(
        bitmap: Bitmap,
        onStatus: ((String) -> Unit)? = null
    ) = withContext(Dispatchers.Default) {
        val bitmapId = System.identityHashCode(bitmap)
        if (encoderCache?.first == bitmapId) return@withContext
        onStatus?.invoke("Encoding image (${store.getActiveVariant(ModelType.SegmentEncoder).label})…")
        val (enc, _) = getOrCreateWrappers()
        encoderCache = bitmapId to enc.encode(bitmap)
        onStatus?.invoke("Ready — tap to segment.")
    }

    // Wrapper lifecycle

    private fun getOrCreateWrappers(): Pair<EncoderWrapper, DecoderWrapper> {
        val encoderVariant = store.getActiveVariant(ModelType.SegmentEncoder)
        val decoderVariant = store.getActiveVariant(ModelType.SegmentDecoder)
        val encoderPath    = encoderVariant.assetPath
        val encoderTag     = encoderVariant.tag
        val decoderPath    = decoderVariant.assetPath
        val decoderTag     = decoderVariant.tag
        val encoderDevice  = store.getActiveDevice(ModelType.SegmentEncoder)
        val decoderDevice  = store.getActiveDevice(ModelType.SegmentDecoder)

        if (encoderWrapper != null &&
            (loadedEncoderPath != encoderPath || loadedEncoderTag != encoderTag || loadedEncoderDevice != encoderDevice)) {
            encoderWrapper?.close(); encoderWrapper = null
            encoderCache = null   // cached embedding belongs to the old encoder / device
        }
        if (decoderWrapper != null &&
            (loadedDecoderPath != decoderPath || loadedDecoderTag != decoderTag || loadedDecoderDevice != decoderDevice)) {
            decoderWrapper?.close(); decoderWrapper = null
        }

        val enc = encoderWrapper ?: createEncoderWrapper(context, encoderPath, encoderTag, encoderDevice)
            .also { encoderWrapper = it; loadedEncoderPath = encoderPath; loadedEncoderTag = encoderTag; loadedEncoderDevice = encoderDevice }
        val dec = decoderWrapper ?: createDecoderWrapper(
            context,
            decoderPath,
            decoderTag,
            decoderDevice
        )
            .also { decoderWrapper = it; loadedDecoderPath = decoderPath; loadedDecoderTag = decoderTag; loadedDecoderDevice = decoderDevice }

        return enc to dec
    }

    // Crop extraction

    private fun extractCrop(
        bitmap: Bitmap,
        enc: EncoderOutput,
        dec: DecoderOutput,
        normX: Float,
        normY: Float,
        removeBackground: Boolean
    ): Pair<Bitmap, FloatArray> {
        val tapX = (normX * enc.scaledW).roundToInt().coerceIn(0, enc.scaledW - 1)
        val tapY = (normY * enc.scaledH).roundToInt().coerceIn(0, enc.scaledH - 1)
        val bestIdx = selectMaskIndex(dec, tapX, tapY, enc.scaledW, enc.scaledH)
        val bestMask = dec.masks.copyOfRange(bestIdx * dec.planeSize, (bestIdx + 1) * dec.planeSize)

        // Ignore padded encoder area outside the resized image content.
        // FP16 models can produce small positive noise there, which would inflate
        // the crop box all the way to the image borders after scale-back.
        var yMin = enc.scaledH; var yMax = -1; var xMin = enc.scaledW; var xMax = -1
        for (y in 0 until enc.scaledH) {
            for (x in 0 until enc.scaledW) {
                if (bestMask[y * 1024 + x] > 0f) {
                    if (y < yMin) yMin = y; if (y > yMax) yMax = y
                    if (x < xMin) xMin = x; if (x > xMax) xMax = x
                }
            }
        }

        if (yMax < 0) return fallbackCrop(bitmap, normX, normY)

        val x0 = (xMin / enc.scale).roundToInt().coerceIn(0, bitmap.width - 1)
        val y0 = (yMin / enc.scale).roundToInt().coerceIn(0, bitmap.height - 1)
        val x1 = (xMax / enc.scale).roundToInt().coerceAtMost(bitmap.width)
        val y1 = (yMax / enc.scale).roundToInt().coerceAtMost(bitmap.height)
        val cropW = (x1 - x0).coerceAtLeast(1)
        val cropH = (y1 - y0).coerceAtLeast(1)

        val box = floatArrayOf(
            y0.toFloat() / bitmap.height, x0.toFloat() / bitmap.width,
            y1.toFloat() / bitmap.height, x1.toFloat() / bitmap.width
        )

        val crop = if (removeBackground) {
            val pixels = IntArray(cropW * cropH)
            bitmap.getPixels(pixels, 0, cropW, x0, y0, cropW, cropH)
            for (cy in 0 until cropH) {
                for (cx in 0 until cropW) {
                    val encX = ((x0 + cx) * enc.scale).roundToInt().coerceIn(0, enc.scaledW - 1)
                    val encY = ((y0 + cy) * enc.scale).roundToInt().coerceIn(0, enc.scaledH - 1)
                    if (bestMask[encY * 1024 + encX] <= 0f) pixels[cy * cropW + cx] = 0
                }
            }
            Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
                .also { it.setPixels(pixels, 0, cropW, 0, 0, cropW, cropH) }
        } else {
            Bitmap.createBitmap(bitmap, x0, y0, cropW, cropH)
        }

        return crop to box
    }

    private fun selectMaskIndex(
        dec: DecoderOutput,
        tapX: Int,
        tapY: Int,
        validW: Int,
        validH: Int
    ): Int {
        val tapFlatIndex = tapY * 1024 + tapX
        var bestContainingIdx = -1
        var bestContainingArea = -1
        var bestContainingScore = Float.NEGATIVE_INFINITY

        for (maskIdx in 0 until dec.numMasks) {
            val offset = maskIdx * dec.planeSize
            if (dec.masks[offset + tapFlatIndex] <= 0f) continue

            var area = 0
            for (y in 0 until validH) {
                val rowBase = offset + y * 1024
                for (x in 0 until validW) {
                    if (dec.masks[rowBase + x] > 0f) area++
                }
            }

            val score = dec.iouScores[maskIdx]
            if (area > bestContainingArea || (area == bestContainingArea && score > bestContainingScore)) {
                bestContainingIdx = maskIdx
                bestContainingArea = area
                bestContainingScore = score
            }
        }

        if (bestContainingIdx >= 0) return bestContainingIdx
        return dec.bestMaskIndex()
    }

    private fun fallbackCrop(bitmap: Bitmap, normX: Float, normY: Float): Pair<Bitmap, FloatArray> {
        val cx = (normX * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
        val cy = (normY * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
        val half = (bitmap.width.coerceAtMost(bitmap.height) / 4).coerceAtLeast(1)
        val x0 = (cx - half).coerceAtLeast(0); val y0 = (cy - half).coerceAtLeast(0)
        val x1 = (cx + half).coerceAtMost(bitmap.width); val y1 = (cy + half).coerceAtMost(bitmap.height)
        return Bitmap.createBitmap(bitmap, x0, y0, x1 - x0, y1 - y0) to floatArrayOf(
            y0.toFloat() / bitmap.height, x0.toFloat() / bitmap.width,
            y1.toFloat() / bitmap.height, x1.toFloat() / bitmap.width
        )
    }
}
