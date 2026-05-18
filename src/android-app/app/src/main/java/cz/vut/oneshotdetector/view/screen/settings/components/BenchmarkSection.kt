/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.screen.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cz.vut.oneshotdetector.viewmodel.benchmark.BenchmarkItemRunState
import cz.vut.oneshotdetector.viewmodel.benchmark.BenchmarkViewModel
import cz.vut.oneshotdetector.viewmodel.model.InferenceDevice
import cz.vut.oneshotdetector.viewmodel.model.AVAILABLE_VARIANTS
import cz.vut.oneshotdetector.viewmodel.model.MODEL_SELECTION_SECTIONS
import cz.vut.oneshotdetector.viewmodel.model.ModelSelectionSection
import cz.vut.oneshotdetector.viewmodel.model.ModelType
import cz.vut.oneshotdetector.view.components.OutlinedBox
import cz.vut.oneshotdetector.view.theme.LocalSpacing

/**
 * Full Benchmark section rendered inside the Settings screen.
 *
 * Displays a config summary, a Run All button, per-model cards grouped by the
 * model selection sections, and export buttons when results are available.
 */
@Composable
fun BenchmarkSection(
    uiState: BenchmarkViewModel.UiState,
    viewModel: BenchmarkViewModel,
    modifier: Modifier = Modifier
) {
    val spacing = LocalSpacing.current
    val isAnyRunning = uiState.isRunningAll ||
            uiState.itemStates.values.any { it is BenchmarkItemRunState.Running }
    val hasResults = uiState.itemStates.values.any { it is BenchmarkItemRunState.Completed }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm)
    ) {
        // ── Header: title + config summary + Run All ──────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.screenHorizontal),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Benchmark",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = uiState.config.summaryLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = viewModel::runAll,
                enabled = !isAnyRunning
            ) {
                Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null)
                Text(text = "Run All", modifier = Modifier.padding(start = spacing.xs))
            }
        }

        // ── Device picker ─────────────────────────────────────────────────────
        val npuAvailable = uiState.npuAvailable == true
        val gpuAvailable = uiState.gpuAvailable == true

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.screenHorizontal),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Text(
                text = "Device",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            DeviceButton(
                label = "CPU",
                selected = uiState.config.device == InferenceDevice.CPU,
                enabled = !isAnyRunning,
                onClick = { viewModel.updateConfig(uiState.config.copy(device = InferenceDevice.CPU)) }
            )
            if (npuAvailable) {
                DeviceButton(
                    label = "NNAPI",
                    selected = uiState.config.device == InferenceDevice.NPU,
                    enabled = !isAnyRunning,
                    onClick = { viewModel.updateConfig(uiState.config.copy(device = InferenceDevice.NPU)) }
                )
            }
            if (gpuAvailable) {
                DeviceButton(
                    label = "GPU",
                    selected = uiState.config.device == InferenceDevice.GPU,
                    enabled = !isAnyRunning,
                    onClick = { viewModel.updateConfig(uiState.config.copy(device = InferenceDevice.GPU)) }
                )
            }
        }

        // ── Hardware availability status ──────────────────────────────────────
        uiState.npuAvailable?.let { available ->
            Text(
                text = if (available) "NNAPI available on this device"
                       else "NNAPI unavailable on this device",
                style = MaterialTheme.typography.bodySmall,
                color = if (available) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal)
            )
        }
        uiState.gpuAvailable?.let { available ->
            Text(
                text = if (available) "GPU available on this device"
                       else "GPU unavailable on this device",
                style = MaterialTheme.typography.bodySmall,
                color = if (available) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal)
            )
        }

        // ── Per-section cards ─────────────────────────────────────────────────
        MODEL_SELECTION_SECTIONS.forEach { section ->
            BenchmarkSectionCard(
                section    = section,
                uiState    = uiState,
                isAnyRunning = isAnyRunning,
                viewModel  = viewModel
            )
        }

        // ── Export bar (visible only when at least one result is ready) ───────
        if (hasResults) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.screenHorizontal),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                OutlinedButton(
                    onClick = viewModel::exportJson,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Export JSON")
                }
                OutlinedButton(
                    onClick = viewModel::exportCsv,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Export CSV")
                }
            }
        }

        // ── Exported file path toast ──────────────────────────────────────────
        uiState.exportedFilePath?.let { path ->
            Text(
                text = "Saved: $path",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = spacing.screenHorizontal)
            )
        }
    }
}

@Composable
private fun BenchmarkSectionCard(
    section: ModelSelectionSection,
    uiState: BenchmarkViewModel.UiState,
    isAnyRunning: Boolean,
    viewModel: BenchmarkViewModel,
    modifier: Modifier = Modifier
) {
    val spacing = LocalSpacing.current
    val config = uiState.config

    OutlinedBox(
        modifier = modifier.padding(horizontal = spacing.screenHorizontal),
        innerPadding = spacing.md
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(
                text = section.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            section.types.forEachIndexed { index, type ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                val variants = AVAILABLE_VARIANTS[type] ?: emptyList()
                val activeVariant = config.variantFor(type)
                BenchmarkItemCard(
                    type             = type,
                    itemState        = uiState.itemStates[type] ?: BenchmarkItemRunState.Idle,
                    activeVariant    = activeVariant,
                    availableVariants = variants,
                    isAnyRunning     = isAnyRunning,
                    onVariantSelected = { variant ->
                        viewModel.updateConfig(
                            config.copy(variantOverrides = config.variantOverrides + (type to variant))
                        )
                    },
                    onRunClicked     = { viewModel.runBenchmark(type) }
                )
            }
        }
    }
}
