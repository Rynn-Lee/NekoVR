package dev.slimevr.dataset

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

const val DATASET_READY_REPORT_SCHEMA: Int = 1
const val DATASET_READY_REPORT_FILE: String = "dataset-ready-report.json"

val REQUIRED_DATASET_READY_EVIDENCE: Set<String> = linkedSetOf(
	"schema",
	"numerical",
	"metadata",
	"reset-label",
	"crash-recovery",
	"memory-soak",
	"rpc-ui",
	"baseline",
)

@Serializable
enum class PilotSource { SIMULATED, REAL }

@Serializable
data class DatasetReadyEvidence(
	val id: String,
	val passed: Boolean,
	val detail: String = "",
)

@Serializable
data class DatasetReadyPilotEvidence(
	val source: PilotSource,
	val archive: String,
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
	val schemaVersion: Int = DATASET_READY_REPORT_SCHEMA,
	val generatedUtc: String,
	val buildCommit: String,
	val ready: Boolean,
	val evidence: List<DatasetReadyEvidence>,
	val pilots: List<DatasetReadyPilotEvidence>,
)

data class DatasetReadyStatus(
	val ready: Boolean,
	val detail: String,
	val report: DatasetReadyReport? = null,
)

object DatasetReadyReportStore {
	private val json = Json { ignoreUnknownKeys = true }

	fun resolve(datasetsRoot: Path): Path {
		val explicit = System.getProperty("nekovr.datasetReadyReport")
			?.takeIf(String::isNotBlank)
			?: System.getenv("NEKOVR_DATASET_READY_REPORT")?.takeIf(String::isNotBlank)
		return explicit?.let(Path::of) ?: datasetsRoot.resolve(DATASET_READY_REPORT_FILE)
	}

	fun load(path: Path): DatasetReadyStatus {
		if (!path.exists()) return DatasetReadyStatus(false, "Dataset-ready report is missing: $path")
		return runCatching {
			val report = Files.newBufferedReader(path).use { json.decodeFromString<DatasetReadyReport>(it.readText()) }
			evaluate(report)
		}.getOrElse { DatasetReadyStatus(false, "Dataset-ready report is invalid: ${it.message}") }
	}

	fun evaluate(report: DatasetReadyReport): DatasetReadyStatus {
		if (report.schemaVersion != DATASET_READY_REPORT_SCHEMA) {
			return DatasetReadyStatus(false, "Unsupported dataset-ready report schema ${report.schemaVersion}", report)
		}
		val evidenceById = report.evidence.associateBy { it.id }
		val missing = REQUIRED_DATASET_READY_EVIDENCE - evidenceById.keys
		if (missing.isNotEmpty()) return DatasetReadyStatus(false, "Missing evidence: ${missing.sorted().joinToString()}", report)
		val failed = REQUIRED_DATASET_READY_EVIDENCE.filter { evidenceById[it]?.passed != true }
		if (failed.isNotEmpty()) return DatasetReadyStatus(false, "Failed evidence: ${failed.sorted().joinToString()}", report)
		for (source in PilotSource.entries) {
			if (report.pilots.none { it.source == source }) return DatasetReadyStatus(false, "Missing ${source.name.lowercase()} pilot evidence", report)
		}
		val failedPilots = report.pilots.filter {
			!it.serverValid || !it.pythonValid || !it.statisticsMatch || !it.provenanceValid || it.fatalFindings.isNotEmpty()
		}
		if (failedPilots.isNotEmpty()) return DatasetReadyStatus(false, "One or more pilot archives failed validation", report)
		if (report.pilots.map { it.rosterSize }.distinct().size < 2) {
			return DatasetReadyStatus(false, "Pilot evidence does not cover multiple tracker layouts", report)
		}
		if (report.pilots.flatMapTo(linkedSetOf()) { it.transports }.size < 2) {
			return DatasetReadyStatus(false, "Pilot evidence does not cover multiple transports", report)
		}
		if (!report.ready) return DatasetReadyStatus(false, "Report was generated as not ready", report)
		return DatasetReadyStatus(true, "Dataset-ready gate passed for ${report.buildCommit}", report)
	}
}
