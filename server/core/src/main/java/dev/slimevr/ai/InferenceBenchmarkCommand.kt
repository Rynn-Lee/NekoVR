package dev.slimevr.ai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object InferenceBenchmarkCommand {
	private val reportJson = Json { prettyPrint = true; encodeDefaults = true; explicitNulls = true }

	@JvmStatic
	fun main(arguments: Array<String>) {
		val options = parse(arguments)
		val provider = ExecutionProviderType.valueOf(options["provider"]?.uppercase() ?: "CPU")
		require(provider != ExecutionProviderType.AUTO) { "Benchmark requires a concrete provider" }
		val output = Path.of(options["output"] ?: "build/reports/ai-inference/benchmark.json").toAbsolutePath().normalize()
		val suppliedModel = options["model"]?.let(Path::of)
		val suppliedSidecar = options["sidecar"]?.let(Path::of)
		require((suppliedModel == null) == (suppliedSidecar == null)) { "Pass both --model and --sidecar, or neither to use the packaged probe" }
		val temporary = if (suppliedModel == null) Files.createTempDirectory("nekovr-benchmark-probe-") else null
		val model = suppliedModel ?: temporary!!.resolve("probe.onnx")
		val sidecar = suppliedSidecar ?: temporary!!.resolve("probe.onnx.json")
		try {
			val probeBundle = if (temporary != null) {
				listOf("probe.onnx", "probe.onnx.json", "probe-fixture.json", "manifest.json").forEach { copyResource(it, temporary.resolve(it)) }
				OnnxProbeBundle.load(temporary)
			} else null
			val metadata = ModelArtifactValidator.validate(model, sidecar)
			JavaOnnxRuntimeBackend().use { backend ->
				if (probeBundle != null) backend.createSession(model, provider).use(probeBundle::verify)
				val defaultTrackers = listOf(metadata.minimumSlots, metadata.maximumSlots).distinct()
				val defaultContexts = listOf(metadata.minimumContext, metadata.maximumContext).distinct()
				val config = InferenceBenchmarkConfig(
					trackerCounts = csvInts(options["trackers"]) ?: defaultTrackers,
					contextSizes = csvInts(options["contexts"]) ?: defaultContexts,
					iterations = options["iterations"]?.toInt() ?: 500,
					warmupInferences = options["warmup"]?.toInt() ?: 30,
					submissionRateHz = options["submission-hz"]?.toDouble() ?: 100.0,
					resourceSamplePeriodMillis = options["resource-sample-ms"]?.toLong() ?: 100L,
					drainTimeoutMillis = options["drain-timeout-ms"]?.toLong() ?: 10_000L,
					benchmarkHostId = options["host-id"] ?: "unspecified",
				)
				val report = InferenceBenchmarkRunner(metadata, { backend.createSession(model, provider) }, backend.runtimePackage, provider).run(config)
				writeAtomically(output, reportJson.encodeToString(report) + "\n")
				println("AI_INFERENCE_BENCHMARK_OK scenarios=${report.scenarios.size} output=$output")
			}
		} finally {
			if (temporary != null) {
				listOf("manifest.json", "probe-fixture.json", "probe.onnx.json", "probe.onnx").forEach { Files.deleteIfExists(temporary.resolve(it)) }
				Files.deleteIfExists(temporary)
			}
		}
	}

	private fun parse(arguments: Array<String>): Map<String, String> {
		val result = linkedMapOf<String, String>()
		var index = 0
		while (index < arguments.size) {
			val key = arguments[index]
			require(key.startsWith("--") && index + 1 < arguments.size) { "Expected --name value arguments" }
			result[key.removePrefix("--")] = arguments[index + 1]
			index += 2
		}
		return result
	}

	private fun csvInts(value: String?): List<Int>? = value?.split(',')?.map { it.trim().toInt() }?.filter { it > 0 }?.also {
		require(it.isNotEmpty()) { "Benchmark matrix must not be empty" }
	}

	private fun copyResource(name: String, target: Path) {
		val resource = requireNotNull(InferenceBenchmarkCommand::class.java.getResourceAsStream("probe/$name")) { "Packaged ONNX probe is missing: $name" }
		resource.use { Files.copy(it, target) }
	}

	private fun writeAtomically(output: Path, content: String) {
		output.parent?.let(Files::createDirectories)
		val temporary = output.resolveSibling("${output.fileName}.tmp")
		Files.writeString(temporary, content)
		try {
			Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
		} catch (_: AtomicMoveNotSupportedException) {
			Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
		}
	}
}
