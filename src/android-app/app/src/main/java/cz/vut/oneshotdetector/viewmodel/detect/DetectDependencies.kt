/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.viewmodel.detect

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import cz.vut.oneshotdetector.viewmodel.model.ModelSelectionStore
import cz.vut.oneshotdetector.viewmodel.model.InferenceDevice
import cz.vut.oneshotdetector.viewmodel.model.ModelType
import cz.vut.oneshotdetector.model.data.gallery.GalleryImage
import cz.vut.oneshotdetector.model.inference.wrappers.createEmbeddingWrapper
import cz.vut.oneshotdetector.view.state.decodeImageWithExif
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

interface EmbeddingEngine {
    fun computeEmbedding(bitmap: Bitmap, notes: MutableList<String>): FloatArray?
    fun decodeStoredEmbedding(embeddingBytes: ByteArray): FloatArray?
    fun cosineSimilarity(sourceEmbedding: FloatArray, referenceEmbedding: FloatArray): Float?
}

interface ImageDecoder {
    fun decode(uri: Uri): Bitmap?
}

interface GalleryDataSource {
    fun getImages(): List<GalleryImage>
    suspend fun getEmbedding(uri: String): ByteArray?
}

class DefaultEmbeddingEngine(
    private val context: Context,
    private val store: ModelSelectionStore = ModelSelectionStore.getShared(context)
) : EmbeddingEngine {

    private var cachedWrapper: cz.vut.oneshotdetector.model.inference.wrappers.EmbeddingWrapper? = null
    private var cachedVariantId: String? = null
    private var cachedDevice: InferenceDevice? = null

    private fun getOrCreateWrapper(
        variantId: String,
        assetPath: String,
        tag: String,
        device: InferenceDevice
    ): cz.vut.oneshotdetector.model.inference.wrappers.EmbeddingWrapper {
        if (cachedWrapper != null && cachedVariantId == variantId && cachedDevice == device) {
            return cachedWrapper!!
        }
        cachedWrapper?.close()
        cachedWrapper = createEmbeddingWrapper(context, assetPath, tag, device)
        cachedVariantId = variantId
        cachedDevice = device
        return cachedWrapper!!
    }

    override fun computeEmbedding(bitmap: Bitmap, notes: MutableList<String>): FloatArray? {
        val variant = store.getActiveVariant(ModelType.Embedding)
        val device = store.getActiveDevice(ModelType.Embedding)
        return runCatching {
            getOrCreateWrapper(variant.id, variant.assetPath, variant.tag, device).embed(bitmap)
        }.getOrElse {
            notes.add("Embedding failed: ${it.message}")
            null
        }
    }

    override fun decodeStoredEmbedding(embeddingBytes: ByteArray): FloatArray? =
        bytesToEmbeddingVector(embeddingBytes)

    override fun cosineSimilarity(
        sourceEmbedding: FloatArray,
        referenceEmbedding: FloatArray
    ): Float? {
        if (sourceEmbedding.isEmpty() || referenceEmbedding.isEmpty()) return null
        val size = minOf(sourceEmbedding.size, referenceEmbedding.size)
        if (size == 0) return null

        var dot = 0f
        var sourceNorm = 0f
        var referenceNorm = 0f
        for (i in 0 until size) {
            dot += sourceEmbedding[i] * referenceEmbedding[i]
            sourceNorm += sourceEmbedding[i] * sourceEmbedding[i]
            referenceNorm += referenceEmbedding[i] * referenceEmbedding[i]
        }

        return if (sourceNorm <= 0f || referenceNorm <= 0f) {
            null
        } else {
            dot / (sqrt(sourceNorm) * sqrt(referenceNorm))
        }
    }

    private fun bytesToEmbeddingVector(embeddingBytes: ByteArray): FloatArray? {
        if (embeddingBytes.isEmpty()) return null
        if (embeddingBytes.size % 4 == 0) {
            val buffer = ByteBuffer.wrap(embeddingBytes).order(ByteOrder.LITTLE_ENDIAN)
            val out = FloatArray(embeddingBytes.size / 4)
            for (i in out.indices) out[i] = buffer.float
            if (out.isNotEmpty()) return out
        }
        return embeddingBytes.map { it.toFloat() / 127f }.toFloatArray()
    }
}

class ExifImageDecoder(
    private val context: Context
) : ImageDecoder {
    override fun decode(uri: Uri): Bitmap? = decodeImageWithExif(context, uri)
}
