package dev.slimevr.dataset

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.Properties
import kotlin.io.path.exists

const val DATASET_READY_REPORT_SCHEMA: Int = 2
const val DATASET_READY_REPORT_FILE: String = "dataset-ready-report.json"
const val DATASET_READY_REPORT_FORMAT: String = "nekovr-dataset-ready-report-v2"
const val DATASET_READY_VERIFIER_ID: String = "nekovr-dataset-ready"
const val DATASET_READY_VERIFIER_VERSION: String = "2"

val REQUIRED_DATASET_READY_EVIDENCE: Set<String> = linkedSetOf(
	"schema", "numerical", "metadata", "reset-label", "crash-recovery", "memory-soak", "rpc-ui", "baseline",
)

@Serializable
enum class PilotSource { SIMULATED, REAL }

@Serializable
data class DatasetReadyVerifier(val id: String, val version: String)

@Serializable
data class DatasetReadyBuild(val commit: String, val dirty: Boolean, val dirtyTreePolicy: String)

@Serializable
data class DatasetReadyEvidence(
	val id: String,
	val passed: Boolean,
	val detail: String = "",
	val commands: List<String>,
	val commandSha256: List<String>,
	val resultArtifact: String,
	val resultSha256: String,
)

@Serializable
data class DatasetReadyPilotEvidence(
	val source: PilotSource,
	val archive: String,
	val archiveSha256: String,
	val serverResultArtifact: String,
	val serverResultSha256: String,
	val pythonResultArtifact: String,
	val pythonResultSha256: String,
	val serverValid: Boolean,
	val pythonValid: Boolean,
	val statisticsMatch: Boolean,
	val provenanceValid: Boolean,
	val fatalFindings: List<String> = emptyList(),
	val frames: Long = 0,
	val rosterSize: Int = 0,
	val channelIds: Set<Int> = emptySet(),
	val validResetWindows: Int = 0,
	val transports: Set<String> = emptySet(),
)

@Serializable
data class DatasetReadyReport(
	val format: String = DATASET_READY_REPORT_FORMAT,
	val schemaVersion: Int = DATASET_READY_REPORT_SCHEMA,
	val verifier: DatasetReadyVerifier,
	val reportPath: String,
	val generatedUtc: String,
	val expiresUtc: String,
	val build: DatasetReadyBuild,
	val ready: Boolean,
	val blockingFindings: List<String> = emptyList(),
	val evidence: List<DatasetReadyEvidence>,
	val pilots: List<DatasetReadyPilotEvidence>,
)

data class DatasetReadyRuntimeIdentity(
	val commit: String,
	val dirty: Boolean,
	val allowDirtyDevelopment: Boolean = false,
	val now: Instant = Instant.now(),
) {
	companion object {
		fun current(): DatasetReadyRuntimeIdentity {
			val embedded = Properties()
			DatasetReadyRuntimeIdentity::class.java.getResourceAsStream("build-identity.properties")?.use(embedded::load)
			return DatasetReadyRuntimeIdentity(
				commit = embedded.getProperty("commit")?.takeIf(String::isNotBlank)
					?: System.getProperty("nekovr.commit")?.takeIf(String::isNotBlank)
					?: System.getenv("NEKOVR_COMMIT")?.takeIf(String::isNotBlank) ?: "UNKNOWN",
				dirty = embedded.getProperty("dirty")?.toBooleanStrictOrNull()
					?: System.getProperty("nekovr.dirty")?.toBooleanStrictOrNull()
					?: System.getenv("NEKOVR_DIRTY")?.toBooleanStrictOrNull() ?: true,
				allowDirtyDevelopment = System.getProperty("nekovr.allowDirtyDatasetReady") == "true",
			)
		}
	}
}

data class DatasetReadyStatus(val ready: Boolean, val detail: String, val report: DatasetReadyReport? = null)

object DatasetReadyReportStore {
	private val json = Json { ignoreUnknownKeys = true }
	private val commitPattern = Regex("^[0-9a-f]{40,64}$")
	private val hashPattern = Regex("^[0-9a-f]{64}$")

	fun resolve(datasetsRoot: Path): Path {
		val explicit = System.getProperty("nekovr.datasetReadyReport")?.takeIf(String::isNotBlank)
			?: System.getenv("NEKOVR_DATASET_READY_REPORT")?.takeIf(String::isNotBlank)
		return explicit?.let(Path::of) ?: datasetsRoot.resolve(DATASET_READY_REPORT_FILE)
	}

	fun load(path: Path, runtime: DatasetReadyRuntimeIdentity = DatasetReadyRuntimeIdentity.current()): DatasetReadyStatus {
		if (!path.exists()) return DatasetReadyStatus(false, "Dataset-ready report is missing: $path")
		return runCatching {
			val report = Files.newBufferedReader(path).use { json.decodeFromString<DatasetReadyReport>(it.readText()) }
			evaluate(report, path, runtime)
		}.getOrElse { DatasetReadyStatus(false, "Dataset-ready report is invalid: ${it.message}") }
	}

