/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.viewmodel.benchmark

import cz.vut.oneshotdetector.viewmodel.model.AVAILABLE_VARIANTS
import cz.vut.oneshotdetector.viewmodel.model.InferenceDevice
import cz.vut.oneshotdetector.viewmodel.model.ModelType
import cz.vut.oneshotdetector.viewmodel.model.ModelVariant

/**
 * Simple settings for one benchmark run.
 *
 * Most values already have defaults, so you only change what you need.
 *
 * @param datasetAssetPath folder in `assets/` with the input images
 * @param fallbackImageSize size of the fake image used when the dataset is empty
 * @param warmUpRuns runs before measuring
 * @param measurementRuns measured runs for each model
 * @param device device used for inference
 * @param computePercentiles if true, also calculate p95 and p99
 * @param separateEmbeddingPhases if true, embedding and similarity are measured separately
 * @param decoderUseCachedEncoder if true, only the decoder step is timed
 * @param maxDatasetImages max number of dataset images loaded in one run
 * @param variantOverrides custom model variant per model type
 */
data class BenchmarkConfig(
    val datasetAssetPath: String = "data",
    val fallbackImageSize: Int = 320,
    val warmUpRuns: Int = 3,
    val measurementRuns: Int = 20,
    val device: InferenceDevice = InferenceDevice.CPU,
    val computePercentiles: Boolean = true,
    val separateEmbeddingPhases: Boolean = true,
    val decoderUseCachedEncoder: Boolean = true,
    val maxDatasetImages: Int = 10,
    val variantOverrides: Map<ModelType, ModelVariant> = emptyMap()
) {
    /** Gets the selected model variant for this type. */
    fun variantFor(type: ModelType): ModelVariant =
        variantOverrides[type]
            ?: AVAILABLE_VARIANTS[type]?.firstOrNull()
            ?: ModelVariant(id = "", label = type.label, assetPath = "")

    val summaryLabel: String
        get() = "${warmUpRuns}w · ${measurementRuns}r · ${device.label}"
}

/** Default benchmark settings. */
val DEFAULT_BENCHMARK_CONFIG = BenchmarkConfig()
