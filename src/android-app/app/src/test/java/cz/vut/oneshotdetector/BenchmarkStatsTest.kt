/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector

import cz.vut.oneshotdetector.viewmodel.benchmark.BenchmarkStats
import cz.vut.oneshotdetector.viewmodel.benchmark.formatMs
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class BenchmarkStatsTest {

    private fun assertClose(expected: Double, actual: Double, delta: Double = 1e-9) {
        assertTrue(
            "Expected $expected ± $delta but was $actual",
            abs(expected - actual) <= delta
        )
    }

    // ── from() basic statistics ───────────────────────────────────────────────

    @Test
    fun `from single sample returns equal mean median min max with zero std`() {
        val stats = BenchmarkStats.from(listOf(5.0))
        assertClose(5.0, stats.meanMs)
        assertClose(5.0, stats.medianMs)
        assertClose(5.0, stats.minMs)
        assertClose(5.0, stats.maxMs)
        assertClose(0.0, stats.stdMs)
        assertEquals(1, stats.sampleCount)
    }

    @Test
    fun `from two samples computes mean and median correctly`() {
        val stats = BenchmarkStats.from(listOf(2.0, 8.0))
        assertClose(5.0, stats.meanMs)
        assertClose(5.0, stats.medianMs)
        assertClose(2.0, stats.minMs)
        assertClose(8.0, stats.maxMs)
    }

    @Test
    fun `from unsorted samples still produces correct min and max`() {
        val stats = BenchmarkStats.from(listOf(10.0, 1.0, 7.0, 3.0))
        assertClose(1.0, stats.minMs)
        assertClose(10.0, stats.maxMs)
    }

    @Test
    fun `from uniform samples produces zero std`() {
        val samples = List(5) { 4.0 }
        val stats = BenchmarkStats.from(samples)
        assertClose(0.0, stats.stdMs)
    }

    @Test
    fun `from known samples produces correct std`() {
        // Sample values are 2, 4, 4, 4, 5, 5, 7, and 9, so the mean is 5
        // and the population standard deviation is 2.
        val samples = listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0)
        val stats = BenchmarkStats.from(samples)
        assertClose(5.0, stats.meanMs)
        assertClose(2.0, stats.stdMs)
    }

    @Test
    fun `sampleCount matches input size`() {
        val stats = BenchmarkStats.from(List(7) { it.toDouble() })
        assertEquals(7, stats.sampleCount)
    }

    // precentile threshold

    @Test
    fun `p95 and p99 are null when fewer than 20 samples`() {
        val stats = BenchmarkStats.from(List(19) { it.toDouble() })
        assertNull(stats.p95Ms)
        assertNull(stats.p99Ms)
    }

    @Test
    fun `p95 and p99 are computed when 20 or more samples`() {
        val stats = BenchmarkStats.from(List(20) { it.toDouble() })
        assertNotNull(stats.p95Ms)
        assertNotNull(stats.p99Ms)
    }

    @Test
    fun `p95 and p99 are null when computePercentiles is false`() {
        val stats = BenchmarkStats.from(List(30) { it.toDouble() }, computePercentiles = false)
        assertNull(stats.p95Ms)
        assertNull(stats.p99Ms)
    }

    @Test
    fun `p99 is greater than or equal to p95`() {
        val stats = BenchmarkStats.from(List(20) { it.toDouble() + 1.0 })
        assertTrue((stats.p99Ms ?: 0.0) >= (stats.p95Ms ?: 0.0))
    }

    @Test
    fun `median equals interpolated middle of sorted list`() {
        // With 5 sorted values 1, 2, 3, 4, and 5, the median index is 2.0,
        // so the median value is 3.0.
        val stats = BenchmarkStats.from(listOf(3.0, 1.0, 5.0, 2.0, 4.0))
        assertClose(3.0, stats.medianMs)
    }

    @Test
    fun `median of even-length list interpolates two middle values`() {
        // With 4 sorted values 1, 2, 3, and 4, the index is 1.5,
        // so interpolation gives 2.5.
        val stats = BenchmarkStats.from(listOf(1.0, 2.0, 3.0, 4.0))
        assertClose(2.5, stats.medianMs)
    }

    // empty input guard

    @Test(expected = IllegalArgumentException::class)
    fun `from empty list throws IllegalArgumentException`() {
        BenchmarkStats.from(emptyList())
    }

    // formatMs formatting

    @Test
    fun `formatMs below 10 uses two decimal places`() {
        assertEquals("9.99 ms", 9.99.formatMs())
        assertEquals("0.12 ms", 0.123.formatMs())
    }

    @Test
    fun `formatMs at or above 10 uses one decimal place`() {
        assertEquals("10.0 ms", 10.0.formatMs())
        assertEquals("123.5 ms", 123.456.formatMs())
    }
}
