package dev.slimevr.ai

import java.nio.file.Files

object OnnxRuntimeProbeCommand {
	@JvmStatic
	fun main(arguments: Array<String>) {
		val provider = arguments.firstOrNull()?.let { ExecutionProviderType.valueOf(it.uppercase()) } ?: ExecutionProviderType.CPU
		if (provider == ExecutionProviderType.AUTO) error("Pass a concrete provider to verify packaging")
		val directory = Files.createTempDirectory("nekovr-onnx-probe-")
		try {
			listOf("probe.onnx", "probe.onnx.json", "probe-fixture.json", "manifest.json").forEach { copyResource(it, directory.resolve(it)) }
			val bundle = OnnxProbeBundle.load(directory)
			JavaOnnxRuntimeBackend().use { backend ->
				backend.createSession(directory.resolve("probe.onnx"), provider).use(bundle::verify)
				println("ONNX_RUNTIME_PROBE_OK provider=$provider runtime=${backend.runtimePackage.version} flavor=${backend.runtimePackage.flavor}")
			}
		} finally {
			listOf("manifest.json", "probe-fixture.json", "probe.onnx.json", "probe.onnx").forEach { Files.deleteIfExists(directory.resolve(it)) }
			Files.deleteIfExists(directory)
		}
	}

	private fun copyResource(name: String, target: java.nio.file.Path) {
		val resource = requireNotNull(OnnxRuntimeProbeCommand::class.java.getResourceAsStream("probe/$name")) {
			"Packaged ONNX probe resource is missing: $name"
		}
		resource.use { Files.copy(it, target) }
	}
}
