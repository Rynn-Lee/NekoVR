package dev.slimevr.dataset

import com.github.luben.zstd.ZstdOutputStream
import dev.slimevr.dataset.generated.DatasetV1Bindings
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Reproducible, cross-language v1 fixture. Values are intentionally non-trivial. */
object DatasetConformanceFixture {
	const val FILE_NAME = "kotlin-conformance-v1.nvrdata"
	const val SESSION_ID = "kotlin-conformance-v1"
	const val START_NS = 1_000_000_000L
	private val sin45 = 0.70710677f

	val preAdjusted = QuaternionSample(sin45, 0f, 0f, sin45)
	val postAdjusted = QuaternionSample(0f, sin45, 0f, sin45)
	val canonicalCorrection = QuaternionSample(-0.5f, 0.5f, 0.5f, 0.5f)

	val roster = listOf(
		SessionTrackerMetadata(
			"tracker-1", 7, "WAIST", "BMI270", "UDP", "SLIMEVR", "ESP32",
			"0.6.1", "NekoVR", setOf(1, 2, 3, 4, 6, 8, 9, 21), "CALIBRATED", "device-a",
		),
		SessionTrackerMetadata(
			"controller-1", 1, "LEFT_CONTROLLER", "NONE", "HID", "GENERIC", "NRF52",
			"1.2.3", "NekoVR", setOf(36), "NOT_APPLICABLE", null,
		),
		SessionTrackerMetadata(
			"hmd-1", 0, "HEAD", "NONE", "OPENVR", "HMD", "HOST",
			"runtime", "NekoVR", setOf(36), "NOT_APPLICABLE", "hmd-a",
		),
	)

	private fun tracker() = TrackerFrameSample(
		sessionTrackerId = "tracker-1",
		rawOrientation = QuaternionSample(0f, 0f, 0.5f, 0.8660254f),
		calibratedPreAiOrientation = preAdjusted,
		finalOrientation = postAdjusted,
		rawAcceleration = Vector3Sample(1.25f, -2.5f, 9.8125f),
		linearAcceleration = Vector3Sample(0.125f, -0.25f, 0.5f),
		angularVelocity = Vector3Sample(0.75f, -1.5f, 2.25f),
		magneticVector = Vector3Sample(12.5f, -24.25f, 48.75f),
		orientationValidity = ChannelValidity.VALID,
		accelerationValidity = ChannelValidity.STALE,
		angularVelocityValidity = ChannelValidity.INVALID,
		angularVelocityProvenance = ChannelProvenance.FIRMWARE_REPORTED,
		driftProvenance = ChannelProvenance.LEGACY_ESTIMATE,
		trackerStatus = "OK",
		sampleSequence = 42,
		sampleAgeNs = 3_000_000,
		correction = CorrectionTelemetry(
			prediction = postAdjusted,
			appliedCorrection = canonicalCorrection,
			applied = true,
			rejectionReason = null,
			modelHash = "model-sha256",
			provider = "CPU",
			slot = 2,
			historyValid = true,
			latencyMicros = 321,
			provenance = ChannelProvenance.MODEL_DERIVED,
		),
		nativeChannels = listOf(
			NativeChannelSample(8, START_NS, values = listOf(36.625f), validity = ChannelValidity.VALID, provenance = ChannelProvenance.MEASURED),
			NativeChannelSample(9, START_NS, integerValue = 42, validity = ChannelValidity.VALID, provenance = ChannelProvenance.FIRMWARE_REPORTED),
			NativeChannelSample(21, START_NS, textValue = "CALIBRATED", validity = ChannelValidity.STALE, provenance = ChannelProvenance.FIRMWARE_REPORTED),
		),
	)

	private fun context() = TrackerFrameSample(
		sessionTrackerId = "controller-1",
		rawOrientation = QuaternionSample(0f, 0f, 0f, 1f),
		calibratedPreAiOrientation = QuaternionSample(0f, 0f, 0f, 1f),
		finalOrientation = QuaternionSample(0f, 0f, 0f, 1f),
		rawAcceleration = Vector3Sample(0f, 0f, 0f),
		linearAcceleration = Vector3Sample(0f, 0f, 0f),
		angularVelocity = Vector3Sample(0f, 0f, 0f),
		magneticVector = Vector3Sample(0f, 0f, 0f),
		orientationValidity = ChannelValidity.UNAVAILABLE,
		accelerationValidity = ChannelValidity.UNAVAILABLE,
		angularVelocityValidity = ChannelValidity.UNAVAILABLE,
		angularVelocityProvenance = ChannelProvenance.UNAVAILABLE,
		driftProvenance = ChannelProvenance.UNAVAILABLE,
		trackerStatus = "STALE",
		sampleSequence = 5,
		sampleAgeNs = 9_000_000,
		correction = CorrectionTelemetry(),
	)

