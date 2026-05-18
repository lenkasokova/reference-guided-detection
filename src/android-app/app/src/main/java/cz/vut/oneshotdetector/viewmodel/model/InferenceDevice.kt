/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.viewmodel.model

import android.os.Build

/**
 * Hardware execution provider used by both production inference and the benchmark pipeline.
 */
enum class InferenceDevice(val label: String) {
    CPU("CPU"),
    NPU("NNAPI"),
    GPU("GPU")
}

/**
 * Device drivers can still
 * reject individual models at runtime, so model wrappers keep delegate setup guarded
 * and let the runtime fall back where possible.
 */
fun InferenceDevice.shouldUseNnapiDelegate(): Boolean =
    this == InferenceDevice.NPU &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1

fun InferenceDevice.shouldUseGpuDelegate(): Boolean = this == InferenceDevice.GPU

fun isNnapiDelegateSupportedOnThisDevice(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1

