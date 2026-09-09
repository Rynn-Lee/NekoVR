package dev.slimevr.dataset

import kotlinx.serialization.Serializable

const val DATASET_SCHEMA_MAJOR: Int = 1
const val DATASET_SCHEMA_MINOR: Int = 2
const val CANONICAL_SAMPLE_RATE_HZ: Int = 50
const val CANONICAL_SAMPLE_INTERVAL_NS: Long = 20_000_000L

@Serializable
enum class ChannelValidity { UNAVAILABLE, INVALID, STALE, VALID }

@Serializable
enum class ChannelProvenance {
	UNAVAILABLE,
	MEASURED,
	FIRMWARE_REPORTED,
	SERVER_DERIVED,
	MODEL_DERIVED,
	USER_ANNOTATED,
	LEGACY_ESTIMATE,
	RESET_DERIVED,
}

@Serializable
enum class CollectionProfile { MINIMUM, STANDARD, FULL_FIDELITY }

@Serializable
enum class ActivityType { UNKNOWN, STANDING, SEATED, LYING, CROUCHING, TRANSITION, DANCE, LOCOMOTION, STATIONARY }

@Serializable
data class TelemetryChannel(
	val id: Int,
	val name: String,
	val unit: String,
	val coordinateFrame: String,
	val cadence: String,
	val precision: String,
	val minimumProfile: CollectionProfile,
	val allowedProvenance: Set<ChannelProvenance>,
)

data class DatasetFileHeader(
	val schemaMajor: Int,
	val schemaMinor: Int,
	val sessionId: String,
	val createdUtc: String,
	val applicationVersion: String,
	val applicationCommit: String,
	val profile: CollectionProfile,
	val canonicalRateHz: Int,
	val channels: List<TelemetryChannel>,
)

data class DatasetTrackerRoster(
	val revision: Int,
	val trackers: List<SessionTrackerMetadata>,
)

data class DatasetFooter(
	val endedMonotonicNs: Long,
	val durationNs: Long,
	val counters: DatasetQualityCounters,
	val telemetrySha256: String,
	val complete: Boolean,
)

object TelemetryChannelRegistry {
	private val measured = setOf(ChannelProvenance.MEASURED, ChannelProvenance.FIRMWARE_REPORTED)
	private val measuredOrDerived = measured + ChannelProvenance.SERVER_DERIVED

