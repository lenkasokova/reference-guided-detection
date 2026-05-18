/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cz.vut.oneshotdetector.viewmodel.benchmark.BenchmarkViewModel
import cz.vut.oneshotdetector.viewmodel.gallery.GalleryViewModel
import cz.vut.oneshotdetector.viewmodel.model.ModelSelectionViewModel
import cz.vut.oneshotdetector.view.layout.AppBackButton
import cz.vut.oneshotdetector.view.layout.AppScreen
import cz.vut.oneshotdetector.view.layout.AppTopBar
import cz.vut.oneshotdetector.view.layout.ScreenColumn
import cz.vut.oneshotdetector.view.screen.settings.components.BenchmarkSection
import cz.vut.oneshotdetector.view.screen.settings.components.ModelSelectionSection
import cz.vut.oneshotdetector.view.theme.LocalSpacing
import kotlinx.coroutines.launch

private sealed interface RecomputeState {
    data object Idle : RecomputeState
    data class Running(val done: Int, val total: Int) : RecomputeState
    data class Done(val updated: Int, val total: Int) : RecomputeState
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val spacing = LocalSpacing.current

    val modelSelectionViewModel: ModelSelectionViewModel = viewModel(factory = ModelSelectionViewModel.factory(context))
    val benchmarkViewModel: BenchmarkViewModel = viewModel(factory = BenchmarkViewModel.factory(context))
    val galleryViewModel: GalleryViewModel = viewModel(factory = GalleryViewModel.factory(context))
    val modelSelectionUiState by modelSelectionViewModel.uiState.collectAsState()
    val benchmarkUiState by benchmarkViewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    var recomputeState by remember { mutableStateOf<RecomputeState>(RecomputeState.Idle) }

    LaunchedEffect(Unit) { modelSelectionViewModel.refreshActualDevices() }

    AppScreen(
        topBar = {
            AppTopBar(
                title = { Text("Settings", color = MaterialTheme.colorScheme.onTertiary) },
                navigationIcon = { AppBackButton(onClick = onBack) }
            )
        }
    ) { paddingValues ->
        ScreenColumn(
            paddingValues = paddingValues,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.tertiary)
                .verticalScroll(rememberScrollState())
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            ModelSelectionSection(
                uiState = modelSelectionUiState,
                viewModel = modelSelectionViewModel,
                modifier = Modifier.padding(vertical = spacing.md)
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.screenHorizontal, vertical = spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                Text(
                    text = "Gallery Embeddings",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Recompute embeddings for all gallery images using the currently selected embedding model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                var lastTotal = 0
                                recomputeState = RecomputeState.Running(0, 0)
                                val updated = galleryViewModel.recomputeAllEmbeddings { done, total ->
                                    lastTotal = total
                                    recomputeState = RecomputeState.Running(done, total)
                                }
                                recomputeState = RecomputeState.Done(updated = updated, total = lastTotal)
                            }
                        },
                        enabled = recomputeState !is RecomputeState.Running
                    ) {
                        Text("Recompute Embeddings")
                    }
                    when (val state = recomputeState) {
                        is RecomputeState.Idle -> Unit
                        is RecomputeState.Running -> {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                text = if (state.total > 0) "${state.done}/${state.total}" else "Starting…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        is RecomputeState.Done -> Text(
                            text = "Done: ${state.updated}/${state.total} updated",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            BenchmarkSection(
                uiState = benchmarkUiState,
                viewModel = benchmarkViewModel,
                modifier = Modifier.padding(vertical = spacing.md)
            )
        }
    }
}
