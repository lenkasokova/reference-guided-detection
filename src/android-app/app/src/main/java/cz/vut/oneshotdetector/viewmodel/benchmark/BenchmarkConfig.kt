/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.viewmodel.benchmark

import cz.vut.oneshotdetector.viewmodel.model.AVAILABLE_VARIANTS
import cz.vut.oneshotdetector.viewmodel.model.InferenceDevice
import cz.vut.oneshotdetector.viewmodel.model.ModelType
import cz.vut.oneshotdetector.viewmodel.model.ModelVariant

/**
 * Central, immutable configuration for a benchmark run.
 *
 * All parameters have sensible defaults so a call-site can override only what matters.
 * No benchmark logic reads hard-coded values — every knob lives here.
 *
 * @param datasetAssetPath       Sub-folder inside assets/ containing input images.
 * @param fallbackImageSize      Side length of the synthetic bitmap used when the dataset is empty.
 * @param warmUpRuns             Passes to run before measurement begins (not recorded).
 * @param measurementRuns        Timed passes per model (must be ≥ 20 for p95/p99).
 * @param device                 Execution provider applied to every session in this run.
 * @param computePercentiles     Compute p95 and p99 (requires measurementRuns ≥ 20).
 * @param separateEmbeddingPhases When true, the embedding runner reports embedding time and
 *                                cosine-similarity time as two separate statistics.
 * @param decoderUseCachedEncoder When true, the decoder runner pre-computes encoder embeddings
 *                                (un-timed) and measures only the decoder pass, mirroring
 *                                the cached-encoder production path.
 * @param maxDatasetImages       Upper limit on how many dataset images are loaded per run.
 * @param variantOverrides       Per-type model variant overrides. If a type is not listed
 *                               here, the code uses the first variant from AVAILABLE_VARIANTS.
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
    /** Returns the active model variant for the given type. */
    fun variantFor(type: ModelType): ModelVariant =
        variantOverrides[type]
            ?: AVAILABLE_VARIANTS[type]?.firstOrNull()
            ?: ModelVariant(id = "", label = type.label, assetPath = "")

    val summaryLabel: String
        get() = "${warmUpRuns}w · ${measurementRuns}r · ${device.label}"
}

/** Default configuration used when no custom config is provided. */
val DEFAULT_BENCHMARK_CONFIG = BenchmarkConfig()