	val channels: List<TelemetryChannel> = listOf(
		TelemetryChannel(1, "raw_orientation", "quaternion", "sensor_to_world", "50 Hz", "fp16", CollectionProfile.MINIMUM, measured),
		TelemetryChannel(2, "calibrated_pre_ai_orientation", "quaternion", "body_to_world", "50 Hz", "fp16", CollectionProfile.MINIMUM, measuredOrDerived),
		TelemetryChannel(3, "final_orientation", "quaternion", "body_to_world", "50 Hz", "fp16", CollectionProfile.MINIMUM, measuredOrDerived + ChannelProvenance.MODEL_DERIVED),
		TelemetryChannel(4, "raw_acceleration", "m/s^2", "sensor", "native/change", "fp16", CollectionProfile.MINIMUM, measured),
		TelemetryChannel(5, "linear_acceleration", "m/s^2", "world", "50 Hz", "fp16", CollectionProfile.MINIMUM, measuredOrDerived),
		TelemetryChannel(6, "angular_velocity", "rad/s", "sensor", "native/50 Hz", "fp16", CollectionProfile.STANDARD, measuredOrDerived),
		TelemetryChannel(7, "magnetic_vector", "uT", "sensor", "native/change", "fp16", CollectionProfile.STANDARD, measured),
		TelemetryChannel(8, "temperature", "degC", "sensor", "native/change", "fp16", CollectionProfile.STANDARD, measured),
		TelemetryChannel(9, "packet_sequence", "count", "device", "native", "uint64", CollectionProfile.STANDARD, measured),
		TelemetryChannel(10, "packet_loss", "ratio", "transport", "change", "fp32", CollectionProfile.STANDARD, measuredOrDerived),
		TelemetryChannel(11, "rssi", "dBm", "transport", "change", "int32", CollectionProfile.STANDARD, measured),
		TelemetryChannel(12, "ping", "ms", "transport", "change", "int32", CollectionProfile.STANDARD, measuredOrDerived),
		TelemetryChannel(13, "battery", "percent", "device", "change", "fp32", CollectionProfile.STANDARD, measured),
		TelemetryChannel(14, "charging_state", "enum", "device", "change", "int32", CollectionProfile.FULL_FIDELITY, measured),
		TelemetryChannel(15, "device_timestamp", "ticks", "device", "native", "uint64", CollectionProfile.FULL_FIDELITY, measured),
		TelemetryChannel(16, "gyro_raw", "rad/s", "sensor", "native", "fp16", CollectionProfile.FULL_FIDELITY, measured),
		TelemetryChannel(17, "model_prediction", "quaternion", "body_to_world", "50 Hz", "fp32", CollectionProfile.STANDARD, setOf(ChannelProvenance.MODEL_DERIVED)),
		TelemetryChannel(18, "applied_correction", "quaternion", "body_to_world", "50 Hz", "fp32", CollectionProfile.STANDARD, setOf(ChannelProvenance.MODEL_DERIVED, ChannelProvenance.UNAVAILABLE)),
		TelemetryChannel(19, "sample_age", "ns", "server", "50 Hz", "uint64", CollectionProfile.MINIMUM, setOf(ChannelProvenance.SERVER_DERIVED)),
		TelemetryChannel(20, "firmware_features", "bitset", "device", "change", "uint64", CollectionProfile.STANDARD, measured),
		TelemetryChannel(21, "magnetometer_state", "enum", "sensor", "change", "string", CollectionProfile.STANDARD, measured),
		TelemetryChannel(22, "calibration_quality", "ratio", "sensor", "native/change", "fp32", CollectionProfile.STANDARD, measured),
		TelemetryChannel(23, "fusion_state", "enum", "sensor", "change", "string", CollectionProfile.STANDARD, measured),
		TelemetryChannel(24, "packets_received", "count", "transport", "change", "uint64", CollectionProfile.STANDARD, measuredOrDerived),
		TelemetryChannel(25, "packets_lost", "count", "transport", "change", "uint64", CollectionProfile.STANDARD, measuredOrDerived),
		TelemetryChannel(26, "packet_gaps", "count", "transport", "change", "uint64", CollectionProfile.STANDARD, measuredOrDerived),
		TelemetryChannel(27, "packets_reordered", "count", "transport", "change", "uint64", CollectionProfile.FULL_FIDELITY, measuredOrDerived),
		TelemetryChannel(28, "packets_duplicate", "count", "transport", "change", "uint64", CollectionProfile.FULL_FIDELITY, measuredOrDerived),
		TelemetryChannel(29, "packets_corrupt", "count", "transport", "change", "uint64", CollectionProfile.FULL_FIDELITY, measuredOrDerived),
		TelemetryChannel(30, "battery_voltage", "V", "device", "change", "fp32", CollectionProfile.STANDARD, measured),
		TelemetryChannel(31, "power_mode", "enum", "device", "change", "string", CollectionProfile.FULL_FIDELITY, measured),
		TelemetryChannel(32, "device_uptime", "ms", "device", "native/change", "uint64", CollectionProfile.FULL_FIDELITY, measured),
		TelemetryChannel(33, "reset_reason", "enum", "device", "change", "string", CollectionProfile.FULL_FIDELITY, measured),
		TelemetryChannel(34, "observed_sample_rate", "Hz", "server", "change", "fp32", CollectionProfile.STANDARD, setOf(ChannelProvenance.SERVER_DERIVED)),
		TelemetryChannel(35, "inter_arrival_jitter", "ns", "server", "native/change", "uint64", CollectionProfile.FULL_FIDELITY, setOf(ChannelProvenance.SERVER_DERIVED)),
		TelemetryChannel(36, "controller_pose", "pose", "world", "50 Hz", "fp32", CollectionProfile.FULL_FIDELITY, measuredOrDerived),
		TelemetryChannel(37, "skeleton_pose", "pose", "world", "50 Hz", "fp32", CollectionProfile.STANDARD, setOf(ChannelProvenance.SERVER_DERIVED)),
		TelemetryChannel(38, "floor_height", "m", "world", "change", "fp32", CollectionProfile.STANDARD, setOf(ChannelProvenance.SERVER_DERIVED)),
		TelemetryChannel(39, "activity", "enum/confidence", "world", "interval", "fp32", CollectionProfile.STANDARD, setOf(ChannelProvenance.SERVER_DERIVED, ChannelProvenance.USER_ANNOTATED)),
		TelemetryChannel(40, "model_gating", "enum", "model", "50 Hz", "string", CollectionProfile.STANDARD, setOf(ChannelProvenance.MODEL_DERIVED, ChannelProvenance.UNAVAILABLE)),
		TelemetryChannel(41, "model_latency", "us", "server", "50 Hz", "uint64", CollectionProfile.STANDARD, setOf(ChannelProvenance.MODEL_DERIVED, ChannelProvenance.UNAVAILABLE)),
		TelemetryChannel(42, "model_slot", "index", "model", "change", "int32", CollectionProfile.STANDARD, setOf(ChannelProvenance.MODEL_DERIVED, ChannelProvenance.UNAVAILABLE)),
		TelemetryChannel(43, "history_validity", "boolean", "model", "50 Hz", "bool", CollectionProfile.STANDARD, setOf(ChannelProvenance.MODEL_DERIVED, ChannelProvenance.UNAVAILABLE)),
		TelemetryChannel(44, "body_role", "enum", "body", "event", "string", CollectionProfile.MINIMUM, setOf(ChannelProvenance.USER_ANNOTATED, ChannelProvenance.SERVER_DERIVED)),
		TelemetryChannel(45, "tracker_status", "enum", "server", "50 Hz", "string", CollectionProfile.MINIMUM, setOf(ChannelProvenance.SERVER_DERIVED)),
	)

