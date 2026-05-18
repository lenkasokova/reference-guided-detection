/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.viewmodel.model

import ai.onnxruntime.OrtSession
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cz.vut.oneshotdetector.di.AppContainer
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModelSelectionViewModel(
    private val store: ModelSelectionStore
) : ViewModel() {

    data class NpuSupportResult(val nnapi: Boolean, val litert: Boolean, val mediapipe: Boolean)
    data class GpuSupportResult(val litert: Boolean, val mediapipe: Boolean, val nnapi: Boolean)

    data class UiState(
        val selections: Map<ModelType, ModelVariant>,
        val devices: Map<ModelType, InferenceDevice>,
        val actualDevices: Map<ModelType, InferenceDevice?> = emptyMap(),
        val npuSupport: NpuSupportResult? = null,
        val gpuSupport: GpuSupportResult? = null
    )

    private val _uiState = MutableStateFlow(buildState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        checkNpuAvailability()
    }

    fun selectVariant(type: ModelType, variant: ModelVariant) {
        store.setActiveVariant(type, variant)
        val selectedDevice = store.getActiveDevice(type)
        val variantSupportsSelectedDevice = when (selectedDevice) {
            InferenceDevice.CPU -> true
            InferenceDevice.NPU -> variant.npuSupported
            InferenceDevice.GPU -> variant.gpuSupported
        }
        if (!variantSupportsSelectedDevice) {
            store.setActiveDevice(type, InferenceDevice.CPU)
        }
        _uiState.value = buildState().copy(
            npuSupport = _uiState.value.npuSupport,
            gpuSupport = _uiState.value.gpuSupport
        )
    }

    fun variantsFor(type: ModelType): List<ModelVariant> =
        AVAILABLE_VARIANTS[type] ?: emptyList()

    fun selectDevice(type: ModelType, device: InferenceDevice) {
        val activeVariant = store.getActiveVariant(type)
        val selectedDevice = when (device) {
            InferenceDevice.CPU -> InferenceDevice.CPU
            InferenceDevice.NPU -> selectAcceleratedDevice(
                type = type,
                requestedDevice = device,
                activeVariant = activeVariant,
                supportsRequestedDevice = ModelVariant::npuSupported
            )
            InferenceDevice.GPU -> selectAcceleratedDevice(
                type = type,
                requestedDevice = device,
                activeVariant = activeVariant,
                supportsRequestedDevice = ModelVariant::gpuSupported
            )
        }
        store.setActiveDevice(type, selectedDevice)
        _uiState.value = buildState().copy(
            npuSupport = _uiState.value.npuSupport,
            gpuSupport = _uiState.value.gpuSupport
        )
    }

    private fun selectAcceleratedDevice(
        type: ModelType,
        requestedDevice: InferenceDevice,
        activeVariant: ModelVariant,
        supportsRequestedDevice: (ModelVariant) -> Boolean
    ): InferenceDevice {
        if (supportsRequestedDevice(activeVariant)) return requestedDevice

        val compatibleVariant = variantsFor(type).firstOrNull(supportsRequestedDevice)
        return if (compatibleVariant != null) {
            store.setActiveVariant(type, compatibleVariant)
            requestedDevice
        } else {
            InferenceDevice.CPU
        }
    }

    fun refreshActualDevices() {
        _uiState.value = buildState().copy(
            npuSupport = _uiState.value.npuSupport,
            gpuSupport = _uiState.value.gpuSupport
        )
    }

    private fun checkNpuAvailability() {
        viewModelScope.launch {
            val npuSupport = withContext(Dispatchers.Default) {
                val nnapi = isNnapiDelegateSupportedOnThisDevice() &&
                        runCatching {
                            OrtSession.SessionOptions().apply { addNnapi() }.close()
                            true
                        }.getOrDefault(false)
                val litert = isNnapiDelegateSupportedOnThisDevice() &&
                        runCatching {
                            CompiledModel.Options(Accelerator.NPU)
                            true
                        }.getOrDefault(false)

                val mediapipe = litert
                NpuSupportResult(nnapi = nnapi, litert = litert, mediapipe = mediapipe)
            }
            val npuAvailable = npuSupport.nnapi || npuSupport.litert || npuSupport.mediapipe
            if (npuAvailable) {
                for (type in NPU_PREFERRED_TYPES) {
                    val variant = store.getActiveVariant(type)
                    if (store.getActiveDevice(type) == InferenceDevice.CPU &&
                        variant.supportsNpu(npuSupport)
                    ) {
                        store.setActiveDevice(type, InferenceDevice.NPU)
                    }
                }
            }

            val gpuSupport = withContext(Dispatchers.Default) {
                val litert = runCatching {
                    CompiledModel.Options(Accelerator.GPU)
                    true
                }.getOrDefault(false)
                GpuSupportResult(litert = litert, mediapipe = litert, nnapi = npuSupport.nnapi)
            }

            _uiState.value = buildState().copy(npuSupport = npuSupport, gpuSupport = gpuSupport)
        }
    }

    private fun buildState(): UiState = UiState(
        selections = ModelType.entries.associateWith { store.getActiveVariant(it) },
        devices = ModelType.entries.associateWith { store.getActiveDevice(it) },
        actualDevices = ModelType.entries.associateWith { store.getActualDevice(it) }
    )

    companion object {
        private val NPU_PREFERRED_TYPES = listOf(ModelType.Detection, ModelType.Embedding)

        fun factory(context: Context) = viewModelFactory {
            initializer {
                val container = AppContainer.get(context)
                ModelSelectionViewModel(
                    store = container.modelSelectionStore
                )
            }
        }
    }
}

fun ModelVariant.supportsNpu(support: ModelSelectionViewModel.NpuSupportResult): Boolean {
    if (!npuSupported) return false
    return when (tag) {
        "tflite"    -> support.litert
        "onnx"      -> support.nnapi
        "mediapipe" -> support.mediapipe
        else        -> false
    }
}

fun ModelVariant.supportsGpu(support: ModelSelectionViewModel.GpuSupportResult): Boolean {
    if (!gpuSupported) return false
    return when (tag) {
        "tflite"    -> support.litert
        "mediapipe" -> support.mediapipe
        "onnx"      -> support.nnapi
        else        -> false
    }
}
