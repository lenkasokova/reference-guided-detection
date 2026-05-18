/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.theme

import androidx.compose.ui.unit.Dp

data class Dimens(
    val heroIconSize: Dp,
    val actionButtonHeight: Dp,
    val horizontalPadding: Dp,
    val topCornerRadius: Dp,
    val topBarHeight: Dp,
    val topBarTopPadding: Dp,
    val buttonCornerRadius: Dp,
    val filterFieldHeight: Dp,
    val borderWidth: Dp,
    val galleryThumbnailAspectRatio: Float,
    val photoPreviewAspectRatio: Float,
    val cardCornerRadius: Dp,
)

fun adaptiveDimens(windowWidthDp: Int, maxScale: Float = 1.4f): Dimens {
    val scale = adaptiveScale(windowWidthDp, maxScale)
    return Dimens(
        heroIconSize = 140f.scaledDp(scale),
        actionButtonHeight = 64f.scaledDp(scale),
        horizontalPadding = 24f.scaledDp(scale),
        topCornerRadius = 32f.scaledDp(scale),
        topBarHeight = 53f.scaledDp(scale),
        topBarTopPadding = 12f.scaledDp(scale),
        buttonCornerRadius = 12f.scaledDp(scale),
        filterFieldHeight = 36f.scaledDp(scale),
        borderWidth = 1f.scaledDp(scale),
        galleryThumbnailAspectRatio = 0.8f,
        photoPreviewAspectRatio = 3f / 4f,
        cardCornerRadius = 8f.scaledDp(scale),
    )
}
