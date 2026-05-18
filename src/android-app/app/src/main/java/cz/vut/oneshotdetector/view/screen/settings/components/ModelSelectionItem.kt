/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.screen.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import cz.vut.oneshotdetector.viewmodel.model.InferenceDevice
import cz.vut.oneshotdetector.viewmodel.model.ModelSelectionViewModel
import cz.vut.oneshotdetector.viewmodel.model.ModelType
import cz.vut.oneshotdetector.viewmodel.model.ModelVariant
import cz.vut.oneshotdetector.viewmodel.model.supportsGpu
import cz.vut.oneshotdetector.viewmodel.model.supportsNpu
import cz.vut.oneshotdetector.view.theme.LocalSpacing

/**
 * Displays one model type with its currently active variant, device chips (CPU / NPU / GPU),
 * and an optional variant picker.
 *
 * When only one variant exists the picker is suppressed and the variant name is shown as
 * static text, avoiding a pointless single-item dropdown.
 */
@Composable
internal fun ModelSelectionItem(
    type: ModelType,
    activeVariant: ModelVariant,
    availableVariants: List<ModelVariant>,
    activeDevice: InferenceDevice,
    onVariantSelected: (ModelVariant) -> Unit,
    onDeviceSelected: (InferenceDevice) -> Unit,
    modifier: Modifier = Modifier,
    npuSupport: ModelSelectionViewModel.NpuSupportResult? = null,
    gpuSupport: ModelSelectionViewModel.GpuSupportResult? = null,
    actualDevice: InferenceDevice? = null
) {
    val spacing = LocalSpacing.current
    var menuExpanded by remember { mutableStateOf(false) }
    val npuRuntimeSupported = npuSupport?.let { activeVariant.supportsNpu(it) } == true
    val gpuRuntimeSupported = gpuSupport?.let { activeVariant.supportsGpu(it) } == true
    val hasNpuVariant = availableVariants.any { it.npuSupported }
    val hasGpuVariant = availableVariants.any { it.gpuSupported }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.xs)
    ) {
        // ── Variant row ───────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = type.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )

            if (availableVariants.size > 1) {
                Box {
                    OutlinedButton(onClick = { menuExpanded = true }) {
                        Text(
                            text = activeVariant.label,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Icon(
                            imageVector = Icons.Filled.ArrowDropDown,
                            contentDescription = null
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        availableVariants.forEach { variant ->
                            val isSelected = variant == activeVariant
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = variant.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                trailingIcon = if (isSelected) {
                                    {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                } else null,
                                onClick = {
                                    onVariantSelected(variant)
                                    menuExpanded = false
                                }
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = activeVariant.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // ── Device chips ──────────────────────────────────────────────────────
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            DeviceButton(
                label = "CPU",
                selected = activeDevice == InferenceDevice.CPU,
                enabled = true,
                supported = true,
                onClick = { onDeviceSelected(InferenceDevice.CPU) }
            )
            if (hasNpuVariant) {
                DeviceButton(
                    label = "NNAPI",
                    selected = activeDevice == InferenceDevice.NPU,
                    enabled = true,
                    supported = npuRuntimeSupported,
                    onClick = { onDeviceSelected(InferenceDevice.NPU) }
                )
            }
            if (hasGpuVariant) {
                DeviceButton(
                    label = "GPU",
                    selected = activeDevice == InferenceDevice.GPU,
                    enabled = true,
                    supported = gpuRuntimeSupported,
                    onClick = { onDeviceSelected(InferenceDevice.GPU) }
                )
            }
        }

        // ── Effective device hint ─────────────────────────────────────────────
        when {
            activeDevice == InferenceDevice.NPU && npuSupport != null -> {
                val (hintText, hintColor) = if (npuRuntimeSupported) {
                    "NNAPI runtime supported — will run on NNAPI" to MaterialTheme.colorScheme.primary
                } else {
                    "NNAPI runtime unavailable — will fall back to CPU" to MaterialTheme.colorScheme.error
                }
                Text(
                    text = hintText,
                    style = MaterialTheme.typography.labelSmall,
                    color = hintColor
                )
            }
            activeDevice == InferenceDevice.GPU && gpuSupport != null -> {
                val (hintText, hintColor) = if (gpuRuntimeSupported) {
                    "GPU runtime supported — will run on GPU" to MaterialTheme.colorScheme.primary
                } else {
                    "GPU runtime unavailable — will fall back to CPU" to MaterialTheme.colorScheme.error
                }
                Text(
                    text = hintText,
                    style = MaterialTheme.typography.labelSmall,
                    color = hintColor
                )
            }
            activeDevice == InferenceDevice.CPU && (npuSupport != null || gpuSupport != null) -> {
                Text(
                    text = "Will run on CPU",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // ── Runtime fallback warning ──────────────────────────────────────────
        if (actualDevice != null) {
            val felledBack = activeDevice != InferenceDevice.CPU && actualDevice == InferenceDevice.CPU
            Text(
                text = if (felledBack) "Fell back to CPU at runtime" else "Running on ${actualDevice.label}",
                style = MaterialTheme.typography.labelSmall,
                color = if (felledBack) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
