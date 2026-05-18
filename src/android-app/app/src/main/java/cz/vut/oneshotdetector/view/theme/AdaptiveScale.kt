/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private const val REFERENCE_WIDTH_DP = 360f

fun adaptiveScale(windowWidthDp: Int, maxScale: Float): Float =
    (windowWidthDp / REFERENCE_WIDTH_DP).coerceIn(1f, maxScale)

fun Float.scaledDp(scale: Float): Dp = (this * scale).dp
