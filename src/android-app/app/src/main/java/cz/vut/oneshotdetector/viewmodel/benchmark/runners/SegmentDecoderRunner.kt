/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.viewmodel.benchmark.runners

import android.content.Context
import cz.vut.oneshotdetector.viewmodel.benchmark.BenchmarkConfig
import cz.vut.oneshotdetector.viewmodel.benchmark.BenchmarkDataset
import cz.vut.oneshotdetector.viewmodel.benchmark.BenchmarkStats
import cz.vut.oneshotdetector.viewmodel.benchmark.ModelBenchmarkResult
import cz.vut.oneshotdetector.viewmodel.model.AVAILABLE_VARIANTS
import cz.vut.oneshotdetector.viewmodel.model.ModelType
import cz.vut.oneshotdetector.viewmodel.model.ModelVariant
import cz.vut.oneshotdetector.viewmodel.model.matchingSegmentationVariant
import cz.vut.oneshotdetector.model.inference.wrappers.EncoderOutput
import cz.vut.oneshotdetector.model.inference.wrappers.OnnxEncoderWrapper
import cz.vut.oneshotdetector.model.inference.wrappers.createDecoderWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Benchmarks the SAM decoder.
 */
class SegmentDecoderRunner(private val context: Context) : ModelBenchmarkRunner {
    override val modelType = ModelType.SegmentDecoder

    override suspend fun run(
        variant: ModelVariant,
        dataset: BenchmarkDataset,
        config: BenchmarkConfig,
        onProgress: (String) -> Unit
    ): ModelBenchmarkResult = withContext(Dispatchers.Default) {
        onProgress("Loading ${variant.label}…")
        val decoderWrapper =
            createDecoderWrapper(context, variant.assetPath, variant.tag, config.device)
        val bitmaps = dataset.loadBitmaps()

        // ── Pre-compute encoder embeddings (un-timed) ─────────────────────────
        val encoderOutputs: List<EncoderOutput> = if (config.decoderUseCachedEncoder) {
            onProgress("Pre-computing encoder embeddings (un-timed)…")
            val encoderVariant = config.variantOverrides[ModelType.SegmentEncoder]
                ?: variant.matchingSegmentationVariant(ModelType.SegmentEncoder)
                ?: AVAILABLE_VARIANTS[ModelType.SegmentEncoder]?.firstOrNull()
            if (encoderVariant != null) {
                val encoderWrapper = OnnxEncoderWrapper(context, encoderVariant.assetPath, config.device)
                val outputs = bitmaps.map { encoderWrapper.encode(it) }
                encoderWrapper.close()
                outputs
            } else emptyList()
        } else emptyList()

        // Fallback: zero embedding if encoder is unavailable
        val fallback = EncoderOutput(
            embedding = FloatArray(1 * 256 * 64 * 64),
            scale = 1f, scaledW = 1024, scaledH = 1024, origW = 1024, origH = 1024
        )

        onProgress("Benchmarking decoder inference…")
        var idx = 0
        val samples = warmUpAndMeasure(
            config.warmUpRuns, config.measurementRuns, onProgress
        ) {
            val enc = if (encoderOutputs.isNotEmpty()) encoderOutputs[idx % encoderOutputs.size]
                      else fallback
            idx++
            decoderWrapper.decode(enc, normX = 0.5f, normY = 0.5f)
        }

        decoderWrapper.close()

        ModelBenchmarkResult(
            modelType    = modelType,
            variant      = variant,
            config       = config,
            primaryStats = BenchmarkStats.from(samples, config.computePercentiles),
            primaryLabel = if (config.decoderUseCachedEncoder) "Decoder (cached enc.)" else "Decoder"
        )
    }
}
