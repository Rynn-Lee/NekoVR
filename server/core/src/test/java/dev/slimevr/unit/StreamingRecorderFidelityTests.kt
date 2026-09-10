package dev.slimevr.unit

import dev.slimevr.dataset.*
import dev.slimevr.dataset.generated.DatasetV1Bindings
import dev.slimevr.dataset.generated.DatasetV1Reader
import dev.slimevr.tracking.trackers.NativeTelemetryChannels
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import dev.slimevr.tracking.trackers.udp.IMUType
import dev.slimevr.tracking.trackers.udp.UDPPacket29SensorTelemetry
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class StreamingRecorderFidelityTests {
	@Test
	fun `canonical transport mappings cover every packet 29 flag`() {
		val mask = (0..11).fold(0L) { value, bit -> value or (1L shl bit) }
		assertEquals(
			setOf(9, 15, 16, 7, 32, 26, 27, 28, 29, 22, 14, 31, 33, 46, 47),
			NativeTelemetryChannels.forUdpMask(mask),
		)
		assertFalse(8 in NativeTelemetryChannels.forUdpMask(NativeTelemetryChannels.UDP_CALIBRATION_QUALITY))
		assertTrue(22 in NativeTelemetryChannels.forUdpMask(NativeTelemetryChannels.UDP_CALIBRATION_QUALITY))
		assertTrue(47 in NativeTelemetryChannels.forHidPacket(3))
	}

	@Test
	fun `packet 29 preserves presence and parses extended native fields`() {
		val mask = UDPPacket29SensorTelemetry.CALIBRATION_QUALITY or
			UDPPacket29SensorTelemetry.CONFIGURED_SAMPLE_RATE or UDPPacket29SensorTelemetry.SLEEP_STATE
		val bytes = ByteBuffer.allocate(1 + 8 + 4 + 4 + 1)
			.put(3.toByte()).putLong(mask).putFloat(.75f).putFloat(100f).put(2.toByte()).flip()
		val packet = UDPPacket29SensorTelemetry()
		packet.readData(bytes)
		assertEquals(3, packet.sensorId)
		assertEquals(.75f, packet.calibrationQuality)
		assertEquals(100f, packet.configuredSampleRateHz)
		assertEquals(2, packet.sleepState)
		assertNull(packet.sequence)
		assertNull(packet.gyro)
	}

	@Test
	fun `profile requirements distinguish tracker data from shared context`() {
		assertEquals(setOf(1, 2, 3, 19, 44, 45), TelemetryChannelRegistry.requiredPerTracker(CollectionProfile.MINIMUM))
		assertEquals(setOf(36, 37, 38, 39), TelemetryChannelRegistry.requiredContext(CollectionProfile.FULL_FIDELITY))
		assertFalse(36 in TelemetryChannelRegistry.requiredPerTracker(CollectionProfile.FULL_FIDELITY))
		assertTrue(setOf(46, 47).all(TelemetryChannelRegistry.byId::containsKey))
		val physical = metadata("imu", "BMI160", TelemetryChannelRegistry.requiredPerTracker(CollectionProfile.FULL_FIDELITY))
		val hmd = metadata("hmd", "NONE", setOf(37, 38, 39))
		val controller = metadata("controller", "NONE", setOf(36))
		assertDoesNotThrow { TelemetryChannelRegistry.requireSupportedProfile(CollectionProfile.FULL_FIDELITY, listOf(physical, hmd, controller)) }
		assertThrows(IllegalArgumentException::class.java) {
			TelemetryChannelRegistry.requireSupportedProfile(CollectionProfile.FULL_FIDELITY, listOf(physical.copy(capabilities = physical.capabilities - 15), hmd, controller))
		}
	}

	@Test
	fun `roster is frozen across context and rejects late sample identities`() {
		val hmd = tracker(1, TrackerPosition.HEAD, position = true, hmd = true, imu = null)
		val controller = tracker(2, null, position = true, imu = null)
		val imu = tracker(3, TrackerPosition.CHEST, position = false, imu = IMUType.BMI160)
		val registry = SessionTrackerRegistry(listOf(hmd, controller, imu), SessionPrivacyOptions(true))
		assertEquals(3, registry.initialRoster.size)
		val stable = registry.idFor(imu)
		assertEquals(stable, registry.idFor(imu))

		val late = tracker(4, TrackerPosition.WAIST, position = false, imu = IMUType.BMI160)
		val (frame, events) = SessionSnapshotFactory(registry).snapshot(listOf(hmd, controller, imu, late), 0, 1_000_000L, 20_000_000L)
		assertFalse(frame.trackers.any { it.sessionTrackerId == "4" })
		assertTrue(events.any { it.detail == "UNROSTERED_SAMPLE_SOURCE_REJECTED:4" })
		assertEquals(3, registry.initialRoster.size)
	}

	@Test
	fun `rostered identity remains stable through topology changes and replacement`() {
		val original = tracker(9, TrackerPosition.CHEST, position = false, imu = IMUType.BMI160)
		val registry = SessionTrackerRegistry(listOf(original), SessionPrivacyOptions(true))
		val stableId = registry.idFor(original)
		val factory = SessionSnapshotFactory(registry)
		factory.snapshot(listOf(original), 0, 1_000_000L, 20_000_000L)
		original.status = TrackerStatus.OK
		original.trackerPosition = TrackerPosition.WAIST
		original.hasCompletedRestCalibration = true
		original.telemetryCapabilities += 20
		val (_, changed) = factory.snapshot(listOf(original), 1, 21_000_000L, 20_000_000L)
		assertTrue(setOf("CONNECT", "ASSIGNMENT", "CALIBRATION", "CAPABILITY_CHANGE").all { type -> changed.any { it.type == type } })

		original.status = TrackerStatus.DISCONNECTED
		assertTrue(factory.snapshot(listOf(original), 2, 41_000_000L, 20_000_000L).second.any { it.type == "DISCONNECT" })
		val replacement = tracker(9, TrackerPosition.WAIST, position = false, imu = IMUType.BMI160).apply { status = TrackerStatus.OK }
		val replacementFrame = factory.snapshot(listOf(replacement), 3, 61_000_000L, 20_000_000L)
		assertEquals(stableId, replacementFrame.first.trackers.single().sessionTrackerId)
		assertTrue(replacementFrame.second.any { it.type == "CONNECT" })
		assertEquals(1, registry.initialRoster.size)
	}

	@Test
	fun `append only context and native telemetry round trip`() {
		val q = QuaternionSample(0f, 0f, 0f, 1f)
		val v = Vector3Sample(1f, 2f, 3f)
		val sample = TrackerFrameSample(
			"controller", q, q, q, v, v, v, v,
			ChannelValidity.VALID, ChannelValidity.VALID, ChannelValidity.VALID,
			ChannelProvenance.MEASURED, ChannelProvenance.UNAVAILABLE, "OK", 7, 10,
			CorrectionTelemetry(),
			listOf(
				NativeChannelSample(35, 10, integerValue = 123, validity = ChannelValidity.VALID, provenance = ChannelProvenance.SERVER_DERIVED),
				NativeChannelSample(46, 10, values = listOf(50f), validity = ChannelValidity.VALID, provenance = ChannelProvenance.FIRMWARE_REPORTED),
				NativeChannelSample(47, 10, textValue = "AWAKE", validity = ChannelValidity.VALID, provenance = ChannelProvenance.FIRMWARE_REPORTED),
			),
			v, ChannelValidity.VALID, ChannelProvenance.MEASURED,
		)
		val source = SessionFrame(
			4, 10, 20_000_000,
			ReferenceFrameSample(q, v, ChannelValidity.VALID, 1),
			emptyList(), listOf(sample), ActivitySample(ActivityType.CROUCHING, .8f, 3, 4),
			listOf(SkeletonBoneSample("LEFT_HAND", q, v, ChannelValidity.VALID)),
			BodyContextSample(v, 1.7f, .9f, ChannelValidity.VALID),
			FloorContextSample(.02f, .95f, ChannelValidity.VALID),
		)
		val decoded = DatasetV1Reader.read(DatasetV1Bindings.frames(listOf(source), 2)).frames.single()
		assertEquals(v, decoded.contextSamples.single().position)
		assertEquals(ChannelValidity.VALID, decoded.contextSamples.single().positionValidity)
		assertEquals("LEFT_HAND", decoded.skeletonBones.single().bodyRole)
		assertEquals(1.7f, decoded.bodyContext?.height)
		assertEquals(.02f, decoded.floorContext?.height)
		assertEquals(ActivityType.CROUCHING, decoded.activity.type)
		assertEquals(listOf(35, 46, 47), decoded.contextSamples.single().nativeChannels.map { it.channelId })
	}

	@Test
	fun `snapshot captures complete native state without fabricated absent values`() {
		val imu = tracker(12, TrackerPosition.CHEST, position = false, imu = IMUType.BMI160).apply {
			configuredSampleRateHz = 50f
			sleepState = "AWAKE"
			packetsReceived = 100
			packetsLost = 2
			packetGaps = 3
			packetReordered = 4
			packetDuplicates = 5
			packetCorrupt = 6
			calibrationQuality = .8f
			fusionStatus = 2
			charging = true
			powerMode = "NORMAL"
			resetReason = "POWER_ON"
			lastDataMonotonicNs = 1_000_000_000L
		}
		val factory = SessionSnapshotFactory(SessionTrackerRegistry(listOf(imu), SessionPrivacyOptions(true)))
		val first = factory.snapshot(listOf(imu), 0, 1_001_000_000L, 20_000_000L).first.trackers.single()
		assertTrue(setOf(22, 23, 24, 25, 26, 27, 28, 29, 31, 46, 47).all { id -> first.nativeChannels.any { it.channelId == id } })
		assertFalse(first.nativeChannels.any { it.channelId == 9 }, "absent sequence must not become a valid zero")

		imu.lastDataMonotonicNs = 1_021_000_000L
		val second = factory.snapshot(listOf(imu), 1, 1_022_000_000L, 20_000_000L).first.trackers.single()
		val jitter = second.nativeChannels.single { it.channelId == 35 }
		assertEquals(1_000_000L, jitter.integerValue)
		assertEquals(ChannelProvenance.SERVER_DERIVED, jitter.provenance)
	}

	private fun tracker(id: Int, role: TrackerPosition?, position: Boolean, hmd: Boolean = false, imu: IMUType?): Tracker = Tracker(
		device = null,
		id = id,
		name = "tracker-$id",
		trackerPosition = role,
		hasPosition = position,
		hasRotation = true,
		hasAcceleration = imu != null,
		trackRotDirection = false,
		isHmd = hmd,
		imuType = imu,
	).apply {
		setRotation(Quaternion.IDENTITY)
		this.position = Vector3(id.toFloat(), 1f, 0f)
	}

	private fun metadata(id: String, imu: String, capabilities: Set<Int>) = SessionTrackerMetadata(
		id, 0, "UNASSIGNED", imu, "TEST", "TEST", "TEST", "1", "TEST", capabilities, "UNKNOWN",
	)
}
