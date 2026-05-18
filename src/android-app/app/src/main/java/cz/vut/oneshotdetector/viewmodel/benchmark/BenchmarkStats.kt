/**
 * @author Bc.Lenka Sokova
 */


package cz.vut.oneshotdetector.viewmodel.benchmark

import kotlin.math.sqrt

/**
 * Descriptive statistics computed from a list of timing samples (in milliseconds).
 */
data class BenchmarkStats(
    val meanMs: Double,
    val medianMs: Double,
    val minMs: Double,
    val maxMs: Double,
    val stdMs: Double,
    val p95Ms: Double?,
    val p99Ms: Double?,
    val sampleCount: Int
) {
    companion object {
        /**
         * Computes benchmark statistics from the given sample list.
         *
         * @param samplesMs        Timing samples in milliseconds (Double for sub-ms precision).
         * @param computePercentiles Whether to compute p95 and p99.
         */
        fun from(samplesMs: List<Double>, computePercentiles: Boolean = true): BenchmarkStats {
            require(samplesMs.isNotEmpty()) { "Cannot compute stats from an empty sample list." }
            val sorted = samplesMs.sorted()
            val mean = samplesMs.average()
            val median = percentile(sorted, 50.0)
            val variance = samplesMs.fold(0.0) { acc, v -> acc + (v - mean) * (v - mean) } / samplesMs.size
            val std = sqrt(variance)
            val canComputePercentiles = computePercentiles && sorted.size >= 20
            return BenchmarkStats(
                meanMs       = mean,
                medianMs     = median,
                minMs        = sorted.first(),
                maxMs        = sorted.last(),
                stdMs        = std,
                p95Ms        = if (canComputePercentiles) percentile(sorted, 95.0) else null,
                p99Ms        = if (canComputePercentiles) percentile(sorted, 99.0) else null,
                sampleCount  = samplesMs.size
            )
        }

        /**
         * Linear interpolation between adjacent sorted values.
         * Index is computed as p/100 × (n − 1).
         */
        private fun percentile(sorted: List<Double>, p: Double): Double {
            val idx = p / 100.0 * (sorted.size - 1)
            val lo = idx.toInt().coerceIn(0, sorted.size - 1)
            val hi = (lo + 1).coerceAtMost(sorted.size - 1)
            val frac = idx - lo
            return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
        }
    }
}

/** Formats a ms value for compact display: 2 decimal places below 10 ms, 1 above. */
fun Double.formatMs(): String =
    if (this < 10.0) "%.2f ms".format(this) else "%.1f ms".format(this)
