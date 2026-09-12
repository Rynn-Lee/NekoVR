package dev.slimevr.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.math.abs

data class OnnxProbeBundle(
	val directory: Path,
	val metadata: ModelArtifactMetadata,
	val input: InferenceTensorBatch,
	val expectedOutput: InferenceTensorOutput,
	val absoluteTolerance: Float,
) {
	fun verify(session: LoadedModelSession) {
		ModelArtifactValidator.validateTensorContract(metadata, session.inputInfo, session.outputInfo)
		val actual = try {
			session.runInference(input)
		} catch (error: Throwable) {
			if (error is ModelLoadException) throw error
			throw ModelLoadException(ModelLoadErrorCode.PROBE_FAILED, "ONNX fixture probe inference failed", error)
		}
		compare("correction_rotation_vectors", actual.correctionRotationVectors, expectedOutput.correctionRotationVectors)
		compare("confidence", actual.confidence, expectedOutput.confidence)
		compare("drift_rate", actual.driftRate, expectedOutput.driftRate)
	}

	private fun compare(name: String, actual: FloatArray, expected: FloatArray) {
		if (actual.size != expected.size) {
			throw ModelLoadException(ModelLoadErrorCode.PROBE_FAILED, "Probe output $name has an unexpected size")
		}
		actual.indices.forEach { index ->
			if (!actual[index].isFinite() || !expected[index].isFinite() || abs(actual[index] - expected[index]) > absoluteTolerance) {
				throw ModelLoadException(ModelLoadErrorCode.PROBE_FAILED, "Probe output $name differs from the committed fixture")
			}
		}
	}

	companion object {
		private const val MANIFEST_FORMAT = "nekovr-onnx-probe-manifest-v1"
		private const val FIXTURE_FORMAT = "nekovr-onnx-probe-v1"
		private val requiredFiles = setOf("probe.onnx", "probe.onnx.json", "probe-fixture.json")
		private val mapper = ObjectMapper()

		fun load(directory: Path): OnnxProbeBundle {
			val normalized = directory.toAbsolutePath().normalize()
			val manifest = readJson(normalized.resolve("manifest.json"), "probe manifest")
			if (manifest.path("format").asText() != MANIFEST_FORMAT || manifest.path("deterministic_seed").asInt(-1) != 805) {
				invalid("Probe manifest format or deterministic seed is invalid")
			}
			val files = manifest.path("files")
			if (!files.isObject || files.fieldNames().asSequence().toSet() != requiredFiles) {
				invalid("Probe manifest must bind the complete model, sidecar, and fixture")
			}
			for (name in requiredFiles) {
				val expected = files.path(name).takeIf(JsonNode::isTextual)?.asText()
				val path = normalized.resolve(name).normalize()
				if (path.parent != normalized || expected?.length != 64 || !Files.isRegularFile(path) || sha256(path) != expected) {
					throw ModelLoadException(ModelLoadErrorCode.MODEL_INTEGRITY, "Probe file hash differs from manifest: $name")
				}
			}

			val metadata = ModelArtifactValidator.validate(normalized.resolve("probe.onnx"), normalized.resolve("probe.onnx.json"))
			val fixture = readJson(normalized.resolve("probe-fixture.json"), "probe fixture")
			val tolerance = fixture.path("absolute_tolerance").takeIf(JsonNode::isNumber)?.floatValue() ?: -1f
			if (fixture.path("format").asText() != FIXTURE_FORMAT || !tolerance.isFinite() || tolerance <= 0f || tolerance > 1e-3f) {
				invalid("Probe fixture format or tolerance is invalid")
			}
			val inputs = fixture.path("inputs")
			val outputs = fixture.path("expected_outputs")
			if (inputs.fieldNames().asSequence().toSet() != setOf("features", "role_ids", "slot_mask", "channel_validity", "time_deltas_s", "time_mask") ||
				outputs.fieldNames().asSequence().toSet() != setOf("correction_rotation_vectors", "confidence", "drift_rate")) {
				invalid("Probe fixture tensor names are noncanonical")
			}
			val featureShape = shape(inputs.path("features"), "features")
			if (featureShape.size != 4 || featureShape[0] != 1) invalid("Probe features shape is invalid")
			val time = featureShape[1]
			val slots = featureShape[2]
			val featureCount = featureShape[3]
			if (featureCount != metadata.featureCount || time !in metadata.minimumContext..metadata.maximumContext || slots !in metadata.minimumSlots..metadata.maximumSlots) {
				invalid("Probe inputs exceed model bounds")
			}
			fun requireShape(name: String, expected: List<Int>) {
				if (shape(inputs.path(name), name) != expected) invalid("Probe input $name shape is invalid")
			}
			requireShape("role_ids", listOf(1, slots))
			requireShape("slot_mask", listOf(1, slots))
			requireShape("channel_validity", listOf(1, time, slots, featureCount))
			requireShape("time_deltas_s", listOf(1, time))
			requireShape("time_mask", listOf(1, time))
			if (shape(outputs.path("correction_rotation_vectors"), "correction_rotation_vectors") != listOf(1, slots, 3) ||
				shape(outputs.path("confidence"), "confidence") != listOf(1, slots) ||
				shape(outputs.path("drift_rate"), "drift_rate") != listOf(1, slots)) {
				invalid("Probe expected output shape is invalid")
			}
			return OnnxProbeBundle(
				normalized, metadata,
				InferenceTensorBatch(
					time, slots, featureCount, floats(inputs.path("features")), longs(inputs.path("role_ids")),
					booleans(inputs.path("slot_mask")), booleans(inputs.path("channel_validity")),
					floats(inputs.path("time_deltas_s")), booleans(inputs.path("time_mask")),
				),
				InferenceTensorOutput(
					floats(outputs.path("correction_rotation_vectors")), floats(outputs.path("confidence")), floats(outputs.path("drift_rate")),
				),
				tolerance,
			)
		}

		private fun readJson(path: Path, label: String): JsonNode = try {
			mapper.readTree(path.toFile())
		} catch (error: Exception) {
			throw ModelLoadException(ModelLoadErrorCode.MODEL_INTEGRITY, "Packaged $label is missing or invalid", error)
		}

		private fun shape(node: JsonNode, name: String): List<Int> {
			if (!node.isArray || node.isEmpty) invalid("Probe tensor $name must be a non-empty array")
			val childArrays = node.all { it.isArray }
			val childScalars = node.all { !it.isArray && (it.isNumber || it.isBoolean) }
			if (!childArrays && !childScalars) invalid("Probe tensor $name is ragged or contains invalid values")
			if (childScalars) return listOf(node.size())
			val childShape = shape(node[0], name)
			if (node.any { shape(it, name) != childShape }) invalid("Probe tensor $name is ragged")
			return listOf(node.size()) + childShape
		}

		private fun floats(node: JsonNode): FloatArray = node.flatten().map {
			if (!it.isNumber || !it.asDouble().isFinite()) invalid("Probe tensor contains a non-finite number")
			it.floatValue()
		}.toFloatArray()

		private fun longs(node: JsonNode): LongArray = node.flatten().map {
			if (!it.isIntegralNumber) invalid("Probe integer tensor contains a non-integer")
			it.longValue()
		}.toLongArray()

		private fun booleans(node: JsonNode): BooleanArray = node.flatten().map {
			if (!it.isBoolean) invalid("Probe boolean tensor contains a non-boolean")
			it.booleanValue()
		}.toBooleanArray()

		private fun JsonNode.flatten(): List<JsonNode> = if (isArray) flatMap { it.flatten() } else listOf(this)

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

		private fun invalid(message: String): Nothing = throw ModelLoadException(ModelLoadErrorCode.MODEL_INTEGRITY, message)
	}
}
