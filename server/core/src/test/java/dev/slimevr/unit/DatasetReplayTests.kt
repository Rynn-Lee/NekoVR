package dev.slimevr.unit

import dev.slimevr.ai.DriftCorrectionResult
import dev.slimevr.ai.DriftCorrectionSource
import dev.slimevr.dataset.CollectionProfile
import dev.slimevr.dataset.DatasetArchiveValidator
import dev.slimevr.dataset.DatasetRecordingService
import dev.slimevr.dataset.DatasetReplay
import dev.slimevr.dataset.QuaternionSample
import dev.slimevr.dataset.RecordingRequest
import dev.slimevr.dataset.SessionPrivacyOptions
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import dev.slimevr.tracking.trackers.udp.IMUType
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class DatasetReplayTests {

	private fun toRad(deg: Float): Float = (deg * Math.PI / 180.0).toFloat()

	private fun yawQuat(deg: Float): Quaternion {
		val rad = toRad(deg)
		return Quaternion(cos(rad / 2f), 0f, sin(rad / 2f), 0f)
	}

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

	private fun createAiCorrectedTracker(
		id: Int,
		position: TrackerPosition,
		correctionYawDeg: Float,
		modelHash: String,
	): Tracker {
		val tracker = Tracker(
			device = null,
			id = id,
			name = position.name,
			trackerPosition = position,
			trackerNum = 0,
			hasPosition = false,
			hasRotation = true,
			hasAcceleration = true,
			isComputed = false,
			trackRotDirection = false,
			allowReset = true,
			imuType = IMUType.BNO085,
		).apply {
			status = TrackerStatus.OK
			setRotation(Quaternion.IDENTITY)
			setAcceleration(Vector3(0f, 9.81f, 0f))
		}

		val correctionQuat = yawQuat(correctionYawDeg)
		tracker.resetsHandler.setDriftCorrectionSource(
			DriftCorrectionSource { _, _, _ ->
				DriftCorrectionResult(
					correction = correctionQuat,
					prediction = correctionQuat,
					applied = true,
					rejectionReason = null,
					modelHash = modelHash,
					provider = "CPU",
					historyValid = true,
					latencyMicros = 1500L,
				)
			},
		)
		return tracker
	}

	private fun recordAiSessionFixture(
		outputDir: Path,
		frameCount: Int = 25,
		yawDeg: Float = 15f,
	): Path {
		val hmd = createHeadTracker()
		val waist = createAiCorrectedTracker(1, TrackerPosition.WAIST, yawDeg, "sha256-production-base-v1")
		val trackers = listOf(hmd, waist)

		var clockNs = 1_000_000_000L
		val service = DatasetRecordingService(
			datasetsRoot = outputDir,
			clockNs = { clockNs },
			batchSize = 10,
		)

		service.startRecording(
			RecordingRequest(
				profile = CollectionProfile.MINIMUM,
				privacy = SessionPrivacyOptions(consent = true),
				applicationVersion = "0.4.0-test",
			),
			trackers,
		)

		for (i in 0 until frameCount) {
			// Apply a slight physical raw rotation each frame
			val raw = yawQuat(i * 1.5f)
			waist.setRotation(raw)
			service.sampleIfDue(trackers)
			clockNs += 20_000_000L
		}

		return service.stopAndFinalize(timeoutSeconds = 5)
	}

	@Test
	fun testOfflineRawStreamReplayAndCandidateEvaluation(@TempDir tempDir: Path) {
		val recordedYawDeg = 15f
		val archivePath = recordAiSessionFixture(tempDir, frameCount = 25, yawDeg = recordedYawDeg)
		assertTrue(Files.exists(archivePath))

		val report = DatasetArchiveValidator().validate(archivePath)
		assertTrue(report.valid, "Fixture archive must be valid: ${report.findings}")
		assertEquals(25L, report.frames)

		// Candidate evaluator: proposes a different correction (e.g. 30 deg yaw) based strictly on raw orientation
		val candidateCorrection = QuaternionSample(0f, 0.2588f, 0f, 0.9659f) // 30 deg around Y

		val counterfactualSamples = DatasetReplay.replay(archivePath) { frame, trackerSample ->
			// Verify candidate evaluation receives raw and frame context, never the recorded output
			assertNotNull(trackerSample.rawOrientation)
			assertTrue(frame.frameIndex >= 0L)
			candidateCorrection
		}

		assertEquals(25, counterfactualSamples.size)

		for (i in 0 until 25) {
			val sample = counterfactualSamples[i]
			assertEquals(i.toLong(), sample.frameIndex)
			assertNotNull(sample.sessionTrackerId)

			// 1. Raw orientation matches the recorded raw stream
			val expectedRawRad = toRad(i * 1.5f)
			val expectedRawY = sin(expectedRawRad / 2f)
			val diffRaw = abs(sample.raw.y - expectedRawY)
			assertTrue(diffRaw < 0.01f, "Frame $i raw.y expected $expectedRawY but was ${sample.raw.y}")

			// 2. Recorded applied correction matches the 15-degree AI correction applied during recording
			val expectedRecordedY = sin(toRad(recordedYawDeg) / 2f) // sin(7.5 deg) ~ 0.1305
			val diffRec = abs(sample.recordedApplied.y - expectedRecordedY)
			assertTrue(
				diffRec < 0.01f,
				"Frame $i recordedApplied.y expected $expectedRecordedY but was ${sample.recordedApplied.y}",
			)

			// 3. Candidate applied correction matches candidate evaluation output
			assertEquals(candidateCorrection.y, sample.candidateApplied.y)
			assertEquals(candidateCorrection.w, sample.candidateApplied.w)
		}
	}

	@Test
	fun testReplayRejectsInvalidArchive(@TempDir tempDir: Path) {
		val invalidFile = tempDir.resolve("corrupt_session.nvrdata")
		Files.writeString(invalidFile, "not a valid zip or nvrdata file")

		assertThrows(IllegalArgumentException::class.java) {
			DatasetReplay.replay(invalidFile) { _, _ -> QuaternionSample(0f, 0f, 0f, 1f) }
		}
	}

	@Test
	fun testMultiTrackerCounterfactualEvaluation(@TempDir tempDir: Path) {
		val hmd = createHeadTracker()
		val waist = createAiCorrectedTracker(1, TrackerPosition.WAIST, 10f, "model-waist-v1")
		val chest = createAiCorrectedTracker(2, TrackerPosition.CHEST, 20f, "model-chest-v1")
		val trackers = listOf(hmd, waist, chest)

		var clockNs = 7_000_000_000L
		val service = DatasetRecordingService(
			datasetsRoot = tempDir,
			clockNs = { clockNs },
			batchSize = 5,
		)
		service.startRecording(
			RecordingRequest(
				profile = CollectionProfile.MINIMUM,
				privacy = SessionPrivacyOptions(consent = true),
			),
			trackers,
		)

		for (i in 0 until 10) {
			waist.setRotation(yawQuat(i * 2f))
			chest.setRotation(yawQuat(i * 1f))
			service.sampleIfDue(trackers)
			clockNs += 20_000_000L
		}

		val archive = service.stopAndFinalize(timeoutSeconds = 5)

		val samples = DatasetReplay.replay(archive) { _, tracker ->
			// Candidate: identity correction
			QuaternionSample(0f, 0f, 0f, 1f)
		}

		// 10 frames * 2 physical IMU trackers = 20 samples
		assertEquals(20, samples.size)
		assertTrue(samples.all { it.candidateApplied == QuaternionSample(0f, 0f, 0f, 1f) })
	}
}
