/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.viewmodel.model

import android.content.Context
import android.content.SharedPreferences

/**
 * Stores and provides the active model variant for each model type.
 *
 * Services and UI code use the shared instance from getShared.
 */
class ModelSelectionStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Returns the currently active model variant for the given type.
     * Falls back to the first available variant if nothing has been selected yet.
     */
    fun getActiveVariant(type: ModelType): ModelVariant {
        val storedId = prefs.getString(type.name, null)
        val variants = AVAILABLE_VARIANTS[type] ?: return unknownVariant(type)
        return variants.firstOrNull { it.id == storedId } ?: variants.first()
    }

    /**
     * Returns the asset path for the active model variant of the given type.
     * Convenience wrapper used by inference services.
     */
    fun getActivePath(type: ModelType): String = getActiveVariant(type).assetPath

    /** Saves the given variant as the active selection for the given type. */
    fun setActiveVariant(type: ModelType, variant: ModelVariant) {
        prefs.edit().putString(type.name, variant.id).apply()
    }

    /** Returns the active inference device for the given type. The default is CPU. */
    fun getActiveDevice(type: ModelType): InferenceDevice {
        val stored = prefs.getString("device_${type.name}", null) ?: return InferenceDevice.CPU
        return InferenceDevice.entries.firstOrNull { it.name == stored } ?: InferenceDevice.CPU
    }

    /** Saves the given execution device for the given type. */
    fun setActiveDevice(type: ModelType, device: InferenceDevice) {
        prefs.edit().putString("device_${type.name}", device.name).apply()
        actualDevices.remove(type)
    }

    // Runtime actual device tracking

    private val actualDevices = mutableMapOf<ModelType, InferenceDevice>()

    /** Called by inference services after a wrapper is loaded to record which device was actually used. */
    fun reportActualDevice(type: ModelType, device: InferenceDevice) {
        actualDevices[type] = device
    }

    /** Returns the device a model is actually running on, or null if not yet loaded. */
    fun getActualDevice(type: ModelType): InferenceDevice? = actualDevices[type]

    private fun unknownVariant(type: ModelType) =
        ModelVariant(id = "", label = type.label, assetPath = "")

    companion object {
        private const val PREFS_NAME = "model_selection"

        @Volatile
        private var instance: ModelSelectionStore? = null

        /** Returns the process-wide singleton, creating it on first call. */
        fun getShared(context: Context): ModelSelectionStore =
            instance ?: synchronized(this) {
                instance ?: ModelSelectionStore(context.applicationContext).also { instance = it }
            }
    }
}
