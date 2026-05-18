/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.model.inference.wrappers

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import cz.vut.oneshotdetector.viewmodel.model.InferenceDevice
import cz.vut.oneshotdetector.viewmodel.model.shouldUseGpuDelegate
import cz.vut.oneshotdetector.viewmodel.model.shouldUseNnapiDelegate
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import kotlin.math.sqrt

/**
 * Wraps an image embedding model: loading, inference, and vector extraction.
 */
interface EmbeddingWrapper : AutoCloseable {
    /** Compute a floating-point embedding vector for bitmap. Returns null on failure. */
    fun embed(bitmap: Bitmap): FloatArray?

    /** Cosine similarity in [−1, 1] between two embedding vectors. */
    fun similarity(a: FloatArray, b: FloatArray): Float
}

fun createEmbeddingWrapper(
    context: Context,
    assetPath: String,
    tag: String,
    device: InferenceDevice = InferenceDevice.CPU
): EmbeddingWrapper = when (tag) {
    "tflite" -> TFLiteEmbeddingWrapper(
        context = context,
        assetPath = assetPath,
        meanRgb = floatArrayOf(0.485f, 0.456f, 0.406f),
        stdRgb = floatArrayOf(0.229f, 0.224f, 0.225f),
        device = device
    )
    else -> throw IllegalArgumentException("Unsupported embedding runtime tag: $tag")
}

/**
 * LiteRT of Compiled Model implementation of Embeddi
 *
 * Expected model contract:
 *  - Input:  [1, H, W, 3] float32 (NHWC) or [1, 3, H, W] float32 (NCHW)
 *  - Output: [1, embedding_dim] float32, ideally L2-normalised
 */
class TFLiteEmbeddingWrapper(
    context: Context,
    assetPath: String,
    private val meanRgb: FloatArray = floatArrayOf(0f, 0f, 0f),
    private val stdRgb: FloatArray = floatArrayOf(1f, 1f, 1f),
    numThreads: Int = 4,
    device: InferenceDevice = InferenceDevice.CPU
) : EmbeddingWrapper {

    private val compiledModel: CompiledModel
    private val inputBuffers: List<TensorBuffer>
    private val outputBuffers: List<TensorBuffer>

    private val isNchw: Boolean
    private val inputH: Int
    private val inputW: Int
    private val pixels: IntArray
    private val inputFloats: FloatArray

    init {
        fun cpuOptions() = CompiledModel.Options.CPU.also {
            it.cpuOptions = CompiledModel.CpuOptions(numThreads)
        }

        val options = when {
            device.shouldUseNnapiDelegate() ->
                runCatching { CompiledModel.Options(Accelerator.NPU) }.getOrElse { cpuOptions() }
            device.shouldUseGpuDelegate() ->
                runCatching { CompiledModel.Options(Accelerator.GPU) }.getOrElse { cpuOptions() }
            else -> cpuOptions()
        }

        compiledModel = runCatching {
            CompiledModel.create(context.assets, assetPath, options)
        }.getOrElse {
            Log.w("TFLiteEmbeddingWrapper", "${device.label} init failed, falling back to CPU", it)
            CompiledModel.create(context.assets, assetPath, cpuOptions())
        }

        inputBuffers = compiledModel.createInputBuffers()
        outputBuffers = compiledModel.createOutputBuffers()

        // MobileNet V3 Large uses 224×224 NHWC input — fixed by architecture.
        inputH = 224
        inputW = 224
        isNchw = false
        pixels = IntArray(inputW * inputH)
        inputFloats = FloatArray(inputH * inputW * 3)
    }

    override fun embed(bitmap: Bitmap): FloatArray? = runCatching {
        val resized = if (bitmap.width == inputW && bitmap.height == inputH) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, inputW, inputH, true)
        }

        resized.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)
        if (resized !== bitmap) resized.recycle()

        for (i in pixels.indices) {
            val px = pixels[i]
            inputFloats[i * 3 + 0] = ((px shr 16 and 0xFF) / 255f - meanRgb[0]) / stdRgb[0]
            inputFloats[i * 3 + 1] = ((px shr  8 and 0xFF) / 255f - meanRgb[1]) / stdRgb[1]
            inputFloats[i * 3 + 2] = ((px        and 0xFF) / 255f - meanRgb[2]) / stdRgb[2]
        }

        inputBuffers[0].writeFloat(inputFloats)
        compiledModel.run(inputBuffers, outputBuffers)
        outputBuffers[0].readFloat()
    }.getOrNull()

    override fun similarity(a: FloatArray, b: FloatArray): Float {
        val size = minOf(a.size, b.size)
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in 0 until size) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na <= 0f || nb <= 0f) 0f else dot / (sqrt(na) * sqrt(nb))
    }

    override fun close() {
        inputBuffers.forEach { it.close() }
        outputBuffers.forEach { it.close() }
        compiledModel.close()
    }
}
