/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.screen.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import cz.vut.oneshotdetector.viewmodel.benchmark.BenchmarkStats
import cz.vut.oneshotdetector.viewmodel.benchmark.formatMs
import cz.vut.oneshotdetector.view.theme.LocalSpacing

/**
 * Compact statistics grid for one benchmark phase (primary or secondary).
 *
 * Shows mean · median · min · max · std in one row and p95 · p99 below when available.
 * Uses monospace font so columns stay visually aligned.
 */
@Composable
internal fun BenchmarkStatsDisplay(
    primaryStats: BenchmarkStats,
    primaryLabel: String,
    secondaryStats: BenchmarkStats? = null,
    secondaryLabel: String? = null,
    modifier: Modifier = Modifier
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.xs)
    ) {
        StatsBlock(label = primaryLabel, stats = primaryStats)
        if (secondaryStats != null && secondaryLabel != null) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = spacing.xs)
            )
            StatsBlock(label = secondaryLabel, stats = secondaryStats)
        }
    }
}

@Composable
private fun StatsBlock(label: String, stats: BenchmarkStats) {
    val spacing = LocalSpacing.current
    val mono = FontFamily.Monospace
    val valueStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = mono)
    val labelStyle = MaterialTheme.typography.labelSmall

    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text(
            text = label,
            style = labelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // ── Row 1: mean / median / std ────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StatCell(name = "mean",   value = stats.meanMs.formatMs(),   style = valueStyle)
            StatCell(name = "median", value = stats.medianMs.formatMs(), style = valueStyle)
            StatCell(name = "std",    value = stats.stdMs.formatMs(),    style = valueStyle)
        }

        // ── Row 2: min / max / (p95 / p99 if available) ──────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StatCell(name = "min", value = stats.minMs.formatMs(), style = valueStyle)
            StatCell(name = "max", value = stats.maxMs.formatMs(), style = valueStyle)
            if (stats.p95Ms != null && stats.p99Ms != null) {
                StatCell(name = "p95", value = stats.p95Ms.formatMs(), style = valueStyle)
                StatCell(name = "p99", value = stats.p99Ms.formatMs(), style = valueStyle)
            } else {
                StatCell(name = "n", value = "${stats.sampleCount}", style = valueStyle)
            }
        }
    }
}

@Composable
private fun StatCell(
    name: String,
    value: String,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text(text = value, style = style, color = MaterialTheme.colorScheme.primary)
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
