/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.model.inference

data class Detection(
    val label: String,
    val score: Float,
    val box: FloatArray,
    val alphaScore: Float,
    val similarityCosine: Float? = null
)
