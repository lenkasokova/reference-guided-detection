/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector

import cz.vut.oneshotdetector.viewmodel.benchmark.BenchmarkConfig
import cz.vut.oneshotdetector.viewmodel.model.InferenceDevice
import cz.vut.oneshotdetector.viewmodel.model.ModelType
import cz.vut.oneshotdetector.viewmodel.model.ModelVariant
import org.junit.Assert.*
import org.junit.Test

class BenchmarkConfigTest {

    // SummaryLabel
    @Test
    fun `summaryLabel formats warmup measurement and device`() {
        val config = BenchmarkConfig(warmUpRuns = 5, measurementRuns = 30, device = InferenceDevice.CPU)
        assertEquals("5w · 30r · CPU", config.summaryLabel)
    }

    @Test
    fun `summaryLabel reflects GPU device`() {
        val config = BenchmarkConfig(warmUpRuns = 2, measurementRuns = 20, device = InferenceDevice.GPU)
        assertEquals("2w · 20r · GPU", config.summaryLabel)
    }

    @Test
    fun `summaryLabel reflects NPU device`() {
        val config = BenchmarkConfig(warmUpRuns = 3, measurementRuns = 25, device = InferenceDevice.NPU)
        assertEquals("3w · 25r · NNAPI", config.summaryLabel)
    }

    // Variant selection
    @Test
    fun `variantFor returns override when present`() {
        val custom = ModelVariant(id = "custom", label = "Custom", assetPath = "custom.tflite")
        val config = BenchmarkConfig(
            variantOverrides = mapOf(ModelType.Embedding to custom)
        )
        assertEquals(custom, config.variantFor(ModelType.Embedding))
    }

    @Test
    fun `variantFor returns first available variant when no override`() {
        val config = BenchmarkConfig()
        val variant = config.variantFor(ModelType.Embedding)
        assertEquals("mobilenet_v3a_large_fp32", variant.id)
    }

    @Test
    fun `variantFor returns fallback placeholder when type has no registered variants`() {
        val config = BenchmarkConfig()
        // All four types should return a non-null variant with a non-blank label.
        for (type in ModelType.entries) {
            val v = config.variantFor(type)
            assertNotNull(v)
            assertTrue(v.label.isNotBlank())
        }
    }

    @Test
    fun `variantFor respects override for Detection type`() {
        val override = ModelVariant(id = "det_custom", label = "Det Custom", assetPath = "det.tflite")
        val config = BenchmarkConfig(variantOverrides = mapOf(ModelType.Detection to override))
        assertEquals(override, config.variantFor(ModelType.Detection))
        assertNotEquals(override, config.variantFor(ModelType.Embedding))
    }

    // Default values
    @Test
    fun `default config has 20 measurement runs enabling percentile computation`() {
        val config = BenchmarkConfig()
        assertTrue(config.measurementRuns >= 20)
        assertTrue(config.computePercentiles)
    }
}
