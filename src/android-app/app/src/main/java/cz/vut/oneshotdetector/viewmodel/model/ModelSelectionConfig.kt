/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.viewmodel.model

/**
 * A concrete model file that can be loaded for one model type.
 */
data class ModelVariant(
    val id: String,
    val label: String,
    /** Path relative to the app's assets directory. */
    val assetPath: String,
    /** Arbitrary tag for runtime routing mediapipe, tflite, onnx variants */
    val tag: String = "",
    /** Whether this variant supports NPU acceleration (NNAPI / LiteRT / MediaPipe delegate). */
    val npuSupported: Boolean = true,
    /** Whether this variant supports GPU acceleration (LiteRT GPU / MediaPipe GPU delegate).
     *  ONNX Runtime on Android has no GPU execution provider, so ONNX variants must set false. */
    val gpuSupported: Boolean = false
)

/**
 * The set of inference components whose active model can be chosen by the user.
 */
enum class ModelType(val label: String) {
    Embedding("Embedding Model"),
    SegmentEncoder("Encoder"),
    SegmentDecoder("Decoder"),
    Detection("Detection Model")
}

/**
 * Registry of all available model variants for each model type.
 */
val AVAILABLE_VARIANTS: Map<ModelType, List<ModelVariant>> = mapOf(
    ModelType.Embedding to listOf(
        ModelVariant(
            id = "mobilenet_v3a_large_fp32",
            label = "MobileNet V3 Large FP32",
            assetPath = "embeddingModel/mobilenet_v3_large/embedding_fp32.tflite",
            tag = "tflite",
            npuSupported = true,
            gpuSupported = true
        ),
        ModelVariant(
            id = "mobilenet_v3_large_pruned_unstructured_30pct_fp16",
            label = "MobileNet V3 Large Unstructured 30% FP16",
            assetPath = "embeddingModel/mobilenet_v3_large/embedding_pruned_unstructured_30pct_float16.tflite",
            tag = "tflite",
            npuSupported = true,
            gpuSupported = true
        ),
        ModelVariant(
            id = "mobilenet_v3_large_pruned_unstructured_30pct_int8",
            label = "MobileNet V3 Large Unstructured 30% Int8",
            assetPath = "embeddingModel/mobilenet_v3_large/embedding_pruned_unstructured_30pct_int8.tflite",
            tag = "tflite",
            npuSupported = true,
            gpuSupported = false
        ),
        ModelVariant(
            id = "mobilenet_v3_large_pruned_structured_30pct_fp16",
            label = "MobileNet V3 Large Structured 30% FP16",
            assetPath = "embeddingModel/mobilenet_v3_large/embedding_pruned_structured_30pct_finetune_float16.tflite",
            tag = "tflite",
            npuSupported = true,
            gpuSupported = true
        ),
        ModelVariant(
            id = "mobilenet_v3_large_pruned_structured_30pct_int8",
            label = "MobileNet V3 Large Structured 30% Int8",
            assetPath = "embeddingModel/mobilenet_v3_large/embedding_pruned_structured_30pct_finetune_int8.tflite",
            tag = "tflite",
            npuSupported = true,
            gpuSupported = false
        )
    ),
    ModelType.SegmentEncoder to listOf(
        ModelVariant(
            id = "mobilesam_encoder",
            label = "MobileSAM Encoder",
            assetPath = "segmentModel/mobilesam_encoder.onnx",
            tag = "onnx",
            npuSupported = true,
            gpuSupported = false
        ),
        ModelVariant(
            id = "edgesam_encoder",
            label = "EdgeSAM Encoder",
            assetPath = "segmentModel/edgesam_encoder.onnx",
            tag = "onnx",
            npuSupported = true,
            gpuSupported = false
        ),
        ModelVariant(
            id = "mobilesam_encoder_fp16",
            label = "MobileSAM Encoder Fp16",
            assetPath = "segmentModel/mobilesam_encoder_fp16.onnx",
            tag = "onnx",
            npuSupported = true,
            gpuSupported = false
        ),
        ModelVariant(
            id = "edgesam_encoder_fp16",
            label = "EdgeSAM Encoder Fp16",
            assetPath = "segmentModel/edgesam_encoder_fp16.onnx",
            tag = "onnx",
            npuSupported = true,
            gpuSupported = false
        ),
        ModelVariant(
            id = "edgesam_encoder_int8",
            label = "EdgeSAM Encoder Int8",
            assetPath = "segmentModel/edgesam_encoder_int8.onnx",
            tag = "onnx",
            npuSupported = true,
            gpuSupported = false
        )
    ),
    ModelType.SegmentDecoder to listOf(
        ModelVariant(
            id = "mobilesam_decoder",
            label = "MobileSAM Decoder",
            assetPath = "segmentModel/mobilesam_decoder.onnx",
            tag = "onnx",
            npuSupported = true,
            gpuSupported = false
        ),
        ModelVariant(
            id = "edgesam_decoder",
            label = "EdgeSAM Decoder",
            assetPath = "segmentModel/edgesam_decoder.onnx",
            tag = "onnx",
            npuSupported = true,
            gpuSupported = false
        ),
        ModelVariant(
            id = "mobilesam_decoder_fp16",
            label = "MobileSAM Decoder Fp16",
            assetPath = "segmentModel/mobilesam_decoder_fp16.onnx",
            tag = "onnx",
            npuSupported = true,
            gpuSupported = false
        ),
        ModelVariant(
            id = "edgesam_decoder_fp16",
            label = "EdgeSAM Decoder Fp16",
            assetPath = "segmentModel/edgesam_decoder_fp16.onnx",
            tag = "onnx",
            npuSupported = true,
            gpuSupported = false
        ),
        ModelVariant(
            id = "edgesam_decoder_int8",
            label = "EdgeSAM Decoder Int8",
            assetPath = "segmentModel/edgesam_decoder_int8.onnx",
            tag = "onnx",
            npuSupported = true,
            gpuSupported = false
        )
    ),
    ModelType.Detection to listOf(
        ModelVariant(
            id = "efficientdet_lite0_fp32",
            label = "EfficientDet Lite0 FP32",
            assetPath = "detectionModel/efficientdet_lite0.tflite",
            tag = "mediapipe",
            npuSupported = true,
            gpuSupported = true
        ),
        ModelVariant(
            id = "efficientdet_lite0_fp16",
            label = "EfficientDet Lite0 FP16",
            assetPath = "detectionModel/efficientdet_lite0_fp16.tflite",
            tag = "mediapipe",
            npuSupported = true,
            gpuSupported = true
        ),
        ModelVariant(
            id = "efficientdet_lite0_int8",
            label = "EfficientDet Lite0 Int8",
            assetPath = "detectionModel/efficientdet_lite0_int8.tflite",
            tag = "mediapipe",
            npuSupported = true,
            gpuSupported = false
        ),
        ModelVariant(
            id = "efficientdet_lite2_fp16",
            label = "EfficientDet Lite2 FP16",
            assetPath = "detectionModel/efficientdet_lite2_fp16.tflite",
            tag = "mediapipe",
            npuSupported = true,
            gpuSupported = true
        ),
        ModelVariant(
            id = "efficientdet_lite2_fp32",
            label = "EfficientDet Lite2 FP32",
            assetPath = "detectionModel/efficientdet_lite2.tflite",
            tag = "mediapipe",
            npuSupported = true,
            gpuSupported = true
        ),
        ModelVariant(
            id = "efficientdet_lite2_int8",
            label = "EfficientDet Lite2 Int8",
            assetPath = "detectionModel/efficientdet_lite2_int8.tflite",
            tag = "mediapipe",
            npuSupported = true,
            gpuSupported = false
        )
    )
)

data class ModelSelectionSection(
    val label: String,
    val types: List<ModelType>
)

val MODEL_SELECTION_SECTIONS: List<ModelSelectionSection> = listOf(
    ModelSelectionSection("Embedding", listOf(ModelType.Embedding)),
    ModelSelectionSection("Segmentation", listOf(ModelType.SegmentEncoder, ModelType.SegmentDecoder)),
    ModelSelectionSection("Detection", listOf(ModelType.Detection))
)

fun ModelVariant.matchingSegmentationVariant(targetType: ModelType): ModelVariant? {
    val targetIds = when (targetType) {
        ModelType.SegmentEncoder -> listOf(id.replace("_decoder", "_encoder"))
        ModelType.SegmentDecoder -> listOf(id.replace("_encoder", "_decoder"))
        else -> return null
    }
    return AVAILABLE_VARIANTS[targetType]
        ?.firstOrNull { candidate -> candidate.id in targetIds }
}

fun segmentationVariantsMatch(encoder: ModelVariant, decoder: ModelVariant): Boolean =
    encoder.matchingSegmentationVariant(ModelType.SegmentDecoder)?.id == decoder.id
