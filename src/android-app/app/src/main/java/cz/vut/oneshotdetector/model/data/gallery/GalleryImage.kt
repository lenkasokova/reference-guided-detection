/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.model.data.gallery

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "gallery_images")
data class GalleryImage(
    @PrimaryKey
    val uri: String,
    val label: String,
    val description: String,
    val embedding: ByteArray
)
