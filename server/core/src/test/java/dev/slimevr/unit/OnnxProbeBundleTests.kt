package dev.slimevr.unit

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.slimevr.ai.InferenceTensorOutput
import dev.slimevr.ai.ExecutionProviderType
import dev.slimevr.ai.JavaOnnxRuntimeBackend
import dev.slimevr.ai.LoadedModelSession
import dev.slimevr.ai.ManagedModelStore
import dev.slimevr.ai.ModelLoadErrorCode
import dev.slimevr.ai.ModelLoadException
import dev.slimevr.ai.OnnxProbeBundle
import dev.slimevr.ai.RuntimeTensorInfo
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OnnxProbeBundleTests {
	private val mapper = ObjectMapper()

	@Test
	fun `bundle authenticates every file and rejects partial or changed evidence`() {
		val directory = copyBundle()
		try {
			OnnxProbeBundle.load(directory)
			directory.resolve("probe.onnx.json").writeBytes(directory.resolve("probe.onnx.json").readBytes() + byteArrayOf(32))
			assertEquals(ModelLoadErrorCode.MODEL_INTEGRITY, assertFailsWith<ModelLoadException> { OnnxProbeBundle.load(directory) }.code)

			copyResource("probe.onnx.json", directory.resolve("probe.onnx.json"), replace = true)
			Files.delete(directory.resolve("probe-fixture.json"))
			assertEquals(ModelLoadErrorCode.MODEL_INTEGRITY, assertFailsWith<ModelLoadException> { OnnxProbeBundle.load(directory) }.code)
		} finally {
			deleteTree(directory)
		}
	}

	@Test
	fun `finite but wrong provider output is rejected against committed fixture`() {
		val directory = copyBundle()
		try {
			val bundle = OnnxProbeBundle.load(directory)
			val session = FakeSession(bundle)
			val error = assertFailsWith<ModelLoadException> { bundle.verify(session) }
			assertEquals(ModelLoadErrorCode.PROBE_FAILED, error.code)
		} finally {
			deleteTree(directory)
		}
	}

	@Test
	fun `wrong expected output remains rejected when attacker recomputes its manifest hash`() {
		val directory = copyBundle()
		try {
			val original = OnnxProbeBundle.load(directory)
			val fixturePath = directory.resolve("probe-fixture.json")
			val fixture = mapper.readTree(fixturePath.toFile()) as ObjectNode
			val confidence = (fixture.get("expected_outputs") as ObjectNode).withArray("confidence").get(0) as com.fasterxml.jackson.databind.node.ArrayNode
			confidence.set(0, mapper.nodeFactory.numberNode(confidence[0].floatValue() + 0.25f))
			mapper.writerWithDefaultPrettyPrinter().writeValue(fixturePath.toFile(), fixture)
			val manifestPath = directory.resolve("manifest.json")
			val manifest = mapper.readTree(manifestPath.toFile()) as ObjectNode
			(manifest.get("files") as ObjectNode).put("probe-fixture.json", ManagedModelStore.sha256(Files.readAllBytes(fixturePath)))
			mapper.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(), manifest)
			val changed = OnnxProbeBundle.load(directory)
			val session = FakeSession(changed, original.expectedOutput)
			assertEquals(ModelLoadErrorCode.PROBE_FAILED, assertFailsWith<ModelLoadException> { changed.verify(session) }.code)
		} finally {
			deleteTree(directory)
		}
	}

	@Test
	fun `unsupported opset is rejected even when sidecar and manifest agree`() {
		val directory = copyBundle()
		try {
			val sidecarPath = directory.resolve("probe.onnx.json")
			val sidecar = mapper.readTree(sidecarPath.toFile()) as ObjectNode
			sidecar.put("opset", 19)
			mapper.writeValue(sidecarPath.toFile(), sidecar)
			updateManifestHash(directory, "probe.onnx.json")
			assertEquals(ModelLoadErrorCode.SIDECAR_INVALID, assertFailsWith<ModelLoadException> { OnnxProbeBundle.load(directory) }.code)
		} finally {
			deleteTree(directory)
		}
	}

	@Test
	fun `mutually rehashed malicious model sidecar and manifest fail before provider probe`() {
		val directory = copyBundle()
		try {
			val modelPath = directory.resolve("probe.onnx")
			val bytes = modelPath.readBytes()
			bytes[0] = (bytes[0].toInt() xor 0x7f).toByte()
			modelPath.writeBytes(bytes)
			val sidecarPath = directory.resolve("probe.onnx.json")
			val sidecar = mapper.readTree(sidecarPath.toFile()) as ObjectNode
			sidecar.put("model_sha256", ManagedModelStore.sha256(bytes))
			sidecar.put("model_size_bytes", bytes.size)
			mapper.writeValue(sidecarPath.toFile(), sidecar)
			updateManifestHash(directory, "probe.onnx")
			updateManifestHash(directory, "probe.onnx.json")

			OnnxProbeBundle.load(directory)
			JavaOnnxRuntimeBackend().use { backend ->
				assertFailsWith<ModelLoadException> { backend.createSession(modelPath, ExecutionProviderType.CPU) }
			}
		} finally {
			deleteTree(directory)
		}
	}

	private fun updateManifestHash(directory: Path, name: String) {
		val manifestPath = directory.resolve("manifest.json")
		val manifest = mapper.readTree(manifestPath.toFile()) as ObjectNode
		(manifest.get("files") as ObjectNode).put(name, ManagedModelStore.sha256(Files.readAllBytes(directory.resolve(name))))
		mapper.writeValue(manifestPath.toFile(), manifest)
	}

	private class FakeSession(bundle: OnnxProbeBundle, private val output: InferenceTensorOutput = InferenceTensorOutput(
		FloatArray(bundle.input.slots * 3), FloatArray(bundle.input.slots), FloatArray(bundle.input.slots),
	)) : LoadedModelSession {
		override val inputInfo: Map<String, RuntimeTensorInfo> = bundle.metadata.inputs.associate {
			it.name to RuntimeTensorInfo(it.dtype, it.shape.map { value -> (value as? Number)?.toLong() ?: -1L }.toLongArray())
		}
		override val outputInfo: Map<String, RuntimeTensorInfo> = bundle.metadata.outputs.associate {
			it.name to RuntimeTensorInfo(it.dtype, it.shape.map { value -> (value as? Number)?.toLong() ?: -1L }.toLongArray())
		}
		override fun runProbe(metadata: dev.slimevr.ai.ModelArtifactMetadata) = Unit
		override fun runInference(input: dev.slimevr.ai.InferenceTensorBatch): InferenceTensorOutput = output
		override fun close() = Unit
	}

	private fun copyBundle(): Path = Files.createTempDirectory("nekovr-probe-bundle-test-").also { directory ->
		listOf("probe.onnx", "probe.onnx.json", "probe-fixture.json", "manifest.json").forEach {
			copyResource(it, directory.resolve(it))
		}
	}

	private fun copyResource(name: String, target: Path, replace: Boolean = false) {
		val resource = requireNotNull(javaClass.getResourceAsStream("/dev/slimevr/ai/probe/$name"))
		resource.use {
			if (replace) Files.copy(it, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING) else Files.copy(it, target)
		}
	}

	private fun deleteTree(root: Path) {
		Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
	}
}
