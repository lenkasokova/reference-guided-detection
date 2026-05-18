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
import cz.vut.oneshotdetector.model.inference.wrappers.createDetectionWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DetectionRunner(private val context: Context) : ModelBenchmarkRunner {
    override val modelType = ModelType.Detection

    override suspend fun run(
        variant: ModelVariant,
        dataset: BenchmarkDataset,
        config: BenchmarkConfig,
        onProgress: (String) -> Unit
    ): ModelBenchmarkResult = withContext(Dispatchers.Default) {
        onProgress("Loading ${variant.label}…")
        val wrapper = createDetectionWrapper(context, variant.assetPath, config.device)
        val bitmaps = dataset.loadBitmaps()

        onProgress("Benchmarking detection inference…")
        var idx = 0
        val samples = warmUpAndMeasure(
            config.warmUpRuns, config.measurementRuns, onProgress
        ) {
            wrapper.detect(bitmaps[idx++ % bitmaps.size])
        }

        wrapper.close()

        ModelBenchmarkResult(
            modelType    = modelType,
            variant      = variant,
            config       = config,
            primaryStats = BenchmarkStats.from(samples, config.computePercentiles),
            primaryLabel = "Detection"
        )
    }
}
