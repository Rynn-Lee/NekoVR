package dev.slimevr.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists

const val INFERENCE_READY_REPORT_SCHEMA: Int = 1
const val INFERENCE_READY_REPORT_FILE: String = "inference-ready-report.json"

val REQUIRED_INFERENCE_READY_EVIDENCE: Set<String> = linkedSetOf(
	"parity",
	"provider-package",
	"layout",
	"watchdog",
	"safety",
	"quality",
	"baseline-regression",
	"performance",
	"shadow-dogfood",
)

@Serializable
data class InferenceReadyEvidenceSource(
	val id: String,
	val path: String,
	val sha256: String,
	val detail: String = "",
)

@Serializable
data class InferenceReadyEvidenceManifest(
	val format: String = "nekovr-inference-ready-evidence-v1",
	val buildCommit: String,
	val modelSha256: String,
	val evidence: List<InferenceReadyEvidenceSource>,
)

@Serializable
data class InferenceReadyEvidence(
	val id: String,
	val passed: Boolean,
	val detail: String,
	val source: String,
	val sourceSha256: String,
)

@Serializable
data class InferenceReadyReport(
	val format: String = "nekovr-inference-ready-report-v1",
	val schemaVersion: Int = INFERENCE_READY_REPORT_SCHEMA,
	val generatedUtc: String,
	val buildCommit: String,
	val modelSha256: String,
	val ready: Boolean,
	val evidence: List<InferenceReadyEvidence>,
)

data class InferenceReadyStatus(
	val ready: Boolean,
	val detail: String,
	val report: InferenceReadyReport? = null,
)

object InferenceReadyReportStore {
	private val json = Json { ignoreUnknownKeys = true }
	private val hashPattern = Regex("^[0-9a-f]{64}$")

	fun resolve(modelsRoot: Path): Path {
		val explicit = System.getProperty("nekovr.inferenceReadyReport")
			?.takeIf(String::isNotBlank)
			?: System.getenv("NEKOVR_INFERENCE_READY_REPORT")?.takeIf(String::isNotBlank)
		return explicit?.let(Path::of) ?: modelsRoot.resolve(INFERENCE_READY_REPORT_FILE)
	}

	fun load(path: Path): InferenceReadyStatus {
		if (!path.exists()) return InferenceReadyStatus(false, "Inference-ready report is missing: $path")
		return runCatching {
			val report = Files.newBufferedReader(path).use { json.decodeFromString<InferenceReadyReport>(it.readText()) }
			evaluate(report)
		}.getOrElse { InferenceReadyStatus(false, "Inference-ready report is invalid: ${it.message}") }
	}

	fun evaluate(report: InferenceReadyReport): InferenceReadyStatus {
		if (report.schemaVersion != INFERENCE_READY_REPORT_SCHEMA) {
			return InferenceReadyStatus(false, "Unsupported inference-ready report schema ${report.schemaVersion}", report)
		}
		if (!hashPattern.matches(report.modelSha256)) return InferenceReadyStatus(false, "Report model SHA-256 is invalid", report)
		val duplicates = report.evidence.groupingBy { it.id }.eachCount().filterValues { it != 1 }.keys
		if (duplicates.isNotEmpty()) return InferenceReadyStatus(false, "Duplicate evidence: ${duplicates.sorted().joinToString()}", report)
		val byId = report.evidence.associateBy { it.id }
		val missing = REQUIRED_INFERENCE_READY_EVIDENCE - byId.keys
		if (missing.isNotEmpty()) return InferenceReadyStatus(false, "Missing evidence: ${missing.sorted().joinToString()}", report)
		val failed = REQUIRED_INFERENCE_READY_EVIDENCE.filter { byId[it]?.passed != true }
		if (failed.isNotEmpty()) return InferenceReadyStatus(false, "Failed evidence: ${failed.sorted().joinToString()}", report)
		if (!report.ready) return InferenceReadyStatus(false, "Report was generated as not ready", report)
		return InferenceReadyStatus(true, "Inference-ready gate passed for ${report.modelSha256}", report)
	}

	internal fun sha256(path: Path): String {
		val digest = MessageDigest.getInstance("SHA-256")
		Files.newInputStream(path).use { input ->
			val buffer = ByteArray(64 * 1024)
			while (true) {
				val read = input.read(buffer)
				if (read < 0) break
				digest.update(buffer, 0, read)
			}
		}
		return digest.digest().joinToString("") { "%02x".format(it) }
	}
}

data class ActiveCorrectionAuthorization(
	val allowed: Boolean,
	val rejectionReason: String?,
) {
	companion object {
		fun evaluate(featureFlagEnabled: Boolean, status: InferenceReadyStatus, activeModelSha256: String): ActiveCorrectionAuthorization = when {
			!featureFlagEnabled -> ActiveCorrectionAuthorization(false, "ACTIVE_CORRECTION_OPT_IN_DISABLED")
			!status.ready -> ActiveCorrectionAuthorization(false, "INFERENCE_READY_GATE_PENDING")
			status.report?.modelSha256 != activeModelSha256 -> ActiveCorrectionAuthorization(false, "INFERENCE_READY_MODEL_MISMATCH")
			else -> ActiveCorrectionAuthorization(true, null)
		}
	}
}
