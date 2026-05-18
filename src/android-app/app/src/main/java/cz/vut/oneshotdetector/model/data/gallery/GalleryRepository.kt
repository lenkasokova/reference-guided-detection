/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.model.data.gallery

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import cz.vut.oneshotdetector.model.inference.wrappers.EmbeddingWrapper
import cz.vut.oneshotdetector.model.inference.wrappers.createEmbeddingWrapper
import cz.vut.oneshotdetector.view.state.decodeImageWithExif
import cz.vut.oneshotdetector.viewmodel.model.ModelSelectionStore
import cz.vut.oneshotdetector.viewmodel.model.ModelType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import androidx.core.net.toUri

class GalleryRepository(private val context: Context) {
    private val galleryDao = AppDatabase.getInstance(context).galleryImageDao()
    private val store = ModelSelectionStore.getShared(context)
    private val imagesState = MutableStateFlow<List<GalleryImage>>(emptyList())
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val galleryImages: StateFlow<List<GalleryImage>> = imagesState.asStateFlow()

    init {
        repositoryScope.launch {
            galleryDao.clearOversizedEmbeddings(MAX_STORED_EMBEDDING_BYTES)
            galleryDao.observeAll().collect { imagesState.value = it }
        }
        repositoryScope.launch {
            seedPlantsFromAssets()
        }
    }

    private suspend fun seedPlantsFromAssets() = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("gallery_seed", Context.MODE_PRIVATE)
        val seeded = prefs.getStringSet("seeded_plants", emptySet())!!.toMutableSet()

