/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.screen.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cz.vut.oneshotdetector.viewmodel.model.InferenceDevice
import cz.vut.oneshotdetector.viewmodel.model.MODEL_SELECTION_SECTIONS
import cz.vut.oneshotdetector.viewmodel.model.ModelSelectionSection
import cz.vut.oneshotdetector.viewmodel.model.ModelSelectionViewModel
import cz.vut.oneshotdetector.viewmodel.model.ModelType
import cz.vut.oneshotdetector.viewmodel.model.ModelVariant
import cz.vut.oneshotdetector.view.components.OutlinedBox
import cz.vut.oneshotdetector.view.theme.LocalSpacing

/**
 * Renders the full Model Selection settings section.
 *
 * The layout is driven by the model selection section list. If we add a new group
 * or move a model type, we only need to update the model selection config.
 *
 * Each model type shows CPU / NPU / GPU chips when the selected variant supports that
 * accelerator and the matching runtime is available.
 */
@Composable
fun ModelSelectionSection(
    uiState: ModelSelectionViewModel.UiState,
    viewModel: ModelSelectionViewModel,
    modifier: Modifier = Modifier
) {
    val spacing = LocalSpacing.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm)
    ) {
        Text(
            text = "Model Selection",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = spacing.screenHorizontal)
        )

        MODEL_SELECTION_SECTIONS.forEach { section ->
            ModelSectionCard(
                section = section,
                activeSelections = uiState.selections,
                activeDevices = uiState.devices,
                actualDevices = uiState.actualDevices,
                npuSupport = uiState.npuSupport,
                gpuSupport = uiState.gpuSupport,
                variantsFor = viewModel::variantsFor,
                onVariantSelected = viewModel::selectVariant,
                onDeviceSelected = viewModel::selectDevice
            )
        }
    }
}

@Composable
private fun ModelSectionCard(
    section: ModelSelectionSection,
    activeSelections: Map<ModelType, ModelVariant>,
    activeDevices: Map<ModelType, InferenceDevice>,
    actualDevices: Map<ModelType, InferenceDevice?>,
    npuSupport: ModelSelectionViewModel.NpuSupportResult?,
    gpuSupport: ModelSelectionViewModel.GpuSupportResult?,
    variantsFor: (ModelType) -> List<ModelVariant>,
    onVariantSelected: (ModelType, ModelVariant) -> Unit,
    onDeviceSelected: (ModelType, InferenceDevice) -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = LocalSpacing.current

    OutlinedBox(
        modifier = modifier.padding(horizontal = spacing.screenHorizontal),
        innerPadding = spacing.md
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(
                text = section.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            section.types.forEachIndexed { index, type ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                ModelSelectionItem(
                    type = type,
                    activeVariant = activeSelections[type] ?: variantsFor(type).first(),
                    availableVariants = variantsFor(type),
                    activeDevice = activeDevices[type] ?: InferenceDevice.CPU,
                    actualDevice = actualDevices[type],
                    gpuSupport = gpuSupport,
                    npuSupport = npuSupport,
                    onVariantSelected = { variant -> onVariantSelected(type, variant) },
                    onDeviceSelected = { device -> onDeviceSelected(type, device) }
                )
            }
        }
    }
}
