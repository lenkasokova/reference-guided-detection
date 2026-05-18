/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.viewmodel.benchmark

import ai.onnxruntime.OrtSession
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import cz.vut.oneshotdetector.di.AppContainer
import cz.vut.oneshotdetector.viewmodel.benchmark.runners.DetectionRunner
import cz.vut.oneshotdetector.viewmodel.benchmark.runners.EmbeddingRunner
import cz.vut.oneshotdetector.viewmodel.benchmark.runners.ModelBenchmarkRunner
import cz.vut.oneshotdetector.viewmodel.benchmark.runners.SegmentDecoderRunner
import cz.vut.oneshotdetector.viewmodel.benchmark.runners.SegmentEncoderRunner
import cz.vut.oneshotdetector.viewmodel.model.AVAILABLE_VARIANTS
import cz.vut.oneshotdetector.viewmodel.model.InferenceDevice
import cz.vut.oneshotdetector.viewmodel.model.MODEL_SELECTION_SECTIONS
import cz.vut.oneshotdetector.viewmodel.model.ModelType
import cz.vut.oneshotdetector.viewmodel.model.isNnapiDelegateSupportedOnThisDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class BenchmarkViewModel(
    private val context: Context,
    private val exporter: BenchmarkExporter
) : ViewModel() {

    data class UiState(
        val config: BenchmarkConfig = DEFAULT_BENCHMARK_CONFIG,
        val itemStates: Map<ModelType, BenchmarkItemRunState> = ModelType.entries.associateWith { BenchmarkItemRunState.Idle },
        val isRunningAll: Boolean = false,
        val exportedFilePath: String? = null,
        val npuAvailable: Boolean? = null,
        val gpuAvailable: Boolean? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val npuAvailable = withContext(Dispatchers.Default) {
                isNnapiDelegateSupportedOnThisDevice() &&
                        runCatching {
                            OrtSession.SessionOptions().apply { addNnapi() }.close()
                            true
                        }.getOrDefault(false)
            }
            val gpuAvailable = withContext(Dispatchers.Default) {
                runCatching {
                    CompiledModel.Options(Accelerator.GPU)
                    true
                }.getOrDefault(false) ||
                runCatching {
                    BaseOptions.builder().setDelegate(Delegate.GPU).build()
                    true
                }.getOrDefault(false)
            }
            _uiState.update { it.copy(npuAvailable = npuAvailable, gpuAvailable = gpuAvailable) }
        }
    }

    fun updateConfig(config: BenchmarkConfig) {
        _uiState.update { it.copy(config = config.withCompatibleVariants()) }
    }

    fun runBenchmark(type: ModelType) {
        viewModelScope.launch {
            val config = _uiState.value.config
            val variant = config.variantFor(type)
            val runner = runnerFor(type)
            val dataset = BenchmarkDataset(context, config)

            setItemState(type, BenchmarkItemRunState.Running("Preparing…"))
            try {
                val result = runner.run(variant, dataset, config) { progress ->
                    setItemState(type, BenchmarkItemRunState.Running(progress))
                }
                setItemState(type, BenchmarkItemRunState.Completed(result))
            } catch (e: Exception) {
                setItemState(type, BenchmarkItemRunState.Failed(e.message ?: "Unknown error"))
            }
        }
    }

    fun runAll() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRunningAll = true) }
            val allTypes = MODEL_SELECTION_SECTIONS.flatMap { it.types }
            val config = _uiState.value.config
            val dataset = BenchmarkDataset(context, config)

            for (type in allTypes) {
                val variant = config.variantFor(type)
                val runner = runnerFor(type)
                setItemState(type, BenchmarkItemRunState.Running("Preparing…"))
                try {
                    val result = runner.run(variant, dataset, config) { progress ->
                        setItemState(type, BenchmarkItemRunState.Running(progress))
                    }
                    setItemState(type, BenchmarkItemRunState.Completed(result))
                } catch (e: Exception) {
                    setItemState(type, BenchmarkItemRunState.Failed(e.message ?: "Unknown error"))
                }
            }
            _uiState.update { it.copy(isRunningAll = false) }
        }
    }

    fun exportJson() {
        viewModelScope.launch {
            val results = completedResults()
            if (results.isEmpty()) return@launch
            val path = exporter.exportJson(results)
            _uiState.update { it.copy(exportedFilePath = path) }
        }
    }

    fun exportCsv() {
        viewModelScope.launch {
            val results = completedResults()
            if (results.isEmpty()) return@launch
            val path = exporter.exportCsv(results)
            _uiState.update { it.copy(exportedFilePath = path) }
        }
    }

    fun clearExportedPath() {
        _uiState.update { it.copy(exportedFilePath = null) }
    }

    private fun setItemState(type: ModelType, state: BenchmarkItemRunState) {
        _uiState.update { it.copy(itemStates = it.itemStates + (type to state)) }
    }

    private fun completedResults(): List<ModelBenchmarkResult> =
        _uiState.value.itemStates.values
            .filterIsInstance<BenchmarkItemRunState.Completed>()
            .map { it.result }

    private fun runnerFor(type: ModelType): ModelBenchmarkRunner = when (type) {
        ModelType.Embedding      -> EmbeddingRunner(context)
        ModelType.SegmentEncoder -> SegmentEncoderRunner(context)
        ModelType.SegmentDecoder -> SegmentDecoderRunner(context)
        ModelType.Detection      -> DetectionRunner(context)
    }

    private fun BenchmarkConfig.withCompatibleVariants(): BenchmarkConfig {
        if (device != InferenceDevice.GPU) return this

        val adjustedOverrides = ModelType.entries.fold(variantOverrides) { overrides, type ->
            val activeVariant = overrides[type] ?: AVAILABLE_VARIANTS[type]?.firstOrNull()
            if (activeVariant == null || activeVariant.gpuSupported) {
                overrides
            } else {
                val gpuVariant = AVAILABLE_VARIANTS[type]?.firstOrNull { it.gpuSupported }
                if (gpuVariant != null) overrides + (type to gpuVariant) else overrides
            }
        }

        return if (adjustedOverrides == variantOverrides) this
        else copy(variantOverrides = adjustedOverrides)
    }

    companion object {
        fun factory(context: Context) = viewModelFactory {
            initializer {
                val container = AppContainer.get(context)
                BenchmarkViewModel(
                    context = container.appContext,
                    exporter = container.benchmarkExporter()
                )
            }
        }
    }
}