        val dir = "data/plants"
        val json = runCatching {
            context.assets.open("$dir/labels.json").bufferedReader().readText()
        }.getOrNull() ?: return@withContext
        val array = runCatching { org.json.JSONArray(json) }.getOrNull() ?: return@withContext

        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString("name").takeIf { it.isNotBlank() } ?: continue
            if (name in seeded) continue
            val label = obj.optString("label").ifBlank { name }
            val description = obj.optString("description", "")
            val bitmap = runCatching { decodeImageWithExif(context, "$dir/$name") }.getOrNull() ?: continue
            val savedUri = saveBitmapToMediaStore(bitmap) ?: run { bitmap.recycle(); continue }
            val embedding = generateEmbeddingBytes(bitmap)
            bitmap.recycle()
            galleryDao.insert(GalleryImage(uri = savedUri.toString(), label = label, description = description, embedding = embedding))
            seeded.add(name)
            prefs.edit().putStringSet("seeded_plants", seeded).apply()
        }
    }

    suspend fun saveImageFromUri(
        sourceUri: Uri,
        label: String,
        description: String
    ): Result<GalleryImage> = withContext(Dispatchers.IO) {
        val savedUri = copyImageToMediaStore(sourceUri) ?: return@withContext Result.failure(
            IllegalStateException("Failed to save image into MediaStore.")
        )
        val savedBitmap = decodeImageWithExif(context, savedUri)
        val embedding = savedBitmap?.let { generateEmbeddingBytes(it) } ?: ByteArray(0)
        val entry = GalleryImage(
            uri = savedUri.toString(),
            label = label.ifBlank { "Saved image" },
            description = description,
            embedding = embedding
        )
        runCatching {
            galleryDao.insert(entry)
            entry
        }.onFailure {
            deleteFromMediaStore(savedUri)
        }
    }

    suspend fun updateImageMetadata(uri: String, label: String, description: String): Boolean =
        withContext(Dispatchers.IO) {
            galleryDao.updateMetadata(
                uri = uri,
                label = label.ifBlank { "Saved image" },
                description = description
            ) > 0
        }

    suspend fun getStoredEmbedding(uri: String): ByteArray? = withContext(Dispatchers.IO) {
        galleryDao.getEmbeddingByUri(uri, MAX_STORED_EMBEDDING_BYTES)
    }

    suspend fun cropImage(
        uri: String,
        normLeft: Float,
        normTop: Float,
        normRight: Float,
        normBottom: Float,
        label: String,
        description: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val parsedUri = uri.toUri()
        val original = decodeImageWithExif(context, parsedUri)
            ?: return@withContext Result.failure(IllegalStateException("Failed to decode image."))
        val left = (normLeft * original.width).roundToInt().coerceIn(0, original.width)
        val top = (normTop * original.height).roundToInt().coerceIn(0, original.height)
        val right = (normRight * original.width).roundToInt().coerceIn(0, original.width)
        val bottom = (normBottom * original.height).roundToInt().coerceIn(0, original.height)
        val cropW = (right - left).coerceAtLeast(1)
        val cropH = (bottom - top).coerceAtLeast(1)
        val cropped = Bitmap.createBitmap(original, left, top, cropW, cropH)
        original.recycle()
        val newUri = saveBitmapToMediaStore(cropped) ?: run {
            cropped.recycle()
            return@withContext Result.failure(IllegalStateException("Failed to save cropped image."))
        }
        val embedding = generateEmbeddingBytes(cropped)
        cropped.recycle()
        runCatching {
            galleryDao.deleteByUri(uri)
            galleryDao.insert(
                GalleryImage(
                    uri = newUri.toString(),
                    label = label.ifBlank { "Saved image" },
                    description = description,
                    embedding = embedding
                )
            )
            deleteFromMediaStore(parsedUri)
            newUri.toString()
        }
    }

    private fun saveBitmapToMediaStore(bitmap: Bitmap): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "oneshot_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/OneShotDetector")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out) }
            true
        }.getOrDefault(false)
        if (!ok) { deleteFromMediaStore(uri); return null }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        return uri
    }

    /* Re-generates the embedding for every gallery image using the currently embedding model */
    suspend fun recomputeAllEmbeddings(
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Int = withContext(Dispatchers.IO) {
        val images = imagesState.value
        val total = images.size
        var updated = 0
        images.forEachIndexed { index, image ->
            val bitmap = runCatching {
                decodeImageWithExif(context, image.uri.toUri())
            }.getOrNull()
            if (bitmap != null) {
                val embedding = generateEmbeddingBytes(bitmap)
                if (embedding.isNotEmpty()) {
                    galleryDao.updateEmbedding(image.uri, embedding)
                    updated++
                }
            }
            onProgress(index + 1, total)
        }
        updated
    }

    suspend fun deleteImage(uri: String): Boolean = withContext(Dispatchers.IO) {
        val parsedUri = uri.toUri()
        deleteFromMediaStore(parsedUri)
        galleryDao.deleteByUri(uri) > 0
    }

    private fun copyImageToMediaStore(sourceUri: Uri): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "oneshot_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/OneShotDetector"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val savedUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null

        val copyOk = runCatching {
            val input = resolver.openInputStream(sourceUri) ?: return@runCatching false
            val output = resolver.openOutputStream(savedUri) ?: return@runCatching false
            input.use { inStream ->
                output.use { outStream ->
                    inStream.copyTo(outStream)
                }
            }
            true
        }.getOrDefault(false)

        if (!copyOk) {
            deleteFromMediaStore(savedUri)
            return null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val doneValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(savedUri, doneValues, null, null)
        }
        return savedUri
    }

    private fun deleteFromMediaStore(uri: Uri) {
        runCatching {
            context.contentResolver.delete(uri, null, null)
        }
    }

    private var embeddingWrapper: EmbeddingWrapper? = null
    private var loadedWrapperKey: String? = null

    @Synchronized
    private fun getOrCreateEmbeddingWrapper(): EmbeddingWrapper? {
        val variant = store.getActiveVariant(ModelType.Embedding)
        val device = store.getActiveDevice(ModelType.Embedding)
        val key = "${variant.tag}:${variant.assetPath}:${device.name}"
        if (embeddingWrapper != null && loadedWrapperKey == key) return embeddingWrapper
        embeddingWrapper?.close()
        embeddingWrapper = runCatching {
            createEmbeddingWrapper(context, variant.assetPath, variant.tag, device)
        }.getOrNull()
        loadedWrapperKey = key
        return embeddingWrapper
    }

    @Synchronized
    private fun generateEmbeddingBytes(bitmap: Bitmap): ByteArray {
        val wrapper = getOrCreateEmbeddingWrapper() ?: return ByteArray(0)
        val embedding = runCatching { wrapper.embed(bitmap) }.getOrNull() ?: return ByteArray(0)
        val bytes = floatArrayToBytes(embedding)
        return if (bytes.size <= MAX_STORED_EMBEDDING_BYTES) bytes else ByteArray(0)
    }

    private fun floatArrayToBytes(values: FloatArray): ByteArray {
        val byteBuffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { byteBuffer.putFloat(it) }
        return byteBuffer.array()
    }

    companion object {
        private const val MAX_STORED_EMBEDDING_BYTES = 256 * 1024

        @Volatile
        private var sharedInstance: GalleryRepository? = null

        fun getShared(context: Context): GalleryRepository {
            val existing = sharedInstance
            if (existing != null) return existing
            return synchronized(this) {
                val current = sharedInstance
                if (current != null) {
                    current
                } else {
                    GalleryRepository(context.applicationContext).also {
                        sharedInstance = it
                    }
                }
            }
        }
    }
}
