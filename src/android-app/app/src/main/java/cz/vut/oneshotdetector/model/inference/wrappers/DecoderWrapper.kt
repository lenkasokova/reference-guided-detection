/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.model.inference.wrappers

import kotlin.also
import kotlin.apply
import kotlin.collections.indices
import kotlin.collections.maxByOrNull
import kotlin.getOrDefault
import kotlin.io.readBytes
import kotlin.io.use
import kotlin.ranges.coerceAtMost
import kotlin.run
import kotlin.runCatching
import kotlin.to
import kotlin.use

/**
 * Decoder output: raw mask logits and IoU confidence scores for each mask candidate.
 *
 * The decoder produces [numMasks] candidates (typically 3). Callers pick the best mask
 * by comparing [iouScores], then threshold [masks] at 0 to get a binary mask.
 */
data class DecoderOutput(
    /** Flat mask logits, shape [numMasks × 1024 × 1024]. */
    val masks: FloatArray,
    /** IoU confidence score per mask candidate. Length = numMasks. */
    val iouScores: FloatArray
) {
    val numMasks: Int get() = iouScores.size
    val planeSize: Int get() = 1024 * 1024
    fun bestMaskIndex(): Int = iouScores.indices.maxByOrNull { iouScores[it] } ?: 0
}

/**
 * Wraps the SAM prompt decoder: prompt encoding, session management, and mask decoding.
 */
interface DecoderWrapper : kotlin.AutoCloseable {
    /**
     * Run the decoder on the given encoderOutput and tap point, returning the raw mask logits and
     */
    fun decode(encoderOutput: cz.vut.oneshotdetector.model.inference.wrappers.EncoderOutput, normX: Float, normY: Float): DecoderOutput
}

fun createDecoderWrapper(
    context: android.content.Context,
    assetPath: String,
    @Suppress("UNUSED_PARAMETER") tag: String,
    device: cz.vut.oneshotdetector.viewmodel.model.InferenceDevice = cz.vut.oneshotdetector.viewmodel.model.InferenceDevice.CPU
): DecoderWrapper = OnnxDecoderWrapper(context, assetPath, device)

/**
 * ONNX Runtime implementation of Decoder wrapper for SAM-compatible decoders.
 *
 * Expected model I/O:
 *   inputs  — "image_embedding" [1, 256, 64, 64]
 *             "point_coords"    [1, 2, 2]
 *             "point_labels"    [1, 2]
 *             "has_mask_input"  [1]
 *             "mask_input"      [1, 1, 256, 256]
 *   outputs — "masks"           [1, numMasks, 1024, 1024]
 *             "iou_predictions" [1, numMasks]
 */
class OnnxDecoderWrapper(
    context: android.content.Context,
    assetPath: String,
    device: cz.vut.oneshotdetector.viewmodel.model.InferenceDevice = cz.vut.oneshotdetector.viewmodel.model.InferenceDevice.CPU
) : DecoderWrapper {

    private val env: ai.onnxruntime.OrtEnvironment = ai.onnxruntime.OrtEnvironment.getEnvironment()
    private val session: ai.onnxruntime.OrtSession = run {
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        env.createSession(bytes, ai.onnxruntime.OrtSession.SessionOptions().apply {
            setOptimizationLevel(ai.onnxruntime.OrtSession.SessionOptions.OptLevel.ALL_OPT)
            val threads = java.lang.Runtime.getRuntime().availableProcessors().coerceAtMost(4)
            when (device) {
                cz.vut.oneshotdetector.viewmodel.model.InferenceDevice.CPU -> setIntraOpNumThreads(threads)
                cz.vut.oneshotdetector.viewmodel.model.InferenceDevice.NPU -> {
                    val added = runCatching { addNnapi(); true }.getOrDefault(false)
                    if (!added) setIntraOpNumThreads(threads)
                }
                cz.vut.oneshotdetector.viewmodel.model.InferenceDevice.GPU -> {
                    val added = runCatching { addNnapi(); true }.getOrDefault(false)
                    if (!added) setIntraOpNumThreads(threads)
                }
            }
        })
    }

    override fun decode(encoderOutput: cz.vut.oneshotdetector.model.inference.wrappers.EncoderOutput, normX: Float, normY: Float): DecoderOutput {
        // Map normalised tap point to encoder
        val px = normX * encoderOutput.scaledW
        val py = normY * encoderOutput.scaledH

        val pcTensor = ai.onnxruntime.OnnxTensor.createTensor(
            env, java.nio.FloatBuffer.wrap(floatArrayOf(px, py, 0f, 0f)), longArrayOf(1, 2, 2)
        )
        val plTensor = ai.onnxruntime.OnnxTensor.createTensor(
            env, java.nio.FloatBuffer.wrap(floatArrayOf(1f, -1f)), longArrayOf(1, 2)
        )
        val hmTensor = ai.onnxruntime.OnnxTensor.createTensor(
            env, java.nio.FloatBuffer.wrap(floatArrayOf(0f)), longArrayOf(1)
        )
        val miTensor = ai.onnxruntime.OnnxTensor.createTensor(
            env, java.nio.FloatBuffer.wrap(FloatArray(1 * 1 * 256 * 256)),
            longArrayOf(1, 1, 256, 256)
        )
        val emTensor = ai.onnxruntime.OnnxTensor.createTensor(
            env, java.nio.FloatBuffer.wrap(encoderOutput.embedding), longArrayOf(1, 256, 64, 64)
        )

        val inputs = kotlin.collections.mapOf<String, ai.onnxruntime.OnnxTensorLike>(
            "point_coords" to pcTensor,
            "point_labels" to plTensor,
            "image_embedding" to emTensor,
            "has_mask_input" to hmTensor,
            "mask_input" to miTensor
        )

        try {
            session.run(inputs).use { out ->
                val mTensor   = out.get("masks").get() as ai.onnxruntime.OnnxTensor
                val iouTensor = out.get("iou_predictions").get() as ai.onnxruntime.OnnxTensor
                val mBuf = mTensor.floatBuffer
                val iBuf = iouTensor.floatBuffer
                return DecoderOutput(
                    masks     = FloatArray(mBuf.remaining()).also { mBuf.get(it) },
                    iouScores = FloatArray(iBuf.remaining()).also { iBuf.get(it) }
                )
            }
        } finally {
            pcTensor.close(); plTensor.close(); hmTensor.close()
            miTensor.close(); emTensor.close()
        }
    }

    override fun close() {
        session.close()
    }
}

