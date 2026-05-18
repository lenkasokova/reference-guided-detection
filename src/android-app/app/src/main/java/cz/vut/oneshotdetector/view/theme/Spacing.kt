/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.theme

import androidx.compose.ui.unit.Dp

data class Spacing(
    val xxs: Dp,
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
    val xxl: Dp,
    val screenHorizontal: Dp,
    val screenVertical: Dp,
)

fun adaptiveSpacing(windowWidthDp: Int, maxScale: Float = 1.5f): Spacing {
    val scale = adaptiveScale(windowWidthDp, maxScale)
    return Spacing(
        xxs = 2f.scaledDp(scale),
        xs = 4f.scaledDp(scale),
        sm = 8f.scaledDp(scale),
        md = 16f.scaledDp(scale),
        lg = 24f.scaledDp(scale),
        xl = 32f.scaledDp(scale),
        xxl = 48f.scaledDp(scale),
        screenHorizontal = 16f.scaledDp(scale),
        screenVertical = 12f.scaledDp(scale),
    )
}