	val byId: Map<Int, TelemetryChannel> = channels.associateBy { it.id }

	fun requiredFor(profile: CollectionProfile): Set<Int> = channels
		.filter { it.minimumProfile.ordinal <= profile.ordinal }
		.mapTo(linkedSetOf()) { it.id }
}

data class Vector3Sample(val x: Float, val y: Float, val z: Float)
data class QuaternionSample(val x: Float, val y: Float, val z: Float, val w: Float)

data class NativeChannelSample(
	val channelId: Int,
	val monotonicNs: Long,
	val values: List<Float> = emptyList(),
	val integerValue: Long? = null,
	val textValue: String? = null,
	val validity: ChannelValidity,
	val provenance: ChannelProvenance,
)

data class CorrectionTelemetry(
	val prediction: QuaternionSample = QuaternionSample(0f, 0f, 0f, 1f),
	val appliedCorrection: QuaternionSample = QuaternionSample(0f, 0f, 0f, 1f),
	val applied: Boolean = false,
	val rejectionReason: String? = "UNAVAILABLE",
	val modelHash: String? = null,
	val provider: String? = null,
	val slot: Int = -1,
	val historyValid: Boolean = false,
	val latencyMicros: Long? = null,
	val provenance: ChannelProvenance = ChannelProvenance.UNAVAILABLE,
)

data class TrackerFrameSample(
	val sessionTrackerId: String,
	val rawOrientation: QuaternionSample,
	val calibratedPreAiOrientation: QuaternionSample,
	val finalOrientation: QuaternionSample,
	val rawAcceleration: Vector3Sample,
	val linearAcceleration: Vector3Sample,
	val angularVelocity: Vector3Sample,
	val magneticVector: Vector3Sample,
	val orientationValidity: ChannelValidity,
	val accelerationValidity: ChannelValidity,
	val angularVelocityValidity: ChannelValidity,
	val angularVelocityProvenance: ChannelProvenance,
	val driftProvenance: ChannelProvenance,
	val trackerStatus: String,
	val sampleSequence: Long,
	val sampleAgeNs: Long,
	val correction: CorrectionTelemetry,
	val nativeChannels: List<NativeChannelSample> = emptyList(),
)

data class ReferenceFrameSample(
	val orientation: QuaternionSample,
	val position: Vector3Sample,
	val validity: ChannelValidity,
	val sampleAgeNs: Long,
)

data class ActivitySample(
	val type: ActivityType,
	val confidence: Float,
	val startFrame: Long,
	val endFrame: Long,
	val provenance: ChannelProvenance = ChannelProvenance.SERVER_DERIVED,
)

data class SessionFrame(
	val frameIndex: Long,
	val monotonicNs: Long,
	val deltaNs: Long,
	val hmd: ReferenceFrameSample,
	val trackers: List<TrackerFrameSample>,
	val contextSamples: List<TrackerFrameSample> = emptyList(),
	val activity: ActivitySample = ActivitySample(ActivityType.UNKNOWN, 0f, frameIndex, frameIndex),
)

