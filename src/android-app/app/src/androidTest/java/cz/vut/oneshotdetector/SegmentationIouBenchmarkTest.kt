/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cz.vut.oneshotdetector.model.inference.wrappers.DecoderOutput
import cz.vut.oneshotdetector.model.inference.wrappers.DecoderWrapper
import cz.vut.oneshotdetector.model.inference.wrappers.EncoderOutput
import cz.vut.oneshotdetector.model.inference.wrappers.EncoderWrapper
import cz.vut.oneshotdetector.model.inference.wrappers.createDecoderWrapper
import cz.vut.oneshotdetector.model.inference.wrappers.createEncoderWrapper
import cz.vut.oneshotdetector.viewmodel.model.InferenceDevice
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Quantifies segmentation quality downgrade on-device by comparing quantized variants
 * against a MobileSAM FP32 baseline.
 */
@RunWith(AndroidJUnit4::class)
class SegmentationIouBenchmarkTest {

    private val device = InferenceDevice.CPU

    private val imageAssetDirectory = "data/general"
    private val tapNormX = 0.50f
    private val tapNormY = 0.50f

    private val modelPairs = listOf(
        ModelPair(
            label = "mobilesam_fp32_baseline",
            encoderAsset = "segmentModel/mobilesam_encoder.onnx",
            decoderAsset = "segmentModel/mobilesam_decoder.onnx",
        ),
        ModelPair(
            label = "mobilesam_int8",
            encoderAsset = "segmentModel/mobilesam_encoder_int8.onnx",
            decoderAsset = "segmentModel/mobilesam_decoder_int8.onnx",
        ),
        ModelPair(
            label = "mobilesam_fp16",
            encoderAsset = "segmentModel/mobilesam_encoder_fp16.onnx",
            decoderAsset = "segmentModel/mobilesam_decoder_fp16.onnx",
        ),
        ModelPair(
            label = "edgesam_fp16",
            encoderAsset = "segmentModel/edgesam_encoder_fp16.onnx",
            decoderAsset = "segmentModel/edgesam_decoder_fp16.onnx",
        ),
        ModelPair(
            label = "edgesam_fp32",
            encoderAsset = "segmentModel/edgesam_encoder.onnx",
            decoderAsset = "segmentModel/edgesam_decoder.onnx",
        ),
        ModelPair(
            label = "edgesam_int8",
            encoderAsset = "segmentModel/edgesam_encoder_int8.onnx",
            decoderAsset = "segmentModel/edgesam_decoder_int8.onnx",
        ),
    )

    @Test
    fun benchmarkSegmentationIouDowngrade() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val outputRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val outDir = File(
            outputRoot,
            "oneshot-seg-iou/${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
        ).apply { mkdirs() }

        log("══════════════════════════════════════════════════")
        log("Segmentation IoU test")
        log("  baseline : ${modelPairs.first().label}")
        log("  variants : ${modelPairs.drop(1).joinToString { it.label }}")
        val samples = listSamples(ctx)

        log("  folder   : $imageAssetDirectory")
        log("  samples  : ${samples.size}")
        log("  tap      : (${formatFloat(tapNormX)}, ${formatFloat(tapNormY)})")
        log("  device   : ${device.label}")
        log("  output   : ${outDir.absolutePath}")
        log("══════════════════════════════════════════════════")

        require(samples.isNotEmpty()) { "No image assets found under $imageAssetDirectory" }

        val cases = mutableListOf<CaseResult>()

        for (sample in samples) {
            val bitmap = loadAssetBitmap(ctx, sample.imageAsset)
            requireNotNull(bitmap) { "Missing image asset: ${sample.imageAsset}" }
            try {
                val runs = runAllPairs(ctx, bitmap, sample)
                val baseline = runs.first()
                runs.drop(1).forEach { variant ->
                    val result = compareRuns(sample, baseline, variant)
                    cases += result
                    log(formatCase(result))
                }
            } finally {
                bitmap.recycle()
            }
        }

        val summary = buildSummary(cases)
        File(outDir, "summary.txt").writeText(summary)
        File(outDir, "cases.tsv").writeText(buildCasesTsv(cases))

        log(summary)
        log("Saved ${File(outDir, "summary.txt").absolutePath}")
        log("Saved ${File(outDir, "cases.tsv").absolutePath}")

