/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.model.data.gallery

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GalleryImageDao {
    @Query("SELECT uri, label, description, CAST(x'' AS BLOB) AS embedding FROM gallery_images ORDER BY rowid DESC")
    fun observeAll(): Flow<List<GalleryImage>>

    @Query("SELECT uri, label, description, CAST(x'' AS BLOB) AS embedding FROM gallery_images WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): GalleryImage?

    @Query("SELECT embedding FROM gallery_images WHERE uri = :uri AND length(embedding) <= :maxBytes LIMIT 1")
    suspend fun getEmbeddingByUri(uri: String, maxBytes: Int): ByteArray?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(image: GalleryImage)

    @Query("UPDATE gallery_images SET label = :label, description = :description WHERE uri = :uri")
    suspend fun updateMetadata(uri: String, label: String, description: String): Int

    @Query("UPDATE gallery_images SET embedding = :embedding WHERE uri = :uri")
    suspend fun updateEmbedding(uri: String, embedding: ByteArray): Int

    @Query("UPDATE gallery_images SET embedding = x'' WHERE length(embedding) > :maxBytes")
    suspend fun clearOversizedEmbeddings(maxBytes: Int): Int

    @Query("DELETE FROM gallery_images WHERE uri = :uri")
    suspend fun deleteByUri(uri: String): Int
}
