/**
 * @author Bc.Lenka Sokova
 */


package cz.vut.oneshotdetector.viewmodel.benchmark

import kotlin.math.sqrt

/**
 * Basic timing stats from benchmark samples in milliseconds.
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
         * Builds stats from the measured times.
         *
         * @param samplesMs measured times in milliseconds
         * @param computePercentiles if true, also calculate p95 and p99
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
         * Gets a percentile from sorted values using simple interpolation.
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

/** Formats milliseconds in a short readable way. */
fun Double.formatMs(): String =
    if (this < 10.0) "%.2f ms".format(this) else "%.1f ms".format(this)
