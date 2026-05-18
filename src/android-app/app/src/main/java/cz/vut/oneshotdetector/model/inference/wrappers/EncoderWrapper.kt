/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.model.inference.wrappers

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import cz.vut.oneshotdetector.viewmodel.model.InferenceDevice
import java.nio.FloatBuffer
import kotlin.math.roundToInt

/**
 * Encoder output: the image embedding plus the geometry needed to map tap coordinates
 * back into the original image after mask decoding.
 */
data class EncoderOutput(
    /** Flat float array of shape [1 × 256 × 64 × 64]. */
    val embedding: FloatArray,
    /** Scale applied when resizing to longest-side 1024. */
    val scale: Float,
    val scaledW: Int,
    val scaledH: Int,
    val origW: Int,
    val origH: Int
)

/**
 * Wraps the SAM image encoder: preprocessing, session management, and inference.
 */
interface EncoderWrapper : AutoCloseable {
    /** Encode bitmap and return its image embedding with geometry metadata. */
    fun encode(bitmap: Bitmap): EncoderOutput
}

/**
 * ONNX Runtime implementation of EncoderWrapper SAM-compatible encoders.
 *
 * Expected model I/O:
 *   input  — "image"           [1, 3, 1024, 1024]
 *   output — "image_embedding" [1, 256, 64, 64]
 */
class OnnxEncoderWrapper(
    context: Context,
    assetPath: String,
    device: InferenceDevice = InferenceDevice.CPU
) : EncoderWrapper {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = run {
        Log.i(TAG, "Creating encoder session — device=$device asset=$assetPath")
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        env.createSession(bytes, OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            when (device) {
                InferenceDevice.CPU -> {
                    val threads = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
                    setIntraOpNumThreads(threads)
                    Log.i(TAG, "Encoder: using CPU ($threads threads)")
                }
                InferenceDevice.NPU -> {
                    val added = runCatching { addNnapi(); true }.getOrDefault(false)
                    if (added) {
                        Log.i(TAG, "Encoder: using NPU via NNAPI (CPU_DISABLED)")
                    } else {
                        val threads = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
                        setIntraOpNumThreads(threads)
                        Log.w(TAG, "Encoder: NNAPI unavailable, falling back to CPU ($threads threads)")
                    }
                }
                InferenceDevice.GPU -> {
                    val added = runCatching { addNnapi(); true }.getOrDefault(false)
                    if (added) {
                        Log.i(TAG, "Encoder: using GPU via NNAPI (CPU_DISABLED)")
                    } else {
                        val threads = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
                        setIntraOpNumThreads(threads)
                        Log.w(TAG, "Encoder: NNAPI unavailable, falling back to CPU ($threads threads)")
                    }
                }
            }
        })
    }

    override fun encode(bitmap: Bitmap): EncoderOutput {
        val origW = bitmap.width
        val origH = bitmap.height
        val scale = 1024f / maxOf(origW, origH)
        val scaledW = (origW * scale).roundToInt().coerceAtMost(1024)
        val scaledH = (origH * scale).roundToInt().coerceAtMost(1024)
        val inputArr = preprocessBitmap(bitmap, scaledW, scaledH)

        val embedding = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(inputArr), longArrayOf(1, 3, 1024, 1024)
        ).use { imgTensor ->
            session.run(mapOf("image" to imgTensor as OnnxTensorLike)).use { out ->
                val embTensor = out.get("image_embedding").get() as OnnxTensor
                val buf = embTensor.floatBuffer
                FloatArray(buf.remaining()).also { buf.get(it) }
            }
        }

        return EncoderOutput(embedding, scale, scaledW, scaledH, origW, origH)
    }

    override fun close() {
        session.close()
    }

    companion object {
        private const val TAG = "OnnxEncoderWrapper"
    }

    // ── Preprocessing ─────────────────────────────────────────────────────────

    /**
     * Resize to scaledW * scaledH, apply SAM pixel normalisation, and pack into a
     * NCHW float array padded to [3 × 1024 × 1024].
     *
     * SAM constants: mean = [123.675, 116.28, 103.53], std = [58.395, 57.12, 57.375]
     */
    private fun preprocessBitmap(bitmap: Bitmap, scaledW: Int, scaledH: Int): FloatArray {
        val resized = Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)
        val pixels = IntArray(scaledW * scaledH)
        resized.getPixels(pixels, 0, scaledW, 0, 0, scaledW, scaledH)
        if (resized !== bitmap) resized.recycle()

        val arr = FloatArray(3 * 1024 * 1024)
        val plane = 1024 * 1024
        for (y in 0 until scaledH) {
            for (x in 0 until scaledW) {
                val p = pixels[y * scaledW + x]
                val idx = y * 1024 + x
                arr[0 * plane + idx] = ((p shr 16 and 0xFF).toFloat() - 123.675f) / 58.395f
                arr[1 * plane + idx] = ((p shr 8  and 0xFF).toFloat() - 116.28f)  / 57.12f
                arr[2 * plane + idx] = ((p        and 0xFF).toFloat() - 103.53f)  / 57.375f
            }
        }
        return arr
    }
}

fun createEncoderWrapper(
    context: Context,
    assetPath: String,
    @Suppress("UNUSED_PARAMETER") tag: String,
    device: InferenceDevice = InferenceDevice.CPU
): EncoderWrapper = OnnxEncoderWrapper(context, assetPath, device)
