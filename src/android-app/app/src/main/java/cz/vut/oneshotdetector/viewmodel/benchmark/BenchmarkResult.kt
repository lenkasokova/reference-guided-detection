/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.viewmodel.benchmark

import cz.vut.oneshotdetector.viewmodel.model.ModelType
import cz.vut.oneshotdetector.viewmodel.model.ModelVariant

/**
 * Complete result of one benchmark run for one model type.
 */
data class ModelBenchmarkResult(
    val modelType: ModelType,
    val variant: ModelVariant,
    val config: BenchmarkConfig,
    val primaryStats: BenchmarkStats,
    val primaryLabel: String,
    val secondaryStats: BenchmarkStats? = null,
    val secondaryLabel: String? = null,
    val timestampEpochMs: Long = System.currentTimeMillis()
)

/** Per-item UI state in the benchmark settings section. */
sealed class BenchmarkItemRunState {
    object Idle : BenchmarkItemRunState()
    data class Running(val progress: String) : BenchmarkItemRunState()
    data class Completed(val result: ModelBenchmarkResult) : BenchmarkItemRunState()
    data class Failed(val message: String) : BenchmarkItemRunState()
}
