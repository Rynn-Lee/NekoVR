package dev.slimevr.unit

import dev.slimevr.dataset.ArchiveState
import dev.slimevr.dataset.CollectionProfile
import dev.slimevr.dataset.DatasetArchiveValidator
import dev.slimevr.dataset.DatasetManifest
import dev.slimevr.dataset.DatasetPrivacy
import dev.slimevr.dataset.DatasetRecordingService
import dev.slimevr.dataset.RecordingRequest
import dev.slimevr.dataset.RecordingState
import dev.slimevr.dataset.SessionPrivacyOptions
import dev.slimevr.dataset.TelemetryChannelRegistry
import dev.slimevr.tracking.trackers.Device
import dev.slimevr.tracking.trackers.DeviceOrigin
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import dev.slimevr.tracking.trackers.hid.HIDTelemetryCapabilities
import dev.slimevr.tracking.trackers.hid.negotiateHIDTelemetry
import dev.slimevr.tracking.trackers.udp.BoardType
import dev.slimevr.tracking.trackers.udp.IMUType
import dev.slimevr.tracking.trackers.udp.MCUType
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.math.cos
import kotlin.math.sin

class DatasetRecordingServiceTests {

	private fun yawQuat(rad: Float): Quaternion = Quaternion(cos(rad / 2f), 0f, sin(rad / 2f), 0f)

	private fun createHeadTracker(): Tracker = Tracker(
		device = null,
		id = 0,
		name = "Headset",
		trackerPosition = TrackerPosition.HEAD,
		trackerNum = 0,
		hasPosition = true,
		hasRotation = true,
		hasAcceleration = false,
		isComputed = true,
		trackRotDirection = false,
	).apply {
		status = TrackerStatus.OK
		position = Vector3(0f, 1.7f, 0f)
		setRotation(Quaternion.IDENTITY)
	}

	private fun createWaistTracker(id: Int = 1): Tracker = Tracker(
		device = null,
		id = id,
		name = "WaistTracker",
		trackerPosition = TrackerPosition.WAIST,
		trackerNum = 0,
		hasPosition = false,
		hasRotation = true,
		hasAcceleration = true,
		isComputed = false,
		trackRotDirection = false,
		imuType = IMUType.BNO085,
	).apply {
		status = TrackerStatus.OK
		setRotation(Quaternion.IDENTITY)
		setAcceleration(Vector3(0f, 9.81f, 0f))
	}

	@Test
	fun testShortRecordingAndValidation(@TempDir tempDir: Path) {
		val hmd = createHeadTracker()
		val waist = createWaistTracker()
		val trackers = listOf(hmd, waist)

		var clockNs = 1_000_000_000L
		val service = DatasetRecordingService(
			datasetsRoot = tempDir,
			clockNs = { clockNs },
			queueCapacity = 256,
			batchSize = 10,
		)

		val request = RecordingRequest(
			profile = CollectionProfile.MINIMUM,
			privacy = SessionPrivacyOptions(consent = true),
			applicationVersion = "0.4.0-test",
		)

		val status = service.startRecording(request, trackers)
		assertEquals(RecordingState.RECORDING, status.state)
		assertTrue(service.isRecording)

		// Sample 50 frames (1 second at 50 Hz, 20ms steps)
		for (i in 0 until 50) {
			waist.setRotation(yawQuat(i * 0.01f))
			service.sampleIfDue(trackers)
			clockNs += 20_000_000L
		}

		val archive = service.stopAndFinalize(timeoutSeconds = 5)
		assertTrue(archive.exists())
		assertEquals(RecordingState.COMPLETED, service.status().state)

		val validator = DatasetArchiveValidator()
		val report = validator.validate(archive)
		assertTrue(report.valid, "Archive report must be valid: ${report.findings}")
		assertEquals(50L, report.frames)
		assertEquals(1, report.schemaMajor)
		assertEquals(1, report.rosterSize)
		assertEquals(TelemetryChannelRegistry.channels.mapTo(linkedSetOf()) { it.id }, report.channelIds)
		assertEquals(50L, report.quality?.writtenFrames)
		assertEquals(setOf("UNKNOWN"), report.transports)
		assertTrue(report.findings.none { it.severity.name == "FATAL" })
	}

