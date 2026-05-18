/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cz.vut.oneshotdetector.model.inference.Detection
import cz.vut.oneshotdetector.model.inference.wrappers.EmbeddingWrapper
import cz.vut.oneshotdetector.model.inference.wrappers.MediaPipeDetectionWrapper
import cz.vut.oneshotdetector.model.inference.wrappers.createEmbeddingWrapper
import cz.vut.oneshotdetector.viewmodel.model.InferenceDevice
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt

/**
 * FPS benchmark test: detection + per-ROI embedding + gallery comparison.
 *
 *  - Processing time is smoothed with exponential moving average (α = SMOOTHING_ALPHA).
 *  - Effective cycle time = max(MIN_CYCLE_MS, smoothedMs × HEADROOM_FACTOR).
 *  - Effective FPS = 1000 / cycleMs  (same formula as the live loop).
 */

@RunWith(AndroidJUnit4::class)
class FpsBenchmarkTest {

    // ── Benchmark parameters (edit these) ────────────────────────────────────

    private val detectionAsset = "detectionModel/efficientdet_lite0.tflite"

    private val embeddingAsset = "embeddingModel/mobilenet_v3_large/embedding_fp32.tflite"
    private val embeddingTag   = "tflite"

    private val device        = InferenceDevice.CPU
    private val warmupFrames  = 5
    private val measureFrames = 30

    private val galleryAssets = listOf(
        "data/general/000000000019.jpg",
        "data/general/000000000180.jpg",
        "data/general/000000000188.jpg",
        "data/general/000000000229.jpg",
        "data/general/000000000345.jpg",
        "data/general/000000000665.jpg",
        "data/general/000000000674.jpg",
        "data/general/000000000725.jpg",
        "data/general/000000001730.jpg",
        "data/general/000000002271.jpg",
    )
    private val queryAsset = "data/general/000000000019.jpg"

    // Live-loop const
    private val SMOOTHING_ALPHA = 0.25f
    //minCycleMs for Roi mode
    private val MIN_CYCLE_MS = 100.0
    // desiredCycleMs = max(minCycleMs, smoothedMs * HEADROOM_FACTOR)
    private val HEADROOM_FACTOR = 1.35

    @Test
    fun benchmarkPipeline() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        log("══════════════════════════════════════════════════")
        log("One-Shot Detector – FPS Benchmark")
        log("  detection  : $detectionAsset")
        log("  embedding  : $embeddingAsset ($embeddingTag)")
        log("  device     : ${device.label}")
        log("  gallery    : ${galleryAssets.size} images")
        log("  frames     : ${warmupFrames}w + ${measureFrames}r")
        log("══════════════════════════════════════════════════")

        val detector = MediaPipeDetectionWrapper(ctx, detectionAsset, device)
        val embedder = createEmbeddingWrapper(ctx, embeddingAsset, embeddingTag, device)

        log("Computing gallery embeddings (not timed)...")
        val gallery = buildGallery(ctx, embedder)
        log("Gallery ready: ${gallery.size}/${galleryAssets.size} embeddings")

