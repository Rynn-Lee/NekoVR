package dev.slimevr.dataset

import dev.slimevr.reset.DefaultResetEventPublisher
import dev.slimevr.reset.ResetEvent
import dev.slimevr.reset.ResetKind
import dev.slimevr.reset.ResetLabelCalculator
import dev.slimevr.reset.ResetOutcome
import dev.slimevr.reset.TrackerAdjustmentSnapshot
import dev.slimevr.reset.TrackerResetStateSnapshot
import dev.slimevr.tracking.trackers.Device
import dev.slimevr.tracking.trackers.DeviceOrigin
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import dev.slimevr.tracking.trackers.udp.BoardType
import dev.slimevr.tracking.trackers.udp.IMUType
import dev.slimevr.tracking.trackers.udp.MCUType
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories

/** Generates deterministic short simulated pilots; physical pilots must still be supplied separately. */
fun main(arguments: Array<String>) {
	val args = arguments.toList()
	val outputIndex = args.indexOf("--output")
	require(outputIndex >= 0 && outputIndex + 1 < args.size) { "Usage: --output DIRECTORY" }
	val output = Path.of(args[outputIndex + 1]).toAbsolutePath().normalize().apply { createDirectories() }
	generatePilot(output, "simulated-5-udp.nvrdata", 5, false)
	generatePilot(output, "simulated-8-mixed.nvrdata", 8, true)
}

private fun generatePilot(output: Path, name: String, trackerCount: Int, mixedTransport: Boolean) {
	var clockNs = 1_000_000_000L
	val hmd = Tracker(
		device = null,
		id = 0,
		name = "Simulated HMD",
		trackerPosition = TrackerPosition.HEAD,
		trackerNum = 0,
		hasPosition = true,
		hasRotation = true,
		hasAcceleration = false,
		isComputed = true,
		trackRotDirection = false,
		isHmd = true,
	).apply {
		status = TrackerStatus.OK
		position = Vector3(0f, 1.7f, 0f)
		setRotation(Quaternion.IDENTITY)
	}
	val roles = listOf(
		TrackerPosition.WAIST,
		TrackerPosition.CHEST,
		TrackerPosition.LEFT_LOWER_LEG,
		TrackerPosition.RIGHT_LOWER_LEG,
		TrackerPosition.LEFT_FOOT,
		TrackerPosition.RIGHT_FOOT,
		TrackerPosition.LEFT_UPPER_ARM,
		TrackerPosition.RIGHT_UPPER_ARM,
	)
	val imus = roles.take(trackerCount).mapIndexed { index, role ->
		val origin = if (mixedTransport && index % 2 == 1) DeviceOrigin.HID else DeviceOrigin.UDP
		val device = object : Device(origin) {
			override val boardType: BoardType = BoardType.SLIMEVR
			override val mcuType: MCUType = if (origin == DeviceOrigin.HID) MCUType.NRF52 else MCUType.ESP32
			override var firmwareVersion: String? = "dataset-pilot-1"
			override var manufacturer: String? = "NekoVR simulator"
			override val hardwareIdentifier: String = "simulated-$trackerCount-$index"
		}
		Tracker(
			device = device,
			id = index + 1,
			name = "Simulated ${role.designation}",
			trackerPosition = role,
			trackerNum = index,
			hasPosition = false,
			hasRotation = true,
			hasAcceleration = true,
			isComputed = false,
			trackRotDirection = false,
			imuType = if (origin == DeviceOrigin.HID) IMUType.BNO085 else IMUType.BMI160,
		).apply {
			status = TrackerStatus.OK
			setRotation(Quaternion.IDENTITY)
			setAcceleration(Vector3(0f, 9.81f, 0f))
		}
	}
	val trackers = listOf(hmd) + imus
	val publisher = DefaultResetEventPublisher()
	val recorder = DatasetRecordingService(
		datasetsRoot = output,
		clockNs = { clockNs },
		batchSize = 5,
		resetPreContextFrames = 2,
		resetPostContextFrames = 2,
	)
	recorder.bindResetPublisher(publisher)
	recorder.startRecording(
		RecordingRequest(
			profile = CollectionProfile.STANDARD,
			privacy = SessionPrivacyOptions(consent = true, subjectPseudonym = "simulated-pilot"),
			applicationVersion = "dataset-ready-pilot-v1",
			applicationCommit = "SIMULATED",
			minFreeSpaceBytes = 0,
		),
		trackers,
	)
	repeat(5) {
		recorder.sampleIfDue(trackers)
		clockNs += CANONICAL_SAMPLE_INTERVAL_NS
	}
	val adjustment = TrackerAdjustmentSnapshot(
		Quaternion.IDENTITY,
		Quaternion.IDENTITY,
		Quaternion.IDENTITY,
		Quaternion.IDENTITY,
		Quaternion.IDENTITY,
		Quaternion.IDENTITY,
		Quaternion.IDENTITY,
	)
	fun snapshot(tracker: Tracker, position: TrackerPosition) = TrackerResetStateSnapshot(
		trackerId = tracker.id,
		trackerPosition = position,
		rawOrientation = Quaternion.IDENTITY,
		calibratedPreAiOrientation = Quaternion.IDENTITY,
		adjustedOrientation = Quaternion.IDENTITY,
		adjustments = adjustment,
		acceleration = Vector3(0f, 0f, 0f),
		angularVelocity = Vector3(0f, 0f, 0f),
		status = TrackerStatus.OK,
		resetEpoch = 1,
		calibrationEpoch = 0,
		sampleAgeNs = 0,
		packetGapCount = 0,
	)
	val imuState = snapshot(imus.first(), roles.first())
	val hmdState = snapshot(hmd, TrackerPosition.HEAD)
	val requestNs = clockNs
	publisher.publish(
		ResetEvent(
			requestId = "dataset-ready-simulated-reset",
			kind = ResetKind.YAW,
			outcome = ResetOutcome.REQUESTED,
			source = "dataset-ready-simulated-pilot",
			requestMonotonicNs = requestNs,
			bodyParts = listOf(roles.first().bodyPart),
		),
	)
	val label = ResetLabelCalculator.buildLabelRecord(
		trackerId = imus.first().id,
		trackerPosition = roles.first(),
		kind = ResetKind.YAW,
		preState = imuState,
		postState = imuState.copy(resetEpoch = 2),
		hmdPre = hmdState,
		hmdPost = hmdState,
		requestMonotonicNs = requestNs,
		applyMonotonicNs = requestNs,
		requestId = "dataset-ready-simulated-reset",
	)
	publisher.publish(
		ResetEvent(
			requestId = "dataset-ready-simulated-reset",
			kind = ResetKind.YAW,
			outcome = ResetOutcome.APPLIED,
			source = "dataset-ready-simulated-pilot",
			requestMonotonicNs = requestNs,
			appliedMonotonicNs = requestNs,
			bodyParts = listOf(roles.first().bodyPart),
			labels = listOf(label),
		),
	)
	repeat(5) {
		recorder.sampleIfDue(trackers)
		clockNs += CANONICAL_SAMPLE_INTERVAL_NS
	}
	val generated = recorder.stopAndFinalize(10)
	val target = output.resolve(name)
	Files.move(generated, target, StandardCopyOption.REPLACE_EXISTING)
	check(DatasetArchiveValidator().validate(target).valid) { "Generated pilot failed validation: $target" }
}