	@Test
	fun testLongSoakRecordingMemoryAndWatermark(@TempDir tempDir: Path) {
		val hmd = createHeadTracker()
		val waist = createWaistTracker()
		val trackers = listOf(hmd, waist)

		var clockNs = 2_000_000_000L
		val service = DatasetRecordingService(
			datasetsRoot = tempDir,
			clockNs = { clockNs },
			queueCapacity = 1024,
			batchSize = 25,
			durableEveryBatches = 2,
		)

		service.startRecording(
			RecordingRequest(
				profile = CollectionProfile.MINIMUM,
				privacy = SessionPrivacyOptions(consent = true),
			),
			trackers,
		)

		// 500 frames at 50 Hz
		for (i in 0 until 500) {
			service.sampleIfDue(trackers)
			clockNs += 20_000_000L
		}

		val statusBeforeStop = service.status()
		assertEquals(500L, statusBeforeStop.sampledFrames)
		assertEquals(0L, statusBeforeStop.droppedFrames)
		assertTrue(statusBeforeStop.queueHighWatermark in 1..1024)

		val archive = service.stopAndFinalize(timeoutSeconds = 10)
		val report = DatasetArchiveValidator().validate(archive)
		assertTrue(report.valid)
		assertEquals(500L, report.frames)
	}

	@Test
	fun testQueueOverflowNonBlockingDropAccounting(@TempDir tempDir: Path) {
		val hmd = createHeadTracker()
		val waist = createWaistTracker()
		val trackers = listOf(hmd, waist)

		var clockNs = 3_000_000_000L
		// Queue capacity is small and writer has delay to force queue overflow
		val service = DatasetRecordingService(
			datasetsRoot = tempDir,
			clockNs = { clockNs },
			queueCapacity = 4,
			batchSize = 20,
			writerDelayMillis = 20,
		)

		service.startRecording(
			RecordingRequest(
				profile = CollectionProfile.MINIMUM,
				privacy = SessionPrivacyOptions(consent = true),
			),
			trackers,
		)

		// Rapidly sample 100 frames on the tracking thread without sleeping
		val startTime = System.currentTimeMillis()
		for (i in 0 until 100) {
			service.sampleIfDue(trackers)
			clockNs += 20_000_000L
		}
		val durationMs = System.currentTimeMillis() - startTime

		// Must be non-blocking on the tracking thread: 100 samples shouldn't wait 100 * 20ms
		assertTrue(durationMs < 500, "Tracking thread sampling must not block: took ${durationMs}ms")

		val status = service.status()
		assertTrue(status.droppedFrames > 0, "Dropped frames must be accounted when queue overflows")

		val archive = service.stopAndFinalize(timeoutSeconds = 10)
		val report = DatasetArchiveValidator().validate(archive)
		assertTrue(report.valid, "Archive remains valid despite drops: ${report.findings}")
		assertTrue(report.frames > 0)
	}

	@Test
	fun testDiskFullThresholdEnforcement(@TempDir tempDir: Path) {
		val hmd = createHeadTracker()
		val waist = createWaistTracker()
		val trackers = listOf(hmd, waist)

		val simulatedFreeSpace = AtomicLong(50_000_000L) // 50 MB
		var clockNs = 4_000_000_000L
		val service = DatasetRecordingService(
			datasetsRoot = tempDir,
			clockNs = { clockNs },
			freeSpace = { simulatedFreeSpace.get() },
			batchSize = 5,
		)

		// 1. Rejects start if free space is below threshold
		assertThrows(IllegalArgumentException::class.java) {
			service.startRecording(
				RecordingRequest(
					privacy = SessionPrivacyOptions(consent = true),
					minFreeSpaceBytes = 100_000_000L, // Requires 100 MB, but only 50 MB available
				),
				trackers,
			)
		}

		// 2. Fails during recording when disk fills up
		simulatedFreeSpace.set(500_000_000L) // 500 MB
		service.startRecording(
			RecordingRequest(
				privacy = SessionPrivacyOptions(consent = true),
				minFreeSpaceBytes = 100_000_000L,
			),
			trackers,
		)
		assertTrue(service.isRecording)

		// Suddenly disk drops below limit
		simulatedFreeSpace.set(50_000_000L)
		// Sample enough frames to trigger a batch write (batchSize = 5)
		for (i in 0 until 10) {
			service.sampleIfDue(trackers)
			clockNs += 20_000_000L
		}

		// Give writer thread a moment to process the batch and hit the check
		Thread.sleep(150)

		assertThrows(Exception::class.java) {
			service.stopAndFinalize(timeoutSeconds = 2)
		}
		assertEquals(RecordingState.FAILED, service.status().state)
		assertTrue(service.status().lastError?.contains("Disk free-space") == true)
	}

