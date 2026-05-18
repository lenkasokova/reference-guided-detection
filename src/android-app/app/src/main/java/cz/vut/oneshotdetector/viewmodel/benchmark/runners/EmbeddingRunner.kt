/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.viewmodel.benchmark.runners

import android.content.Context
import cz.vut.oneshotdetector.viewmodel.benchmark.BenchmarkConfig
import cz.vut.oneshotdetector.viewmodel.benchmark.BenchmarkDataset
import cz.vut.oneshotdetector.viewmodel.benchmark.BenchmarkStats
import cz.vut.oneshotdetector.viewmodel.benchmark.ModelBenchmarkResult
import cz.vut.oneshotdetector.viewmodel.model.ModelType
import cz.vut.oneshotdetector.viewmodel.model.ModelVariant
import cz.vut.oneshotdetector.model.inference.wrappers.createEmbeddingWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Benchmarks the embedding model.
 */
class EmbeddingRunner(private val context: Context) : ModelBenchmarkRunner {
    override val modelType = ModelType.Embedding

    override suspend fun run(
        variant: ModelVariant,
        dataset: BenchmarkDataset,
        config: BenchmarkConfig,
        onProgress: (String) -> Unit
    ): ModelBenchmarkResult = withContext(Dispatchers.Default) {
        onProgress("Loading ${variant.label}…")
        val wrapper = createEmbeddingWrapper(context, variant.assetPath, variant.tag, config.device)
        val bitmaps = dataset.loadBitmaps()

        // embedding inference times
        onProgress("Benchmarking embedding inference…")
        var primaryIdx = 0
        val embeddingSamples = warmUpAndMeasure(
            config.warmUpRuns, config.measurementRuns, onProgress
        ) {
            wrapper.embed(bitmaps[primaryIdx++ % bitmaps.size])
        }
        val primaryStats = BenchmarkStats.from(embeddingSamples, config.computePercentiles)
        wrapper.close()

        ModelBenchmarkResult(
            modelType      = modelType,
            variant        = variant,
            config         = config,
            primaryStats   = primaryStats,
            primaryLabel   = "Embedding",
            secondaryStats = null,
            secondaryLabel = null
        )
    }
}
