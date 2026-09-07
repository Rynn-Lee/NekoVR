package dev.slimevr.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

enum class ModelLoadErrorCode {
	RUNTIME_UNAVAILABLE,
	SIDECAR_INVALID,
	MODEL_INTEGRITY,
	FEATURE_SCHEMA_MISMATCH,
	TENSOR_CONTRACT_MISMATCH,
	PROVIDER_NOT_PACKAGED,
	PROVIDER_UNAVAILABLE,
	SESSION_CREATION_FAILED,
	PROBE_FAILED,
}

class ModelLoadException(
	val code: ModelLoadErrorCode,
	message: String,
	cause: Throwable? = null,
) : RuntimeException(message, cause)

data class ModelTensorContract(
	val name: String,
	val dtype: String,
	val shape: List<Any>,
)

data class ModelArtifactMetadata(
	val modelId: String,
	val modelVersion: String,
	val modelSha256: String,
	val modelSizeBytes: Long,
	val featureSchemaSha256: String,
	val inputs: List<ModelTensorContract>,
	val outputs: List<ModelTensorContract>,
	val minimumSlots: Int,
	val maximumSlots: Int,
	val minimumContext: Int,
	val maximumContext: Int,
	val normalizationMean: FloatArray,
	val normalizationStandardDeviation: FloatArray,
	val supportedRoles: Set<Int>,
	val opset: Int,
) {
	val featureCount: Int
		get() = normalizationMean.size
}

object ModelArtifactValidator {
	private const val SIDECAR_FORMAT = "nekovr-model-sidecar-v1"
	private val mapper = ObjectMapper()

	fun validate(modelPath: Path, sidecarPath: Path, expectedFeatureSchemaSha256: String? = null): ModelArtifactMetadata {
		if (!Files.isRegularFile(modelPath) || !Files.isRegularFile(sidecarPath)) {
			throw ModelLoadException(ModelLoadErrorCode.SIDECAR_INVALID, "Model and sidecar must be regular files")
		}
		val root = try {
			mapper.readTree(sidecarPath.toFile())
		} catch (error: Exception) {
			throw ModelLoadException(ModelLoadErrorCode.SIDECAR_INVALID, "Unable to parse model sidecar", error)
		}
		fun text(name: String): String = root.path(name).takeIf(JsonNode::isTextual)?.asText()
			?: throw ModelLoadException(ModelLoadErrorCode.SIDECAR_INVALID, "Missing sidecar string: $name")
		fun positiveInt(parent: JsonNode, name: String): Int = parent.path(name).takeIf(JsonNode::isIntegralNumber)?.asInt()
			?.takeIf { it > 0 } ?: throw ModelLoadException(ModelLoadErrorCode.SIDECAR_INVALID, "Invalid positive sidecar integer: $name")
		if (text("format") != SIDECAR_FORMAT || root.path("schema_version").asInt(-1) != 1) {
			throw ModelLoadException(ModelLoadErrorCode.SIDECAR_INVALID, "Unsupported model sidecar format")
		}
		val expectedSize = root.path("model_size_bytes").asLong(-1)
		val expectedHash = text("model_sha256")
		if (expectedSize <= 0 || expectedHash.length != 64 || Files.size(modelPath) != expectedSize || sha256(modelPath) != expectedHash) {
			throw ModelLoadException(ModelLoadErrorCode.MODEL_INTEGRITY, "Model size or SHA-256 differs from sidecar")
		}
		val featureHash = text("feature_schema_sha256")
		if (expectedFeatureSchemaSha256 != null && featureHash != expectedFeatureSchemaSha256) {
			throw ModelLoadException(ModelLoadErrorCode.FEATURE_SCHEMA_MISMATCH, "Model feature-schema hash is incompatible")
		}
		val slots = root.path("slot_bounds")
		val context = root.path("context_bounds")
		val minimumSlots = positiveInt(slots, "minimum")
		val maximumSlots = positiveInt(slots, "maximum")
		val minimumContext = positiveInt(context, "minimum")
		val maximumContext = positiveInt(context, "maximum")
		if (minimumSlots > maximumSlots || minimumContext > maximumContext) {
			throw ModelLoadException(ModelLoadErrorCode.SIDECAR_INVALID, "Sidecar bounds are reversed")
		}
		val normalization = root.path("normalization")
		val mean = finiteFloatArray(normalization.path("mean"), "normalization.mean")
		val standardDeviation = finiteFloatArray(normalization.path("standard_deviation"), "normalization.standard_deviation")
		if (mean.isEmpty() || mean.size != standardDeviation.size || standardDeviation.any { it <= 0f }) {
			throw ModelLoadException(ModelLoadErrorCode.SIDECAR_INVALID, "Normalization vectors must be non-empty, equally sized, and have positive deviations")
		}
		val supportedRolesNode = root.path("supported_roles")
		if (!supportedRolesNode.isArray) throw ModelLoadException(ModelLoadErrorCode.SIDECAR_INVALID, "supported_roles must be an array")
		val supportedRoles = supportedRolesNode.map {
			if (!it.isIntegralNumber || it.asInt() < 0) throw ModelLoadException(ModelLoadErrorCode.SIDECAR_INVALID, "supported_roles contains an invalid ID")
			it.asInt()
		}.toSet()
		if (supportedRoles.size != supportedRolesNode.size()) throw ModelLoadException(ModelLoadErrorCode.SIDECAR_INVALID, "supported_roles must be unique")
		val parsedInputs = tensors(root.path("inputs"))
		val featuresWidth = parsedInputs.firstOrNull { it.name == "features" }?.shape?.lastOrNull() as? Number
		if (featuresWidth?.toInt() != mean.size) {
			throw ModelLoadException(ModelLoadErrorCode.SIDECAR_INVALID, "Normalization width differs from the features tensor")
		}
		return ModelArtifactMetadata(
			modelId = text("model_id"), modelVersion = text("model_version"), modelSha256 = expectedHash,
			modelSizeBytes = expectedSize, featureSchemaSha256 = featureHash,
			inputs = parsedInputs, outputs = tensors(root.path("outputs")),
			minimumSlots = minimumSlots, maximumSlots = maximumSlots,
			minimumContext = minimumContext, maximumContext = maximumContext,
			normalizationMean = mean, normalizationStandardDeviation = standardDeviation,
			supportedRoles = supportedRoles,
			opset = positiveInt(root, "opset"),
		)
	}