	@Test
	fun testForcedCrashAndStartupRecovery(@TempDir tempDir: Path) {
		// Simulate an interrupted session left on disk from a crashed process
		val sessionId = "interrupted-crash-session-123"
		val partialDir = tempDir.resolve("$sessionId.partial")
		partialDir.createDirectories()

		val partialManifest = DatasetManifest(
			sessionId = sessionId,
			createdUtc = Instant.now().toString(),
			monotonicStartNs = 1_000_000_000L,
			profile = CollectionProfile.MINIMUM,
			applicationVersion = "0.4.0-test",
			applicationCommit = "test-commit",
			privacy = DatasetPrivacy(consent = true),
			trackers = emptyList(),
			state = ArchiveState.RECORDING,
		)
		partialDir.resolve(DatasetRecordingService.MANIFEST_PARTIAL).writeText(partialManifest.toJsonString())
		partialDir.resolve(DatasetRecordingService.TELEMETRY_PARTIAL).writeBytes(ByteArray(1024))

		// Now simulate server startup: discover recoverable sessions
		val newService = DatasetRecordingService(datasetsRoot = tempDir)
		val recoverable = newService.discoverRecoverableSessions()
		assertEquals(1, recoverable.size)
		val session = recoverable.first()
		assertEquals(sessionId, session.sessionId)
		assertEquals("interrupted recording has durable telemetry", session.reason)
		assertNotNull(session.manifest)
		assertEquals(sessionId, session.manifest?.sessionId)
		assertTrue(newService.recoverabilityFindings(session).any { it.severity.name == "FATAL" })
		assertThrows(IllegalArgumentException::class.java) { newService.recoverPartial(session) }

		// Test quarantine
		val quarantinedPath = newService.quarantinePartial(session)
		assertTrue(quarantinedPath.exists())
		assertTrue(quarantinedPath.toString().endsWith(".quarantine"))
		assertFalse(partialDir.exists())
		assertTrue(newService.discoverRecoverableSessions().isEmpty())
	}

	@Test
	fun testRecoveryPreservesDurableManifestAndProducesValidArchive(@TempDir tempDir: Path) {
		val trackers = listOf(createHeadTracker(), createWaistTracker())
		var clockNs = 9_000_000_000L
		val sourceService = DatasetRecordingService(
			datasetsRoot = tempDir,
			clockNs = { clockNs },
			batchSize = 2,
		)
		sourceService.startRecording(
			RecordingRequest(
				profile = CollectionProfile.MINIMUM,
				privacy = SessionPrivacyOptions(consent = true, subjectPseudonym = "recovery-subject"),
			),
			trackers,
		)
		repeat(6) {
			sourceService.sampleIfDue(trackers)
			clockNs += 20_000_000L
		}
		val sourceArchive = sourceService.stopAndFinalize(5)

		lateinit var durableManifest: DatasetManifest
		ZipFile(sourceArchive.toFile()).use { zip ->
			val manifestText = zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() }
			durableManifest = DatasetManifest.fromJsonString(manifestText)
			val partialDir = tempDir.resolve("${durableManifest.sessionId}.partial")
			partialDir.createDirectories()
			partialDir.resolve(DatasetRecordingService.MANIFEST_PARTIAL).writeText(manifestText)
			zip.getInputStream(zip.getEntry("telemetry.fbs.zst")).use { input ->
				Files.copy(input, partialDir.resolve(DatasetRecordingService.TELEMETRY_PARTIAL))
			}
		}
		Files.delete(sourceArchive)

		val recoveryService = DatasetRecordingService(datasetsRoot = tempDir)
		val session = recoveryService.discoverRecoverableSessions().single()
		assertTrue(recoveryService.recoverabilityFindings(session).none { it.severity.name == "FATAL" })