	fun records(): List<ByteArray> {
		val frame = SessionFrame(
			frameIndex = 12,
			monotonicNs = START_NS,
			deltaNs = CANONICAL_SAMPLE_INTERVAL_NS,
			hmd = ReferenceFrameSample(
				QuaternionSample(0f, 0.25881904f, 0f, 0.9659258f),
				Vector3Sample(0.125f, 1.75f, -0.5f),
				ChannelValidity.VALID,
				2_000_000,
			),
			trackers = listOf(tracker()),
			contextSamples = listOf(context()),
			activity = ActivitySample(ActivityType.DANCE, 0.875f, 10, 14, ChannelProvenance.USER_ANNOTATED),
		)
		val event = DatasetEvent(
			type = "RESET", monotonicNs = START_NS + 5_000_000, frameIndex = 12,
			sessionTrackerId = "tracker-1", oldValue = "REQUESTED", newValue = "APPLIED",
			detail = "conformance reset", requestId = "reset-1", requestMonotonicNs = START_NS + 1_000_000,
			appliedMonotonicNs = START_NS + 5_000_000, eventIndex = 9, resetOutcome = "APPLIED",
			resetSource = "USER", resetKind = "FULL", affectedBodyParts = listOf("WAIST", "LEGS"),
		)
		val label = DatasetResetLabel(
			eventIndex = 9, sessionTrackerId = "tracker-1", correction = canonicalCorrection,
			diagnosticYawRadians = 1.0471976f, axisMask = 7, preStartFrame = 10, preEndFrame = 11,
			postStartFrame = 12, postEndFrame = 14, qualityFlags = 5, domain = "FULL", requestId = "reset-1",
			requestMonotonicNs = START_NS + 1_000_000, appliedMonotonicNs = START_NS + 5_000_000,
			rawOrientationBefore = preAdjusted, rawOrientationAfter = postAdjusted,
			calibratedPreAiBefore = preAdjusted, calibratedPreAiAfter = postAdjusted,
			attachmentRotBefore = QuaternionSample(0f, 0f, 0f, 1f), attachmentRotAfter = preAdjusted,
			mountingRotBefore = preAdjusted, mountingRotAfter = postAdjusted,
			yawRotBefore = QuaternionSample(0f, 0f, 0f, 1f), yawRotAfter = canonicalCorrection,
			hmdReferenceBefore = QuaternionSample(0f, 0f, 0f, 1f), hmdReferenceAfter = postAdjusted,
			hmdValid = true, resetEpoch = 4, trainingPolicy = "REVIEW",
			gyroFixBefore = preAdjusted, gyroFixAfter = postAdjusted,
			mountRotFixBefore = postAdjusted, mountRotFixAfter = preAdjusted,
			tposeDownFixBefore = QuaternionSample(0f, 0f, 0f, 1f), tposeDownFixAfter = canonicalCorrection,
			constraintFixBefore = canonicalCorrection, constraintFixAfter = QuaternionSample(0f, 0f, 0f, 1f),
			calibrationEpoch = 6, bodyRole = "WAIST", hmdSampleAgeBeforeNs = 2_000_000, hmdSampleAgeAfterNs = 3_000_000,
		)
		val counters = DatasetQualityCounters(2, 1, 1, 1, 2, 7, 3, 4, 5, 6)
		val prefix = listOf(
			DatasetV1Bindings.header(SESSION_ID, "2026-01-02T03:04:05Z", "conformance-1", "0123456789abcdef", CollectionProfile.FULL_FIDELITY),
			DatasetV1Bindings.roster(roster, revision = 3, sequence = 1),
			DatasetV1Bindings.frames(listOf(frame), sequence = 2),
			DatasetV1Bindings.events(listOf(event), listOf(label), sequence = 3),
		)
		return prefix + DatasetV1Bindings.footer(
			START_NS + CANONICAL_SAMPLE_INTERVAL_NS, CANONICAL_SAMPLE_INTERVAL_NS, counters,
			DatasetTelemetryChecksum.computeHex(prefix), complete = true, sequence = 4,
		)
	}

	fun writeArchive(directory: Path): Path {
		Files.createDirectories(directory)
		val telemetry = ByteArrayOutputStream().also { output ->
			ZstdOutputStream(output).use { it.write(framed(records())) }
		}.toByteArray()
		val manifest = DatasetManifest(
			sessionId = SESSION_ID,
			createdUtc = "2026-01-02T03:04:05Z",
			endedUtc = "2026-01-02T03:04:05.020Z",
			monotonicStartNs = START_NS,
			durationNs = CANONICAL_SAMPLE_INTERVAL_NS,
			profile = CollectionProfile.FULL_FIDELITY,
			applicationVersion = "conformance-1",
			applicationCommit = "0123456789abcdef",
			privacy = DatasetPrivacy(consent = true, subjectPseudonym = "fixture"),
			trackers = roster,
			quality = DatasetQualityCounters(2, 1, 1, 1, 2, 7, 3, 4, 5, 6),
			telemetrySha256 = sha256Hex(telemetry),
			telemetryBytes = telemetry.size.toLong(),
			state = ArchiveState.COMPLETE,
		).toJsonString().toByteArray()
		val target = directory.resolve(FILE_NAME)
		ZipOutputStream(Files.newOutputStream(target)).use { zip ->
			storedEntry(zip, "manifest.json", manifest)
			storedEntry(zip, "telemetry.fbs.zst", telemetry)
		}
		return target
	}

	private fun framed(records: List<ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
		records.forEach { record ->
			output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(record.size).array())
			output.write(record)
		}
	}.toByteArray()

	private fun storedEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
		val crc = CRC32().apply { update(bytes) }
		val entry = ZipEntry(name).apply {
			method = ZipEntry.STORED
			size = bytes.size.toLong()
			compressedSize = size
			this.crc = crc.value
			time = 0L
		}
		zip.putNextEntry(entry)
		zip.write(bytes)
		zip.closeEntry()
	}

	private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
		.digest(bytes).joinToString("") { "%02x".format(it) }
}

fun main(arguments: Array<String>) {
	val outputIndex = arguments.indexOf("--output")
	require(outputIndex >= 0 && outputIndex + 1 < arguments.size) { "Usage: --output DIRECTORY" }
	println(DatasetConformanceFixture.writeArchive(Path.of(arguments[outputIndex + 1]).toAbsolutePath().normalize()))
}