	fun evaluate(report: DatasetReadyReport, reportSource: Path, runtime: DatasetReadyRuntimeIdentity): DatasetReadyStatus {
		fun reject(detail: String) = DatasetReadyStatus(false, detail, report)
		if (report.schemaVersion != DATASET_READY_REPORT_SCHEMA || report.format != DATASET_READY_REPORT_FORMAT) return reject("Unsupported dataset-ready report format/schema")
		if (report.verifier.id != DATASET_READY_VERIFIER_ID || report.verifier.version != DATASET_READY_VERIFIER_VERSION) return reject("Unknown dataset-ready verifier ${report.verifier.id}/${report.verifier.version}")
		val actualReportPath = reportSource.toRealPath()
		val declaredReportPath = Path.of(report.reportPath).toAbsolutePath().normalize()
		if (actualReportPath != declaredReportPath) return reject("Dataset-ready report was copied or moved from ${report.reportPath}")

		val generated = runCatching { Instant.parse(report.generatedUtc) }.getOrNull() ?: return reject("Dataset-ready generation time is invalid")
		val expires = runCatching { Instant.parse(report.expiresUtc) }.getOrNull() ?: return reject("Dataset-ready expiry time is invalid")
		if (!expires.isAfter(generated)) return reject("Dataset-ready expiry does not follow generation time")
		if (generated.isAfter(runtime.now.plusSeconds(300))) return reject("Dataset-ready report generation time is in the future")
		if (!expires.isAfter(runtime.now)) return reject("Dataset-ready report expired at ${report.expiresUtc}")

		if (!commitPattern.matches(report.build.commit) || runtime.commit == "UNKNOWN") return reject("Dataset-ready build identity is unknown or invalid")
		if (report.build.commit != runtime.commit) return reject("Dataset-ready report belongs to ${report.build.commit}, running build is ${runtime.commit}")
		when (report.build.dirtyTreePolicy) {
			"REQUIRE_CLEAN" -> if (report.build.dirty || runtime.dirty) return reject("Dataset-ready report requires a clean build")
			"ALLOW_DEVELOPMENT" -> if (!runtime.allowDirtyDevelopment) return reject("Dirty development dataset-ready reports are disallowed")
			else -> return reject("Unknown dirty-tree policy ${report.build.dirtyTreePolicy}")
		}
		if (report.build.dirty != runtime.dirty) return reject("Dataset-ready dirty-tree state does not match the running build")

		val duplicates = report.evidence.groupingBy { it.id }.eachCount().filterValues { it != 1 }.keys
		if (duplicates.isNotEmpty()) return reject("Duplicate evidence: ${duplicates.sorted().joinToString()}")
		val evidenceById = report.evidence.associateBy { it.id }
		val missing = REQUIRED_DATASET_READY_EVIDENCE - evidenceById.keys
		if (missing.isNotEmpty()) return reject("Missing evidence: ${missing.sorted().joinToString()}")
		val unexpected = evidenceById.keys - REQUIRED_DATASET_READY_EVIDENCE
		if (unexpected.isNotEmpty()) return reject("Unexpected evidence: ${unexpected.sorted().joinToString()}")
		for (evidence in report.evidence) {
			if (!evidence.passed) return reject("Failed evidence: ${evidence.id}")
			if (evidence.commands.isEmpty() || evidence.commands.size != evidence.commandSha256.size) return reject("Evidence ${evidence.id} has incomplete command identity")
			for ((command, expectedHash) in evidence.commands.zip(evidence.commandSha256)) {
				if (!hashPattern.matches(expectedHash) || sha256(command.toByteArray(StandardCharsets.UTF_8)) != expectedHash) return reject("Evidence ${evidence.id} command hash changed")
			}
			verifyArtifact(evidence.resultArtifact, evidence.resultSha256)?.let { return reject("Evidence ${evidence.id}: $it") }
		}

		for (source in PilotSource.entries) if (report.pilots.none { it.source == source }) return reject("Missing ${source.name.lowercase()} pilot evidence")
		val duplicatePilots = report.pilots.groupingBy { Path.of(it.archive).toAbsolutePath().normalize() }.eachCount().filterValues { it != 1 }.keys
		if (duplicatePilots.isNotEmpty()) return reject("Duplicate pilot archive evidence")
		for (pilot in report.pilots) {
			verifyArtifact(pilot.archive, pilot.archiveSha256)?.let { return reject("Pilot archive: $it") }
			verifyArtifact(pilot.serverResultArtifact, pilot.serverResultSha256)?.let { return reject("Server pilot result: $it") }
			verifyArtifact(pilot.pythonResultArtifact, pilot.pythonResultSha256)?.let { return reject("Python pilot result: $it") }
			if (!pilot.serverValid || !pilot.pythonValid || !pilot.statisticsMatch || !pilot.provenanceValid || pilot.fatalFindings.isNotEmpty()) return reject("One or more pilot archives failed validation")
		}
		if (report.pilots.map { it.rosterSize }.distinct().size < 2) return reject("Pilot evidence does not cover multiple tracker layouts")
		if (report.pilots.flatMapTo(linkedSetOf()) { it.transports }.size < 2) return reject("Pilot evidence does not cover multiple transports")
		if (!report.ready) {
			val finding = report.blockingFindings.firstOrNull()?.let { ": $it" } ?: ""
			return reject("Report was generated as not ready$finding")
		}
		return DatasetReadyStatus(true, "Dataset-ready gate passed for ${report.build.commit}", report)
	}

	private fun verifyArtifact(pathValue: String, expectedHash: String): String? {
		if (!hashPattern.matches(expectedHash)) return "artifact SHA-256 is missing or invalid"
		if (pathValue.isBlank()) return "artifact path is missing"
		val path = runCatching { Path.of(pathValue) }.getOrNull() ?: return "artifact path is invalid"
		if (!Files.isRegularFile(path)) return "artifact is missing: $path"
		return if (sha256(path) == expectedHash) null else "artifact bytes changed: $path"
	}

	internal fun sha256(path: Path): String = Files.newInputStream(path).use { input ->
		val digest = MessageDigest.getInstance("SHA-256")
		val buffer = ByteArray(64 * 1024)
		while (true) {
			val read = input.read(buffer)
			if (read < 0) break
			digest.update(buffer, 0, read)
		}
		digest.digest().joinToString("") { "%02x".format(it) }
	}

	private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
