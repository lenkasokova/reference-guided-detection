/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.viewmodel.benchmark

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports benchmark results to JSON or CSV files in the app's external files directory.
 */
class BenchmarkExporter(private val context: Context) {

    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    suspend fun exportJson(results: List<ModelBenchmarkResult>): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = outputFile("json")
                file.writeText(buildJson(results))
                file.absolutePath
            }.getOrNull()
        }

    suspend fun exportCsv(results: List<ModelBenchmarkResult>): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = outputFile("csv")
                file.writeText(buildCsv(results))
                file.absolutePath
            }.getOrNull()
        }

    // Builders

    private fun buildJson(results: List<ModelBenchmarkResult>): String {
        val sb = StringBuilder()
        sb.appendLine("[")
        results.forEachIndexed { resultIdx, r ->
            sb.appendLine("  {")
            sb.appendLine("    \"modelType\": \"${r.modelType.name}\",")
            sb.appendLine("    \"variant\": \"${r.variant.label}\",")
            sb.appendLine("    \"device\": \"${r.config.device.name}\",")
            sb.appendLine("    \"warmUpRuns\": ${r.config.warmUpRuns},")
            sb.appendLine("    \"measurementRuns\": ${r.config.measurementRuns},")
            sb.appendLine("    \"timestampMs\": ${r.timestampEpochMs},")
            sb.appendLine("    \"primary\": {")
            sb.appendLine("      \"label\": \"${r.primaryLabel}\",")
            sb.appendStatsJson(r.primaryStats, indent = "      ")
            sb.appendLine("    }${if (r.secondaryStats != null) "," else ""}")
            if (r.secondaryStats != null && r.secondaryLabel != null) {
                sb.appendLine("    ,\"secondary\": {")
                sb.appendLine("      \"label\": \"${r.secondaryLabel}\",")
                sb.appendStatsJson(r.secondaryStats, indent = "      ")
                sb.appendLine("    }")
            }
            sb.append("  }")
            if (resultIdx < results.lastIndex) sb.append(",")
            sb.appendLine()
        }
        sb.append("]")
        return sb.toString()
    }

    private fun StringBuilder.appendStatsJson(stats: BenchmarkStats, indent: String) {
        appendLine("${indent}\"meanMs\": ${stats.meanMs.format6()},")
        appendLine("${indent}\"medianMs\": ${stats.medianMs.format6()},")
        appendLine("${indent}\"minMs\": ${stats.minMs.format6()},")
        appendLine("${indent}\"maxMs\": ${stats.maxMs.format6()},")
        appendLine("${indent}\"stdMs\": ${stats.stdMs.format6()},")
        appendLine("${indent}\"p95Ms\": ${stats.p95Ms?.format6() ?: "null"},")
        appendLine("${indent}\"p99Ms\": ${stats.p99Ms?.format6() ?: "null"},")
        append("${indent}\"sampleCount\": ${stats.sampleCount}")
        appendLine()
    }

    private fun buildCsv(results: List<ModelBenchmarkResult>): String {
        val sb = StringBuilder()
        val header = listOf(
            "modelType", "variantLabel", "phase", "device",
            "warmUpRuns", "measurementRuns",
            "meanMs", "medianMs", "minMs", "maxMs", "stdMs", "p95Ms", "p99Ms", "sampleCount"
        )
        sb.appendLine(header.joinToString(","))

        for (r in results) {
            sb.appendCsvRow(r, r.primaryLabel, r.primaryStats)
            if (r.secondaryStats != null && r.secondaryLabel != null) {
                sb.appendCsvRow(r, r.secondaryLabel, r.secondaryStats)
            }
        }
        return sb.toString()
    }

    private fun StringBuilder.appendCsvRow(
        r: ModelBenchmarkResult,
        phase: String,
        stats: BenchmarkStats
    ) {
        val row = listOf(
            r.modelType.name,
            r.variant.label.csvEscape(),
            phase.csvEscape(),
            r.config.device.name,
            r.config.warmUpRuns.toString(),
            r.config.measurementRuns.toString(),
            stats.meanMs.format6(),
            stats.medianMs.format6(),
            stats.minMs.format6(),
            stats.maxMs.format6(),
            stats.stdMs.format6(),
            stats.p95Ms?.format6() ?: "",
            stats.p99Ms?.format6() ?: "",
            stats.sampleCount.toString()
        )
        appendLine(row.joinToString(","))
    }

    // Helpers

    private fun outputFile(extension: String): File {
        val dir = File(context.getExternalFilesDir(null), "benchmark").also { it.mkdirs() }
        val ts = dateFormat.format(Date())
        return File(dir, "benchmark_${ts}.$extension")
    }

    private fun Double.format6(): String = "%.6f".format(this)

    private fun String.csvEscape(): String =
        if (contains(',') || contains('"') || contains('\n')) "\"${replace("\"", "\"\"")}\"" else this
}