        val queryFrame = loadAsset(ctx, queryAsset)
            ?: Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).also {
                log("WARN: query asset not found, using blank 640×480 bitmap")
            }
        log("Query frame: ${queryFrame.width}×${queryFrame.height}")

        // Warmup — not recorded
        log("Warming up ($warmupFrames frames)...")
        repeat(warmupFrames) { runFrame(detector, embedder, gallery, queryFrame) }

        // Measurement
        log("Measuring ($measureFrames frames)...")
        val totalSamples   = DoubleArray(measureFrames)
        val detectSamples  = DoubleArray(measureFrames)
        val embedSamples   = DoubleArray(measureFrames)
        val compareSamples = DoubleArray(measureFrames)
        val detCounts      = IntArray(measureFrames)

        // Exponential smoothing state
        var smoothedMs = 0.0

        for (i in 0 until measureFrames) {
            val r = runFrame(detector, embedder, gallery, queryFrame)
            totalSamples[i]   = r.totalMs
            detectSamples[i]  = r.detectMs
            embedSamples[i]   = r.embedMs
            compareSamples[i] = r.compareMs
            detCounts[i]      = r.detectionCount

            // Same EMA formula as the live loop
            smoothedMs = if (i == 0) r.totalMs
                         else SMOOTHING_ALPHA * r.totalMs + (1 - SMOOTHING_ALPHA) * smoothedMs
        }

        // Live-loop cycle: max(minCycle, smoothed * headroom)
        val adaptiveCycleMs = maxOf(MIN_CYCLE_MS, smoothedMs * HEADROOM_FACTOR)
        val adaptiveFps     = 1000.0 / adaptiveCycleMs
        val rawFps          = 1000.0 / totalSamples.average()

        log("══════════════════════════════════════════════════")
        log("Results ($measureFrames frames, gallery=${gallery.size})")
        log("  Avg detections/frame : ${"%.1f".format(detCounts.average())}")
        log("  Detection   : ${stats(detectSamples)}")
        log("  Embed+Crop  : ${stats(embedSamples)}")
        log("  Gallery cmp : ${stats(compareSamples)}")
        log("  Total/frame : ${stats(totalSamples)}")
        log("  Smoothed processing  : ${fmt(smoothedMs)}  (EMA α=$SMOOTHING_ALPHA)")
        log("  Adaptive cycle       : ${fmt(adaptiveCycleMs)}  (min=${fmt(MIN_CYCLE_MS)}, headroom=×$HEADROOM_FACTOR)")
        log("  ► Raw FPS            : ${"%.2f".format(rawFps)}  (1000 / mean)")
        log("  ► Live-loop FPS      : ${"%.2f".format(adaptiveFps)}  (1000 / adaptiveCycle)")
        log("══════════════════════════════════════════════════")

        queryFrame.recycle()
        detector.close()
        embedder.close()
    }

    // Per frame processing: detect → embed → compare gallery
    private data class FrameResult(
        val detectMs: Double,
        val embedMs: Double,
        val compareMs: Double,
        val totalMs: Double,
        val detectionCount: Int
    )

    private fun runFrame(
        detector: MediaPipeDetectionWrapper,
        embedder: EmbeddingWrapper,
        gallery: List<FloatArray>,
        frame: Bitmap
    ): FrameResult {
        val t0 = nanoMs()

        // Detect objects in the frame
        val detections = detector.detect(frame)
        val t1 = nanoMs()

        // Embed each detected ROI; fall back to whole-frame embed when none detected
        val roiEmbeddings: List<FloatArray> = if (detections.isEmpty()) {
            listOfNotNull(embedder.embed(frame))
        } else {
            detections.mapNotNull { det ->
                val roi = cropBox(frame, det) ?: return@mapNotNull embedder.embed(frame)
                val emb = embedder.embed(roi)
                roi.recycle()
                emb
            }
        }
        val t2 = nanoMs()

        // Compare every ROI embedding against all gallery embeddings.
        var bestScore = -1f
        for (roiEmb in roiEmbeddings) {
            for (galleryEmb in gallery) {
                val score = dot(roiEmb, galleryEmb)
                if (score > bestScore) bestScore = score
            }
        }
        val t3 = nanoMs()

        return FrameResult(
            detectMs       = t1 - t0,
            embedMs        = t2 - t1,
            compareMs      = t3 - t2,
            totalMs        = t3 - t0,
            detectionCount = detections.size
        )
    }

    // Statistics

    /** One-line summary: mean, p50, p95, p99 */
    private fun stats(samples: DoubleArray): String {
        val sorted = samples.sorted()
        val mean   = sorted.average()
        val median = percentile(sorted, 50.0)
        val variance = samples.fold(0.0) { acc, v -> acc + (v - mean) * (v - mean) } / samples.size
        val std    = sqrt(variance)
        val sb = StringBuilder("avg=${fmt(mean)}  p50=${fmt(median)}  std=${fmt(std)}")
        if (samples.size >= 20) {
            sb.append("  p95=${fmt(percentile(sorted, 95.0))}")
            sb.append("  p99=${fmt(percentile(sorted, 99.0))}")
        }
        return sb.toString()
    }

    private fun percentile(sorted: List<Double>, p: Double): Double {
        val idx = p / 100.0 * (sorted.size - 1)
        val lo  = idx.toInt().coerceIn(0, sorted.size - 1)
        val hi  = (lo + 1).coerceAtMost(sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (idx - lo)
    }

    private fun buildGallery(ctx: Context, embedder: EmbeddingWrapper): List<FloatArray> =
        galleryAssets.mapNotNull { path ->
            val bmp = loadAsset(ctx, path) ?: return@mapNotNull null
            val emb = embedder.embed(bmp)
            bmp.recycle()
            emb
        }

    private fun loadAsset(ctx: Context, path: String): Bitmap? =
        runCatching { ctx.assets.open(path).use { BitmapFactory.decodeStream(it) } }.getOrNull()

    private fun cropBox(bitmap: Bitmap, det: Detection): Bitmap? {
        val box = det.box
        if (box.size < 4) return null
        val w = bitmap.width; val h = bitmap.height
        val norm = box.all { it in 0f..1f }
        val top    = if (norm) (box[0] * h).toInt() else box[0].toInt()
        val left   = if (norm) (box[1] * w).toInt() else box[1].toInt()
        val bottom = if (norm) (box[2] * h).toInt() else box[2].toInt()
        val right  = if (norm) (box[3] * w).toInt() else box[3].toInt()
        val l = left.coerceIn(0, w - 1);  val t = top.coerceIn(0, h - 1)
        val r = right.coerceIn(l + 1, w); val b = bottom.coerceIn(t + 1, h)
        if (r - l <= 1 || b - t <= 1) return null
        return Bitmap.createBitmap(bitmap, l, t, r - l, b - t)
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size); var s = 0f
        for (i in 0 until n) s += a[i] * b[i]
        return s
    }

    private fun nanoMs(): Double = System.nanoTime() / 1_000_000.0

    private fun fmt(ms: Double) = if (ms < 10.0) "%.2f ms".format(ms) else "%.1f ms".format(ms)

    private fun log(msg: String) = Log.i(TAG, msg)

    companion object {
        private const val TAG = "FpsBenchmark"
    }
}