/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.viewmodel.benchmark.runners

import cz.vut.oneshotdetector.viewmodel.benchmark.BenchmarkConfig
import cz.vut.oneshotdetector.viewmodel.benchmark.BenchmarkDataset
import cz.vut.oneshotdetector.viewmodel.benchmark.ModelBenchmarkResult
import cz.vut.oneshotdetector.viewmodel.model.ModelType
import cz.vut.oneshotdetector.viewmodel.model.ModelVariant

interface ModelBenchmarkRunner {
    val modelType: ModelType

    /**
     * Runs warm-up passes
     */
    suspend fun run(
        variant: ModelVariant,
        dataset: BenchmarkDataset,
        config: BenchmarkConfig,
        onProgress: (String) -> Unit
    ): ModelBenchmarkResult
}

// Timing utilities

/**
 * Executes block and returns elapsed time in milliseconds.
 */
internal fun timedSync(block: () -> Unit): Double {
    val start = System.nanoTime()
    block()
    return (System.nanoTime() - start) / 1_000_000.0
}

/**
 * Runs warm times (un-timed), then collects measurementRuns timed samples.
 * Returns samples in milliseconds.
 */
internal suspend fun warmUpAndMeasure(
    warmUpRuns: Int,
    measurementRuns: Int,
    onProgress: (String) -> Unit,
    block: suspend () -> Unit
): List<Double> {
    repeat(warmUpRuns) { i ->
        onProgress("Warm-up ${i + 1}/$warmUpRuns")
        block()
    }
    return (1..measurementRuns).map { i ->
        onProgress("Run $i/$measurementRuns")
        val start = System.nanoTime()
        block()
        (System.nanoTime() - start) / 1_000_000.0
    }
}
