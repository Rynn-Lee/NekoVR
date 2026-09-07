package dev.slimevr.ai

import java.nio.file.Files

object OnnxRuntimeProbeCommand {
	@JvmStatic
	fun main(arguments: Array<String>) {
		val provider = arguments.firstOrNull()?.let { ExecutionProviderType.valueOf(it.uppercase()) } ?: ExecutionProviderType.CPU
		if (provider == ExecutionProviderType.AUTO) error("Pass a concrete provider to verify packaging")
		val directory = Files.createTempDirectory("nekovr-onnx-probe-")
		val model = directory.resolve("probe.onnx")
		val sidecar = directory.resolve("probe.onnx.json")
		try {
			copyResource("probe.onnx", model)
			copyResource("probe.onnx.json", sidecar)
			AIDriftEngine().use { engine ->
				val result = engine.loadModel(model, sidecar, provider)
				check(result.activated && result.provider == provider) {
					"$provider package probe failed: ${result.failure?.code} ${result.failure?.message}"
				}
				println("ONNX_RUNTIME_PROBE_OK provider=$provider runtime=${engine.activeStatus?.runtimeVersion} flavor=${engine.activeStatus?.runtimeFlavor}")
			}
		} finally {
			Files.deleteIfExists(sidecar)
			Files.deleteIfExists(model)
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
