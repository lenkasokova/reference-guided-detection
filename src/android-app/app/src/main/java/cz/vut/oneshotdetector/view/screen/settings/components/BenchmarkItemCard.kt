/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.screen.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import cz.vut.oneshotdetector.viewmodel.benchmark.BenchmarkItemRunState
import cz.vut.oneshotdetector.viewmodel.model.ModelType
import cz.vut.oneshotdetector.viewmodel.model.ModelVariant
import cz.vut.oneshotdetector.view.theme.LocalDimens
import cz.vut.oneshotdetector.view.theme.LocalSpacing

/**
 * One row in the Benchmark section: type label, optional variant picker, run button, and result.
 *
 * When running, the play button is replaced by a spinner and a progress message is shown.
 * When completed, the benchmark stats display shows the full stats grid.
 * When failed, a compact error message is shown.
 */
@Composable
internal fun BenchmarkItemCard(
    type: ModelType,
    itemState: BenchmarkItemRunState,
    activeVariant: ModelVariant,
    availableVariants: List<ModelVariant>,
    isAnyRunning: Boolean,
    onVariantSelected: (ModelVariant) -> Unit,
    onRunClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = LocalSpacing.current
    val dimens = LocalDimens.current
    var menuExpanded by remember { mutableStateOf(false) }
    val isRunning = itemState is BenchmarkItemRunState.Running

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.xs)
    ) {
        // ── Header row: type label + variant picker + run/stop button ─────────
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

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs)
            ) {
                // Variant picker (only when multiple variants exist)
                if (availableVariants.size > 1) {
                    Box {
                        OutlinedButton(
                            onClick = { menuExpanded = true },
                            enabled = !isAnyRunning,
                            modifier = Modifier.widthIn(max = dimens.actionButtonHeight * 1.75f)
                        ) {
                            Text(
                                text = activeVariant.label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(imageVector = Icons.Filled.ArrowDropDown, contentDescription = null)
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    FilledTonalIconButton(onClick = onRunClicked, enabled = !isAnyRunning) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Run benchmark for ${type.label}"
                        )
                    }
                }
            }
        }

        // ── Status / result area ──────────────────────────────────────────────
        when (itemState) {
            is BenchmarkItemRunState.Idle      -> Unit
            is BenchmarkItemRunState.Running   -> Text(
                text = itemState.progress,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            is BenchmarkItemRunState.Completed -> BenchmarkStatsDisplay(
                primaryStats   = itemState.result.primaryStats,
                primaryLabel   = itemState.result.primaryLabel,
                secondaryStats = itemState.result.secondaryStats,
                secondaryLabel = itemState.result.secondaryLabel
            )
            is BenchmarkItemRunState.Failed    -> Text(
                text = "Error: ${itemState.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
