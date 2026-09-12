package dev.slimevr.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

@Serializable
private data class InstalledInferenceSmokeReport(
	val format: String = "nekovr-installed-inference-smoke-v1",
	val offline: Boolean,
	val runtimeFlavor: String,
	val runtimeVersion: String,
	val modelSha256: String,
	val managedImportPassed: Boolean,
	val passedProviders: List<String>,
)

object InstalledInferenceSmokeCommand {
	@JvmStatic
	fun main(arguments: Array<String>) {
		check(System.getenv("NEKOVR_OFFLINE_SMOKE") == "1") { "Installed smoke must run with NEKOVR_OFFLINE_SMOKE=1" }
		val output = arguments.firstOrNull()?.let(Path::of)
		val temporary = Files.createTempDirectory("nekovr-installed-smoke-")
		try {
			val sourceModel = temporary.resolve("small.onnx")
			val sourceSidecar = temporary.resolve("small.onnx.json")
			copyResource("benchmark", "small.onnx", sourceModel)
			copyResource("benchmark", "small.onnx.json", sourceSidecar)
			val probeDirectory = temporary.resolve("probe").also(Files::createDirectory)
			listOf("probe.onnx", "probe.onnx.json", "probe-fixture.json", "manifest.json").forEach {
				copyResource("probe", it, probeDirectory.resolve(it))
			}
			val probe = OnnxProbeBundle.load(probeDirectory)
			val managed = ManagedModelStore(temporary.resolve("models")).importModel(sourceModel, sourceSidecar)
			val passed = mutableListOf<String>()
			JavaOnnxRuntimeBackend().use { backend ->
				val providers = listOf(ExecutionProviderType.CPU) + backend.availableProviders
					.filter { it != ExecutionProviderType.CPU && backend.runtimePackage.allows(it) }.sortedBy { it.name }
				for (provider in providers.distinct()) {
					backend.createSession(probeDirectory.resolve("probe.onnx"), provider).use(probe::verify)
					backend.createSession(managed.modelPath, provider).use { session ->
						ModelArtifactValidator.validateTensorContract(managed.metadata, session.inputInfo, session.outputInfo)
						session.runProbe(managed.metadata.copy(minimumContext = 60, maximumContext = 60, minimumSlots = 10))
					}
					passed += provider.name
				}
				val report = InstalledInferenceSmokeReport(
					offline = true, runtimeFlavor = backend.runtimePackage.flavor, runtimeVersion = backend.runtimePackage.version,
					modelSha256 = managed.metadata.modelSha256, managedImportPassed = true, passedProviders = passed,
				)
				val encoded = Json { prettyPrint = true; encodeDefaults = true }.encodeToString(report) + "\n"
				if (output == null) print(encoded) else {
					output.toAbsolutePath().normalize().parent?.let(Files::createDirectories)
					Files.writeString(output, encoded)
				}
				println("INSTALLED_INFERENCE_SMOKE_OK providers=${passed.joinToString(",")}")
			}
		} finally {
			Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
		}
	}

	private fun copyResource(folder: String, name: String, target: Path) {
		val resource = requireNotNull(InstalledInferenceSmokeCommand::class.java.getResourceAsStream("$folder/$name")) {
			"Packaged inference resource is missing: $folder/$name"
		}
		resource.use { Files.copy(it, target) }
	}
}
