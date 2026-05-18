/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.navigation

import android.net.Uri

object StackRoutes {
    const val HOME = "home"
    const val CAMERA = "camera"
    const val GALLERY = "gallery"
    const val SETTINGS = "settings"
    const val DETECT = "detect"
    const val DETAIL = "detail/{plantId}"
    const val EDIT = "edit/{plantId}"
    const val CROP = "crop/{plantId}"

    const val PHOTO_ACTIONS = "photo_actions?imageUri={imageUri}"

    fun detail(plantId: String) = "detail/${Uri.encode(plantId)}"
    fun edit(plantId: String) = "edit/${Uri.encode(plantId)}"
    fun crop(plantId: String) = "crop/${Uri.encode(plantId)}"
    fun photoActions(imageUri: String) = "photo_actions?imageUri=${Uri.encode(imageUri)}"
}