	fun validateTensorContract(metadata: ModelArtifactMetadata, inputInfo: Map<String, RuntimeTensorInfo>, outputInfo: Map<String, RuntimeTensorInfo>) {
		validateTensors(metadata.inputs, inputInfo, "input")
		validateTensors(metadata.outputs, outputInfo, "output")
	}

	private fun validateTensors(expected: List<ModelTensorContract>, actual: Map<String, RuntimeTensorInfo>, kind: String) {
		if (expected.map { it.name }.toSet() != actual.keys) {
			throw ModelLoadException(ModelLoadErrorCode.TENSOR_CONTRACT_MISMATCH, "ONNX $kind names differ from sidecar")
		}
		for (tensor in expected) {
			val info = actual[tensor.name]
				?: throw ModelLoadException(ModelLoadErrorCode.TENSOR_CONTRACT_MISMATCH, "${tensor.name} is not a tensor")
			if (info.dtype != tensor.dtype || info.shape.size != tensor.shape.size) {
				throw ModelLoadException(ModelLoadErrorCode.TENSOR_CONTRACT_MISMATCH, "Type/rank mismatch for ${tensor.name}")
			}
			tensor.shape.forEachIndexed { index, dimension ->
				if (dimension is Number && dimension.toLong() != info.shape[index]) {
					throw ModelLoadException(ModelLoadErrorCode.TENSOR_CONTRACT_MISMATCH, "Shape mismatch for ${tensor.name}")
				}
			}
		}
	}

	private fun tensors(node: JsonNode): List<ModelTensorContract> {
		if (!node.isArray || node.isEmpty) throw ModelLoadException(ModelLoadErrorCode.SIDECAR_INVALID, "Tensor contract is empty")
		return node.map { tensor ->
			val shape = tensor.path("shape")
			if (!shape.isArray) throw ModelLoadException(ModelLoadErrorCode.SIDECAR_INVALID, "Tensor shape is missing")
			ModelTensorContract(
				name = tensor.path("name").asText(""), dtype = tensor.path("dtype").asText(""),
				shape = shape.map { if (it.isIntegralNumber) it.asLong() else it.asText() },
			).also {
				if (it.name.isBlank() || it.dtype.isBlank()) throw ModelLoadException(ModelLoadErrorCode.SIDECAR_INVALID, "Invalid tensor contract")
			}
		}
	}

	private fun finiteFloatArray(node: JsonNode, name: String): FloatArray {
		if (!node.isArray) throw ModelLoadException(ModelLoadErrorCode.SIDECAR_INVALID, "$name must be an array")
		return FloatArray(node.size()) { index ->
			val value = node[index]
			if (!value.isNumber || !value.asDouble().isFinite()) throw ModelLoadException(ModelLoadErrorCode.SIDECAR_INVALID, "$name contains a non-finite value")
			value.floatValue()
		}
	}

	private fun sha256(path: Path): String {
		val digest = MessageDigest.getInstance("SHA-256")
		Files.newInputStream(path).use { input ->
			val buffer = ByteArray(1024 * 1024)
			while (true) {
				val count = input.read(buffer)
				if (count < 0) break
				digest.update(buffer, 0, count)
			}
		}
		return digest.digest().joinToString("") { "%02x".format(it) }
	}
}