        assertTrue("Expected some results", cases.isNotEmpty())
    }

    private fun runAllPairs(
        ctx: Context,
        bitmap: Bitmap,
        sample: Sample,
    ): List<RunResult> = modelPairs.map { pair ->
        createEncoderWrapper(ctx, pair.encoderAsset, "onnx", device).use { encoder ->
            createDecoderWrapper(ctx, pair.decoderAsset, "onnx", device).use { decoder ->
                runOnePair(bitmap, sample, pair, encoder, decoder)
            }
        }
    }

    private fun runOnePair(
        bitmap: Bitmap,
        sample: Sample,
        pair: ModelPair,
        encoder: EncoderWrapper,
        decoder: DecoderWrapper,
    ): RunResult {
        val encoderOutput = encoder.encode(bitmap)
        val decoderOutput = decoder.decode(encoderOutput, sample.tapNormX, sample.tapNormY)
        val tapX = (sample.tapNormX * encoderOutput.scaledW).roundToInt().coerceIn(0, encoderOutput.scaledW - 1)
        val tapY = (sample.tapNormY * encoderOutput.scaledH).roundToInt().coerceIn(0, encoderOutput.scaledH - 1)
        val bestIdx = selectMaskIndex(decoderOutput, tapX, tapY, encoderOutput.scaledW, encoderOutput.scaledH)
        val bestMask = decoderOutput.masks.copyOfRange(
            bestIdx * decoderOutput.planeSize,
            (bestIdx + 1) * decoderOutput.planeSize
        )
        val bestBox = computeMaskBox(bestMask, encoderOutput.scaledW, encoderOutput.scaledH)

        return RunResult(
            label = pair.label,
            encoder = encoderOutput,
            decoder = decoderOutput,
            bestIdx = bestIdx,
            bestMask = bestMask,
            bestBox = bestBox,
        )
    }

    private fun compareRuns(sample: Sample, baseline: RunResult, variant: RunResult): CaseResult {
        require(baseline.encoder.scaledW == variant.encoder.scaledW) {
            "Scaled width mismatch for ${sample.imageAsset}: ${baseline.encoder.scaledW} vs ${variant.encoder.scaledW}"
        }
        require(baseline.encoder.scaledH == variant.encoder.scaledH) {
            "Scaled height mismatch for ${sample.imageAsset}: ${baseline.encoder.scaledH} vs ${variant.encoder.scaledH}"
        }

        val validW = baseline.encoder.scaledW
        val validH = baseline.encoder.scaledH
        val baselineMask = baseline.bestMask
        val variantMask = variant.bestMask

        return CaseResult(
            imageAsset = sample.imageAsset,
            tapNormX = sample.tapNormX,
            tapNormY = sample.tapNormY,
            baselineLabel = baseline.label,
            variantLabel = variant.label,
            baselineBestIdx = baseline.bestIdx,
            variantBestIdx = variant.bestIdx,
            baselineBestScore = baseline.decoder.iouScores[baseline.bestIdx],
            variantBestScore = variant.decoder.iouScores[variant.bestIdx],
            bestMaskBinaryIou = binaryIou(baselineMask, variantMask, validW, validH),
            bestMaskMeanAbsDiff = meanAbsDiff(baselineMask, variantMask),
            bestMaskMaxAbsDiff = maxAbsDiff(baselineMask, variantMask),
            boxIou = boxIou(baseline.bestBox, variant.bestBox),
            baselineArea = countMaskArea(baselineMask, validW, validH),
            variantArea = countMaskArea(variantMask, validW, validH),
        )
    }

    private fun buildSummary(cases: List<CaseResult>): String {
        val grouped = cases.groupBy { it.variantLabel }
        val sb = StringBuilder()
        sb.appendLine("Segmentation IoU test")
        sb.appendLine("date=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        sb.appendLine("device=$device")
        sb.appendLine("baseline=${modelPairs.first().label}")
        sb.appendLine("directory=$imageAssetDirectory")
        sb.appendLine("samples=${cases.map { it.imageAsset }.distinct().size}")
        sb.appendLine("tap=(${formatFloat(tapNormX)},${formatFloat(tapNormY)})")
        sb.appendLine()

        grouped.toSortedMap().forEach { (variant, rows) ->
            val bestMaskIous = rows.map { it.bestMaskBinaryIou }
            val boxIous = rows.map { it.boxIou }
            val scoreDrops = rows.map { (it.baselineBestScore - it.variantBestScore).toDouble() }
            val areaRatios = rows.map {
                if (it.baselineArea == 0) Double.NaN else it.variantArea.toDouble() / it.baselineArea.toDouble()
            }.filter { !it.isNaN() && !it.isInfinite() }

            sb.appendLine("[$variant]")
            sb.appendLine("cases=${rows.size}")
            sb.appendLine("best_mask_iou_avg=${fmt(bestMaskIous.average())}")
            sb.appendLine("best_mask_iou_min=${fmt(bestMaskIous.minOrNull() ?: Double.NaN)}")
            sb.appendLine("best_mask_iou_p50=${fmt(percentile(bestMaskIous.sorted(), 50.0))}")
            sb.appendLine("box_iou_avg=${fmt(boxIous.average())}")
            sb.appendLine("score_drop_avg=${fmt(scoreDrops.average())}")
            sb.appendLine("area_ratio_avg=${fmt(areaRatios.average())}")
            sb.appendLine()
        }

        sb.appendLine("Case results")
        cases.forEach { sb.appendLine(formatCase(it)) }
        return sb.toString()
    }

    private fun buildCasesTsv(cases: List<CaseResult>): String {
        val header = listOf(
            "image_asset",
            "tap_x",
            "tap_y",
            "baseline",
            "variant",
            "baseline_best_idx",
            "variant_best_idx",
            "baseline_best_score",
            "variant_best_score",
            "best_mask_binary_iou",
            "best_mask_mean_abs_diff",
            "best_mask_max_abs_diff",
            "box_iou",
            "baseline_area",
            "variant_area",
        ).joinToString("\t")

        val rows = cases.map {
            listOf(
                it.imageAsset,
                "%.4f".format(it.tapNormX),
                "%.4f".format(it.tapNormY),
                it.baselineLabel,
                it.variantLabel,
                it.baselineBestIdx.toString(),
                it.variantBestIdx.toString(),
                "%.6f".format(it.baselineBestScore),
                "%.6f".format(it.variantBestScore),
                "%.6f".format(it.bestMaskBinaryIou),
                "%.6f".format(it.bestMaskMeanAbsDiff),
                "%.6f".format(it.bestMaskMaxAbsDiff),
                "%.6f".format(it.boxIou),
                it.baselineArea.toString(),
                it.variantArea.toString(),
            ).joinToString("\t")
        }

        return buildString {
            appendLine(header)
            rows.forEach { appendLine(it) }
        }
    }

    private fun formatCase(result: CaseResult): String =
        "${result.variantLabel}: image=${result.imageAsset.substringAfterLast('/')} " +
            "tap=(${formatFloat(result.tapNormX)},${formatFloat(result.tapNormY)}) " +
            "maskIoU=${fmt(result.bestMaskBinaryIou)} " +
            "boxIoU=${fmt(result.boxIou)} " +
            "scoreDrop=${fmt((result.baselineBestScore - result.variantBestScore).toDouble())} " +
            "area=${result.baselineArea}->${result.variantArea} " +
            "bestIdx=${result.baselineBestIdx}->${result.variantBestIdx}"

    private fun selectMaskIndex(
        dec: DecoderOutput,
        tapX: Int,
        tapY: Int,
        validW: Int,
        validH: Int,
    ): Int {
        val tapFlatIndex = tapY * 1024 + tapX
        var bestContainingIdx = -1
        var bestContainingArea = -1
        var bestContainingScore = Float.NEGATIVE_INFINITY

        for (maskIdx in 0 until dec.numMasks) {
            val offset = maskIdx * dec.planeSize
            if (dec.masks[offset + tapFlatIndex] <= 0f) continue

            var area = 0
            for (y in 0 until validH) {
                val rowBase = offset + y * 1024
                for (x in 0 until validW) {
                    if (dec.masks[rowBase + x] > 0f) area++
                }
            }

            val score = dec.iouScores[maskIdx]
            if (area > bestContainingArea || (area == bestContainingArea && score > bestContainingScore)) {
                bestContainingIdx = maskIdx
                bestContainingArea = area
                bestContainingScore = score
            }
        }

        return if (bestContainingIdx >= 0) bestContainingIdx else dec.bestMaskIndex()
    }

    private fun computeMaskBox(mask: FloatArray, validW: Int, validH: Int): IntArray? {
        var yMin = validH
        var yMax = -1
        var xMin = validW
        var xMax = -1

        for (y in 0 until validH) {
            val rowBase = y * 1024
            for (x in 0 until validW) {
                if (mask[rowBase + x] <= 0f) continue
                if (y < yMin) yMin = y
                if (y > yMax) yMax = y
                if (x < xMin) xMin = x
                if (x > xMax) xMax = x
            }
        }

        return if (yMax < 0) null else intArrayOf(xMin, yMin, xMax, yMax)
    }

    private fun countMaskArea(mask: FloatArray, validW: Int, validH: Int): Int {
        var area = 0
        for (y in 0 until validH) {
            val rowBase = y * 1024
            for (x in 0 until validW) {
                if (mask[rowBase + x] > 0f) area++
            }
        }
        return area
    }

    private fun binaryIou(a: FloatArray, b: FloatArray, validW: Int, validH: Int): Double {
        var inter = 0
        var union = 0
        for (y in 0 until validH) {
            val rowBase = y * 1024
            for (x in 0 until validW) {
                val av = a[rowBase + x] > 0f
                val bv = b[rowBase + x] > 0f
                if (av && bv) inter++
                if (av || bv) union++
            }
        }
        return if (union == 0) Double.NaN else inter.toDouble() / union.toDouble()
    }

    private fun boxIou(a: IntArray?, b: IntArray?): Double {
        if (a == null || b == null) return Double.NaN
        val interLeft = maxOf(a[0], b[0])
        val interTop = maxOf(a[1], b[1])
        val interRight = minOf(a[2], b[2])
        val interBottom = minOf(a[3], b[3])
        val interW = (interRight - interLeft + 1).coerceAtLeast(0)
        val interH = (interBottom - interTop + 1).coerceAtLeast(0)
        val inter = interW * interH
        val areaA = (a[2] - a[0] + 1).coerceAtLeast(0) * (a[3] - a[1] + 1).coerceAtLeast(0)
        val areaB = (b[2] - b[0] + 1).coerceAtLeast(0) * (b[3] - b[1] + 1).coerceAtLeast(0)
        val union = areaA + areaB - inter
        return if (union == 0) Double.NaN else inter.toDouble() / union.toDouble()
    }

    private fun meanAbsDiff(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "Mismatched sizes: ${a.size} vs ${b.size}" }
        var sum = 0.0
        for (i in a.indices) sum += abs(a[i] - b[i]).toDouble()
        return sum / a.size.toDouble()
    }

    private fun maxAbsDiff(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "Mismatched sizes: ${a.size} vs ${b.size}" }
        var max = 0.0
        for (i in a.indices) {
            val diff = abs(a[i] - b[i]).toDouble()
            if (diff > max) max = diff
        }
        return max
    }

    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val idx = p / 100.0 * (sorted.size - 1)
        val lo = idx.toInt().coerceIn(0, sorted.size - 1)
        val hi = (lo + 1).coerceAtMost(sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (idx - lo)
    }

    private fun loadAssetBitmap(ctx: Context, assetPath: String): Bitmap? =
        runCatching { ctx.assets.open(assetPath).use { BitmapFactory.decodeStream(it) } }.getOrNull()

    private fun listSamples(ctx: Context): List<Sample> =
        ctx.assets.list(imageAssetDirectory)
            ?.map { "$imageAssetDirectory/$it" }
            ?.filter { path ->
                val lower = path.lowercase(Locale.US)
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
            }
            ?.sorted()
            ?.map { Sample(it, tapNormX, tapNormY) }
            .orEmpty()

    private fun fmt(value: Double): String = if (value.isNaN()) "NaN" else "%.6f".format(value)

    private fun formatFloat(value: Float): String = "%.3f".format(value)

    private fun log(msg: String) {
        Log.i("SegmentationIoU", msg)
    }

    private data class Sample(
        val imageAsset: String,
        val tapNormX: Float,
        val tapNormY: Float,
    )

    private data class ModelPair(
        val label: String,
        val encoderAsset: String,
        val decoderAsset: String,
    )

    private data class RunResult(
        val label: String,
        val encoder: EncoderOutput,
        val decoder: DecoderOutput,
        val bestIdx: Int,
        val bestMask: FloatArray,
        val bestBox: IntArray?,
    )

    private data class CaseResult(
        val imageAsset: String,
        val tapNormX: Float,
        val tapNormY: Float,
        val baselineLabel: String,
        val variantLabel: String,
        val baselineBestIdx: Int,
        val variantBestIdx: Int,
        val baselineBestScore: Float,
        val variantBestScore: Float,
        val bestMaskBinaryIou: Double,
        val bestMaskMeanAbsDiff: Double,
        val bestMaskMaxAbsDiff: Double,
        val boxIou: Double,
        val baselineArea: Int,
        val variantArea: Int,
    )
}
