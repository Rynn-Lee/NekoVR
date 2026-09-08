package dev.slimevr.ai

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

object InferenceReadyReportCommand {
	private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false }
	private val mapper = ObjectMapper()

	@JvmStatic
	fun main(arguments: Array<String>) {
		require(arguments.size == 2) { "Usage: EVIDENCE-MANIFEST.json OUTPUT.json" }
		val manifestPath = Path.of(arguments[0]).toAbsolutePath().normalize()
		val manifest = json.decodeFromString<InferenceReadyEvidenceManifest>(Files.readString(manifestPath))
		require(manifest.format == "nekovr-inference-ready-evidence-v1") { "Unsupported evidence manifest format" }
		val evidence = manifest.evidence.map { source -> verifySource(manifestPath.parent, manifest.modelSha256, source) }
		val requiredPassed = REQUIRED_INFERENCE_READY_EVIDENCE.all { id -> evidence.singleOrNull { it.id == id }?.passed == true }
		val report = InferenceReadyReport(
			generatedUtc = Instant.now().toString(), buildCommit = manifest.buildCommit,
			modelSha256 = manifest.modelSha256.lowercase(), ready = requiredPassed, evidence = evidence,
		)
		val evaluated = InferenceReadyReportStore.evaluate(report)
		val output = Path.of(arguments[1]).toAbsolutePath().normalize()
		output.parent?.let(Files::createDirectories)
		val temporary = output.resolveSibling("${output.fileName}.tmp")
		Files.writeString(temporary, json.encodeToString(report) + "\n")
		try {
			Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
		} catch (_: AtomicMoveNotSupportedException) {
			Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
		}
		check(evaluated.ready) { evaluated.detail }
		println("INFERENCE_READY_OK model=${report.modelSha256} output=$output")
	}

	private fun verifySource(base: Path, expectedModelSha256: String, source: InferenceReadyEvidenceSource): InferenceReadyEvidence {
		val path = base.resolve(source.path).normalize()
		if (!path.startsWith(base.normalize())) return failed(source, path, "Evidence path escapes the manifest directory")
		if (!Files.isRegularFile(path)) return failed(source, path, "Evidence file is missing")
		val actualHash = InferenceReadyReportStore.sha256(path)
		if (!actualHash.equals(source.sha256, true)) return failed(source, path, "Evidence SHA-256 mismatch")
		return runCatching {
			val root = mapper.readTree(path.toFile())
			val passed = root.path("passed").takeIf { it.isBoolean }?.asBoolean()
				?: throw IllegalArgumentException("Evidence does not expose top-level passed")
			val modelHash = sequenceOf("modelSha256", "model_sha256").map { root.path(it).asText("") }.firstOrNull(String::isNotBlank)
			if (modelHash != null && !modelHash.equals(expectedModelSha256, true)) {
				throw IllegalArgumentException("Evidence belongs to a different model")
			}
			InferenceReadyEvidence(source.id, passed, source.detail, source.path, actualHash)
		}.getOrElse { failed(source, path, it.message ?: "Evidence is invalid", actualHash) }
	}

	private fun failed(source: InferenceReadyEvidenceSource, path: Path, detail: String, hash: String = "") = InferenceReadyEvidence(
		source.id, false, listOf(source.detail, detail).filter(String::isNotBlank).joinToString(": "), path.toString(), hash,
	)
}