@Serializable
data class SessionTrackerMetadata(
	val sessionTrackerId: String,
	val deviceLocalTrackerNumber: Int,
	val bodyRole: String,
	val imuType: String,
	val transport: String,
	val boardType: String,
	val mcuType: String,
	val firmwareVersion: String,
	val manufacturer: String,
	val capabilities: Set<Int>,
	val initialCalibration: String,
	val pseudonymousDeviceId: String? = null,
)

data class DatasetEvent(
	val type: String,
	val monotonicNs: Long,
	val frameIndex: Long,
	val sessionTrackerId: String? = null,
	val oldValue: String? = null,
	val newValue: String? = null,
	val detail: String? = null,
	val requestId: String? = null,
	val requestMonotonicNs: Long = 0L,
	val appliedMonotonicNs: Long = 0L,
	val eventIndex: Long = 0L,
	val resetOutcome: String? = null,
	val resetSource: String? = null,
	val resetKind: String? = null,
	val affectedBodyParts: List<String> = emptyList(),
)

data class DatasetResetLabel(
	val eventIndex: Long,
	val sessionTrackerId: String,
	val correction: QuaternionSample,
	val diagnosticYawRadians: Float,
	val axisMask: Byte,
	val preStartFrame: Long,
	val preEndFrame: Long,
	val postStartFrame: Long,
	val postEndFrame: Long,
	val qualityFlags: Int,
	val domain: String = "YAW",
	val requestId: String? = null,
	val requestMonotonicNs: Long = 0L,
	val appliedMonotonicNs: Long = 0L,
	val rawOrientationBefore: QuaternionSample = QuaternionSample(0f, 0f, 0f, 1f),
	val rawOrientationAfter: QuaternionSample = QuaternionSample(0f, 0f, 0f, 1f),
	val calibratedPreAiBefore: QuaternionSample = QuaternionSample(0f, 0f, 0f, 1f),
	val calibratedPreAiAfter: QuaternionSample = QuaternionSample(0f, 0f, 0f, 1f),
	val attachmentRotBefore: QuaternionSample = QuaternionSample(0f, 0f, 0f, 1f),
	val attachmentRotAfter: QuaternionSample = QuaternionSample(0f, 0f, 0f, 1f),
	val mountingRotBefore: QuaternionSample = QuaternionSample(0f, 0f, 0f, 1f),
	val mountingRotAfter: QuaternionSample = QuaternionSample(0f, 0f, 0f, 1f),
	val yawRotBefore: QuaternionSample = QuaternionSample(0f, 0f, 0f, 1f),
	val yawRotAfter: QuaternionSample = QuaternionSample(0f, 0f, 0f, 1f),
	val hmdReferenceBefore: QuaternionSample = QuaternionSample(0f, 0f, 0f, 1f),
	val hmdReferenceAfter: QuaternionSample = QuaternionSample(0f, 0f, 0f, 1f),
	val hmdValid: Boolean = true,
	val resetEpoch: Long = 0L,
	val trainingPolicy: String = "INCLUDE",
	val gyroFixBefore: QuaternionSample = QuaternionSample(0f, 0f, 0f, 1f),
	val gyroFixAfter: QuaternionSample = QuaternionSample(0f, 0f, 0f, 1f),
	val mountRotFixBefore: QuaternionSample = QuaternionSample(0f, 0f, 0f, 1f),
	val mountRotFixAfter: QuaternionSample = QuaternionSample(0f, 0f, 0f, 1f),
	val tposeDownFixBefore: QuaternionSample = QuaternionSample(0f, 0f, 0f, 1f),
	val tposeDownFixAfter: QuaternionSample = QuaternionSample(0f, 0f, 0f, 1f),
	val constraintFixBefore: QuaternionSample = QuaternionSample(0f, 0f, 0f, 1f),
	val constraintFixAfter: QuaternionSample = QuaternionSample(0f, 0f, 0f, 1f),
	val calibrationEpoch: Long = 0L,
	val bodyRole: String = "UNASSIGNED",
	val hmdSampleAgeBeforeNs: Long = 0L,
	val hmdSampleAgeAfterNs: Long = 0L,
)
