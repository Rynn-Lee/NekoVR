package dev.slimevr.dataset

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class TrackerMetadata(
	val id: Int,
	val name: String,
	val bodyPosition: String,
	val imuType: String,
	val firmwareVersion: String = "1.0",
	val manufacturer: String = "NekoVR",
)

@Serializable
data class ResetEventMetadata(
	val timestampMs: Long,
	val resetType: String, // "YAW_RESET", "FULL_RESET", "MOUNTING_RESET"
	val trackerId: Int,
	val yawDeltaDegrees: Float,
)

@Serializable
data class DatasetManifest(
	val version: String = "1.0.0",
	val recordedAtIso: String,
	val durationSeconds: Float,
	val sampleRateHz: Int = 50,
	val frameCount: Long,
	val compression: String = "Zstandard (FP16)",
	val hmdGroundTruthAvailable: Boolean = true,
	val trackers: List<TrackerMetadata>,
	val resetEvents: List<ResetEventMetadata>,
	val systemInfo: String = "NekoVR Game Session",
) {
	fun toJsonString(): String {
		val json = Json { prettyPrint = true }
		return json.encodeToString(this)
	}
}
