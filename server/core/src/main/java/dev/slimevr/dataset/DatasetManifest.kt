package dev.slimevr.dataset

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class DatasetPrivacy(
	val consent: Boolean,
	val subjectPseudonym: String? = null,
	val identifierPolicy: String = "OMIT",
	val perSessionSaltBase64: String? = null,
)

@Serializable
data class DatasetQualityCounters(
	val sampledFrames: Long = 0,
	val writtenFrames: Long = 0,
	val droppedFrames: Long = 0,
	val gapEvents: Long = 0,
	val invalidSamples: Long = 0,
	val queueHighWatermark: Int = 0,
	val packetGaps: Long = 0,
	val packetReordered: Long = 0,
	val packetDuplicates: Long = 0,
	val packetCorrupt: Long = 0,
)

@Serializable
enum class ArchiveState { RECORDING, FINALIZING, COMPLETE, RECOVERABLE, RECOVERED, QUARANTINED, FAILED }

@Serializable
data class DatasetManifest(
	val schemaMajor: Int = DATASET_SCHEMA_MAJOR,
	val schemaMinor: Int = DATASET_SCHEMA_MINOR,
	val format: String = "NekoVR FlatBuffers/Zstandard session",
	val sessionId: String,
	val createdUtc: String,
	val endedUtc: String? = null,
	val monotonicStartNs: Long,
	val durationNs: Long = 0,
	val canonicalSampleRateHz: Int = CANONICAL_SAMPLE_RATE_HZ,
	val profile: CollectionProfile,
	val applicationVersion: String,
	val applicationCommit: String,
	val privacy: DatasetPrivacy,
	val trackers: List<SessionTrackerMetadata>,
	val channelRegistryVersion: Int = 1,
	val channelIds: Set<Int> = TelemetryChannelRegistry.channels.mapTo(linkedSetOf()) { it.id },
	val quality: DatasetQualityCounters = DatasetQualityCounters(),
	val telemetrySha256: String? = null,
	val telemetryBytes: Long = 0,
	val state: ArchiveState = ArchiveState.RECORDING,
	val recovered: Boolean = false,
) {
	fun toJsonString(): String = json.encodeToString(this)

	companion object {
		val json = Json {
			prettyPrint = true
			ignoreUnknownKeys = true
			encodeDefaults = true
		}

		fun fromJsonString(value: String): DatasetManifest = json.decodeFromString(value)
	}
}

@Serializable
enum class FindingSeverity { INFO, WARNING, FATAL }

@Serializable
data class ValidationFinding(
	val code: String,
	val severity: FindingSeverity,
	val message: String,
	val missingFields: List<String> = emptyList(),
)

@Serializable
data class ResetWindowSummary(
	val eventIndex: Long,
	val sessionTrackerId: String,
	val preStartFrame: Long,
	val preEndFrame: Long,
	val postStartFrame: Long,
	val postEndFrame: Long,
	val qualityFlags: Int,
	val trainingPolicy: String,
)

@Serializable
data class DatasetValidationReport(
	val archive: String,
	val schemaMajor: Int?,
	val schemaMinor: Int?,
	val frames: Long,
	val resetLabels: Long,
	val findings: List<ValidationFinding>,
	val rosterSize: Int = 0,
	val channelIds: Set<Int> = emptySet(),
	val quality: DatasetQualityCounters? = null,
	val validResetWindows: List<ResetWindowSummary> = emptyList(),
	val transports: Set<String> = emptySet(),
	val valid: Boolean = findings.none { it.severity == FindingSeverity.FATAL },
)
