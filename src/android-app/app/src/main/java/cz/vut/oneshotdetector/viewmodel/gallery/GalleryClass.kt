/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.viewmodel.gallery

import cz.vut.oneshotdetector.model.data.gallery.GalleryImage

data class GalleryClass(
    val label: String,
    val images: List<GalleryImage>
)
