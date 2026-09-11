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
	val RESET_RELATION_MUTATIONS = listOf(
		"reset-wrong-axis.nvrdata",
		"reset-regressing-epoch.nvrdata",
		"reset-unrostered-label.nvrdata",
		"reset-unordered-window.nvrdata",
		"reset-unresolved-window.nvrdata",
	)
	const val SESSION_ID = "kotlin-conformance-v1"
	const val START_NS = 1_000_000_000L
	private val sin45 = 0.70710677f

	val preAdjusted = QuaternionSample(sin45, 0f, 0f, sin45)
	val postAdjusted = QuaternionSample(0f, sin45, 0f, sin45)
	val canonicalCorrection = QuaternionSample(-0.5f, 0.5f, 0.5f, 0.5f)

	val roster = listOf(
		SessionTrackerMetadata(
			"tracker-1", 7, "WAIST", "BMI270", "UDP", "SLIMEVR", "ESP32",
			"0.6.1", "NekoVR", setOf(1, 2, 3, 4, 5, 6, 15, 16, 19, 44, 45), "CALIBRATED", "device-a",
		),
		SessionTrackerMetadata(
			"controller-1", 1, "LEFT_CONTROLLER", "NONE", "HID", "GENERIC", "NRF52",
			"1.2.3", "NekoVR", setOf(36), "NOT_APPLICABLE", null,
		),
		SessionTrackerMetadata(
			"hmd-1", 0, "HEAD", "NONE", "OPENVR", "HMD", "HOST",
			"runtime", "NekoVR", setOf(37, 38, 39), "NOT_APPLICABLE", "hmd-a",
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
			legacyCorrection = preAdjusted,
			legacyApplied = true,
			legacyProvenance = ChannelProvenance.LEGACY_ESTIMATE,
			inputSchemaSha256 = "feature-schema-sha256",
			modelVersion = "1.2.0",
			bodyRoleId = 2,
			mappingTrackerId = 7,
			confidence = .91f,
			driftRate = .0125f,
			gateOutcome = "APPLIED",
			epoch = 6,
			inferenceSequence = 42,
			finalOutput = postAdjusted,
		),
		nativeChannels = listOf(
			NativeChannelSample(15, START_NS, values = listOf(.875f), validity = ChannelValidity.VALID, provenance = ChannelProvenance.FIRMWARE_REPORTED),
			NativeChannelSample(16, START_NS, values = listOf(30.5f), validity = ChannelValidity.VALID, provenance = ChannelProvenance.FIRMWARE_REPORTED),
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
		position = Vector3Sample(-0.4f, 1.2f, 0.3f),
		positionValidity = ChannelValidity.VALID,
		positionProvenance = ChannelProvenance.MEASURED,
	)

	fun records(
		profile: CollectionProfile = CollectionProfile.FULL_FIDELITY,
		channels: List<TelemetryChannel> = TelemetryChannelRegistry.channels,
		footerComplete: Boolean = true,
		footerDurationNs: Long = CANONICAL_SAMPLE_INTERVAL_NS,
		footerCounters: DatasetQualityCounters = DatasetQualityCounters(2, 1, 1, 1, 2, 7, 3, 4, 5, 6),
		footerChecksumOverride: String? = null,
		rosterOverride: List<SessionTrackerMetadata> = roster,
		frameTransform: (SessionFrame) -> SessionFrame = { it },
		eventsTransform: (List<DatasetEvent>) -> List<DatasetEvent> = { it },
		labelsTransform: (List<DatasetResetLabel>) -> List<DatasetResetLabel> = { it },
	): List<ByteArray> {
		val frame = frameTransform(SessionFrame(
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
			skeletonBones = listOf(SkeletonBoneSample("LEFT_HAND", postAdjusted, Vector3Sample(-0.4f, 1.2f, 0.3f), ChannelValidity.VALID)),
			bodyContext = BodyContextSample(Vector3Sample(0f, 1f, 0f), 1.72f, 0.9f, ChannelValidity.VALID),
			floorContext = FloorContextSample(0.015f, 0.95f, ChannelValidity.VALID),
		))
		val requestedEvent = DatasetEvent(
			type = "RESET_FULL_REQUESTED", monotonicNs = START_NS + 1_000_000, frameIndex = 12,
			sessionTrackerId = "tracker-1", detail = "conformance reset requested", requestId = "reset-1",
			requestMonotonicNs = START_NS + 1_000_000, eventIndex = 8, resetOutcome = "REQUESTED",
			resetSource = "USER", resetKind = "FULL", affectedBodyParts = listOf("WAIST", "LEGS"),
		)
		val appliedEvent = DatasetEvent(
			type = "RESET_FULL_APPLIED", monotonicNs = START_NS + 5_000_000, frameIndex = 12,
			sessionTrackerId = "tracker-1", oldValue = "REQUESTED", newValue = "APPLIED",
			detail = "conformance reset", requestId = "reset-1", requestMonotonicNs = START_NS + 1_000_000,
			appliedMonotonicNs = START_NS + 5_000_000, eventIndex = 9, resetOutcome = "APPLIED",
			resetSource = "USER", resetKind = "FULL", affectedBodyParts = listOf("WAIST", "LEGS"),
		)
		val label = DatasetResetLabel(
			eventIndex = 9, sessionTrackerId = "tracker-1", correction = canonicalCorrection,
			diagnosticYawRadians = 1.0471976f, axisMask = 7, preStartFrame = 10, preEndFrame = 11,
			postStartFrame = 12, postEndFrame = 14, qualityFlags = 133, domain = "FULL", requestId = "reset-1",
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
			adjustedOrientationBefore = preAdjusted, adjustedOrientationAfter = postAdjusted,
			rawValidityBefore = ChannelValidity.VALID, rawValidityAfter = ChannelValidity.VALID,
			calibratedPreAiValidityBefore = ChannelValidity.VALID, calibratedPreAiValidityAfter = ChannelValidity.VALID,
			adjustedValidityBefore = ChannelValidity.VALID, adjustedValidityAfter = ChannelValidity.VALID,
			statusBefore = "OK", statusAfter = "OK", sampleAgeBeforeNs = 2_000_000, sampleAgeAfterNs = 3_000_000,
			resetEpochBefore = 3, calibrationEpochBefore = 5,
		)
		val gapEvent = DatasetEvent("GAP", START_NS + 500_000, 11, detail = "one deterministic missing frame")
		val events = eventsTransform(listOf(gapEvent, requestedEvent, appliedEvent))
		val labels = labelsTransform(listOf(label))
		val prefix = listOf(
			DatasetV1Bindings.header(SESSION_ID, "2026-01-02T03:04:05Z", "conformance-1", "0123456789abcdef", profile, channels),
			DatasetV1Bindings.roster(rosterOverride, revision = 3, sequence = 1),
			DatasetV1Bindings.frames(listOf(frame), sequence = 2),
			DatasetV1Bindings.events(events, labels, sequence = 3),
		)
		return prefix +
			DatasetV1Bindings.footer(
				START_NS + CANONICAL_SAMPLE_INTERVAL_NS,
				footerDurationNs,
				footerCounters,
				footerChecksumOverride ?: DatasetTelemetryChecksum.computeHex(prefix),
				complete = footerComplete,
				sequence = 4,
			)
	}

	fun writeArchive(
		directory: Path,
		fileName: String = FILE_NAME,
		profile: CollectionProfile = CollectionProfile.FULL_FIDELITY,
		channels: List<TelemetryChannel> = TelemetryChannelRegistry.channels,
		footerComplete: Boolean = true,
		footerDurationNs: Long = CANONICAL_SAMPLE_INTERVAL_NS,
		footerCounters: DatasetQualityCounters = DatasetQualityCounters(2, 1, 1, 1, 2, 7, 3, 4, 5, 6),
		footerChecksumOverride: String? = null,
		rosterOverride: List<SessionTrackerMetadata> = roster,
		frameTransform: (SessionFrame) -> SessionFrame = { it },
		eventsTransform: (List<DatasetEvent>) -> List<DatasetEvent> = { it },
		labelsTransform: (List<DatasetResetLabel>) -> List<DatasetResetLabel> = { it },
	): Path {
		Files.createDirectories(directory)
		val records = records(profile, channels, footerComplete, footerDurationNs, footerCounters, footerChecksumOverride, rosterOverride, frameTransform, eventsTransform, labelsTransform)
		val telemetry = ByteArrayOutputStream().also { output ->
			ZstdOutputStream(output).use { it.write(framed(records)) }
		}.toByteArray()
		val manifest = DatasetManifest(
			sessionId = SESSION_ID,
			createdUtc = "2026-01-02T03:04:05Z",
			endedUtc = "2026-01-02T03:04:05.020Z",
			monotonicStartNs = START_NS,
			durationNs = CANONICAL_SAMPLE_INTERVAL_NS,
			profile = profile,
			applicationVersion = "conformance-1",
			applicationCommit = "0123456789abcdef",
			privacy = DatasetPrivacy(consent = true, subjectPseudonym = "fixture"),
			trackers = rosterOverride,
			channelIds = channels.mapTo(linkedSetOf()) { it.id },
			quality = DatasetQualityCounters(2, 1, 1, 1, 2, 7, 3, 4, 5, 6),
			telemetrySha256 = sha256Hex(telemetry),
			telemetryBytes = telemetry.size.toLong(),
			state = ArchiveState.COMPLETE,
		).toJsonString().toByteArray()
		val target = directory.resolve(fileName)
		ZipOutputStream(Files.newOutputStream(target)).use { zip ->
			storedEntry(zip, "manifest.json", manifest)
			storedEntry(zip, "telemetry.fbs.zst", telemetry)
		}
		return target
	}

	fun writeResetRelationMutations(directory: Path): List<Path> = listOf(
		writeArchive(directory, RESET_RELATION_MUTATIONS[0], labelsTransform = { labels -> labels.map { it.copy(axisMask = 1) } }),
		writeArchive(directory, RESET_RELATION_MUTATIONS[1], labelsTransform = { labels -> labels.map { it.copy(resetEpoch = it.resetEpochBefore) } }),
		writeArchive(directory, RESET_RELATION_MUTATIONS[2], labelsTransform = { labels -> labels.map { it.copy(sessionTrackerId = "not-in-roster") } }),
		writeArchive(directory, RESET_RELATION_MUTATIONS[3], labelsTransform = { labels -> labels.map { it.copy(preEndFrame = it.postStartFrame + 1) } }),
		writeArchive(directory, RESET_RELATION_MUTATIONS[4], labelsTransform = { labels -> labels.map { it.copy(qualityFlags = it.qualityFlags and 128.inv()) } }),
	)

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
	val output = Path.of(arguments[outputIndex + 1]).toAbsolutePath().normalize()
	println(DatasetConformanceFixture.writeArchive(output))
	DatasetConformanceFixture.writeResetRelationMutations(output).forEach(::println)
}