		val recoveredArchive = recoveryService.recoverPartial(session)
		val report = DatasetArchiveValidator().validate(recoveredArchive)
		assertTrue(report.valid, "Recovered archive must validate: ${report.findings}")
		assertEquals(6L, report.frames)
		assertEquals(RecordingState.COMPLETED, recoveryService.status().state)
		ZipFile(recoveredArchive.toFile()).use { zip ->
			val recoveredManifest = DatasetManifest.fromJsonString(
				zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() },
			)
			assertTrue(recoveredManifest.recovered)
			assertEquals(ArchiveState.RECOVERED, recoveredManifest.state)
			assertEquals("recovery-subject", recoveredManifest.privacy.subjectPseudonym)
		}
	}

	@Test
	fun testMixedWiFiAndHIDnRFOriginTrackers(@TempDir tempDir: Path) {
		val hmd = createHeadTracker()

		// Wi-Fi tracker (UDP)
		val wifiDevice = object : Device(DeviceOrigin.UDP) {
			override val boardType: BoardType = BoardType.SLIMEVR
			override val mcuType: MCUType = MCUType.ESP8266
			override var firmwareVersion: String? = "0.4.1-wifi"
			override var manufacturer: String? = "SlimeVR"
			override val hardwareIdentifier: String = "ESP8266_MAC_AA:BB:CC"
		}
		val wifiTracker = Tracker(
			device = wifiDevice,
			id = 1,
			name = "ChestTracker",
			trackerPosition = TrackerPosition.CHEST,
			trackerNum = 0,
			hasPosition = false,
			hasRotation = true,
			hasAcceleration = true,
			isComputed = false,
			trackRotDirection = false,
			imuType = IMUType.BMI160,
		).apply {
			status = TrackerStatus.OK
			setRotation(Quaternion.IDENTITY)
			setAcceleration(Vector3(0f, 9.81f, 0f))
		}

		// HID / nRF tracker
		val hidDevice = object : Device(DeviceOrigin.HID) {
			override val boardType: BoardType = BoardType.SLIMEVR
			override val mcuType: MCUType = MCUType.NRF52
			override var firmwareVersion: String? = "0.4.1-hid"
			override var manufacturer: String? = "SlimeVR-Dongle"
			override val hardwareIdentifier: String = "NRF_SERIAL_123456"
		}
		val hidTracker = Tracker(
			device = hidDevice,
			id = 2,
			name = "LeftLegTracker",
			trackerPosition = TrackerPosition.LEFT_LOWER_LEG,
			trackerNum = 0,
			hasPosition = false,
			hasRotation = true,
			hasAcceleration = true,
			isComputed = false,
			trackRotDirection = false,
			imuType = IMUType.BNO085,
		).apply {
			status = TrackerStatus.OK
			setRotation(Quaternion.IDENTITY)
			setAcceleration(Vector3(0f, 9.81f, 0f))
			negotiateHIDTelemetry(HIDTelemetryCapabilities(setOf(9, 15, 16)))
		}

		val trackers = listOf(hmd, wifiTracker, hidTracker)
		var clockNs = 6_000_000_000L
		val service = DatasetRecordingService(
			datasetsRoot = tempDir,
			clockNs = { clockNs },
			batchSize = 5,
		)

		service.startRecording(
			RecordingRequest(
				profile = CollectionProfile.MINIMUM,
				privacy = SessionPrivacyOptions(
					consent = true,
					subjectPseudonym = "Tester01",
					hashHardwareIdentifiers = true,
				),
			),
			trackers,
		)

		for (i in 0 until 10) {
			service.sampleIfDue(trackers)
			clockNs += 20_000_000L
		}

		val archive = service.stopAndFinalize(timeoutSeconds = 5)
		val report = DatasetArchiveValidator().validate(archive)
		assertTrue(report.valid, "Archive must be valid: ${report.findings}")

		// Validate manifest metadata
		java.util.zip.ZipFile(archive.toFile()).use { zip ->
			val manifestEntry = zip.getEntry("manifest.json")
			assertNotNull(manifestEntry)
			val manifestJson = zip.getInputStream(manifestEntry).reader().readText()
			val manifest = DatasetManifest.fromJsonString(manifestJson)

			assertEquals(ArchiveState.COMPLETE, manifest.state)
			assertEquals("Tester01", manifest.privacy.subjectPseudonym)
			assertEquals("HMAC_SHA256_PER_SESSION", manifest.privacy.identifierPolicy)
			assertNotNull(manifest.privacy.perSessionSaltBase64)

			val wifiMetadata = manifest.trackers.find { it.bodyRole == TrackerPosition.CHEST.designation }
			assertNotNull(wifiMetadata)
			assertEquals("BMI160", wifiMetadata?.imuType)
			assertEquals("UDP", wifiMetadata?.transport)
			assertEquals("ESP8266", wifiMetadata?.mcuType)
			assertNotNull(wifiMetadata?.pseudonymousDeviceId)
			assertFalse(wifiMetadata?.pseudonymousDeviceId?.contains("AA:BB:CC") == true, "Raw MAC must not be stored")

			val hidMetadata = manifest.trackers.find { it.bodyRole == TrackerPosition.LEFT_LOWER_LEG.designation }
			assertNotNull(hidMetadata)
			assertEquals("BNO085", hidMetadata?.imuType)
			assertEquals("HID", hidMetadata?.transport)
			assertEquals("NRF52", hidMetadata?.mcuType)
			assertTrue(hidMetadata?.capabilities?.containsAll(listOf(9, 15, 16)) == true)
			assertNotNull(hidMetadata?.pseudonymousDeviceId)
			assertFalse(hidMetadata?.pseudonymousDeviceId?.contains("123456") == true, "Raw serial must not be stored")
		}
	}
}
