package dev.slimevr.unit

import com.jme3.math.FastMath
import com.github.luben.zstd.ZstdInputStream
import dev.slimevr.ai.DriftCorrectionResult
import dev.slimevr.ai.DriftCorrectionSource
import dev.slimevr.dataset.DatasetArchiveValidator
import dev.slimevr.dataset.DatasetRecordingService
import dev.slimevr.dataset.RecordingRequest
import dev.slimevr.dataset.SessionPrivacyOptions
import dev.slimevr.dataset.generated.DatasetV1Reader
import dev.slimevr.reset.AXIS_MASK_MOUNTING
import dev.slimevr.reset.AXIS_MASK_YAW
import dev.slimevr.reset.DefaultResetEventPublisher
import dev.slimevr.reset.FLAG_INVALID_OR_STALE_HMD
import dev.slimevr.reset.FLAG_EXCESS_MOTION
import dev.slimevr.reset.FLAG_INSUFFICIENT_CONTEXT
import dev.slimevr.reset.FLAG_INVALID_QUATERNIONS
import dev.slimevr.reset.FLAG_OVERLAPPING_RESETS
import dev.slimevr.reset.FLAG_PACKET_GAPS
import dev.slimevr.reset.FLAG_RECONNECT_OR_REASSIGNMENT
import dev.slimevr.reset.FLAG_WINDOW_TRUNCATED
import dev.slimevr.reset.ResetEvent
import dev.slimevr.reset.ResetEventListener
import dev.slimevr.reset.ResetKind
import dev.slimevr.reset.ResetLabelCalculator
import dev.slimevr.reset.ResetOutcome
import dev.slimevr.reset.ResetSupervisionPolicy
import dev.slimevr.reset.SupervisionAction
import dev.slimevr.reset.resetTimer
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import io.github.axisangles.ktmath.EulerAngles
import io.github.axisangles.ktmath.EulerOrder
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedInputStream
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipFile
import kotlin.math.abs

class ResetSupervisionTests {

	private val eps = 1e-3f

	private fun toRad(deg: Float): Float = deg * FastMath.DEG_TO_RAD

	@Test
	fun testWraparoundYaw() {
		val q170 = EulerAngles(EulerOrder.YZX, 0f, toRad(170f), 0f).toQuaternion()
		val qMinus170 = EulerAngles(EulerOrder.YZX, 0f, toRad(-170f), 0f).toQuaternion()

		val (correction, diagnosticYaw) = ResetLabelCalculator.computeCorrection(q170, qMinus170)

		assertTrue(correction.w >= 0f, "Correction quaternion w should be canonicalized to >= 0")

		val expectedYaw = toRad(20f)
		val diff = abs(diagnosticYaw - expectedYaw)
		assertTrue(diff < eps || abs(diff - FastMath.TWO_PI) < eps, "Diagnostic yaw should be 20 deg, got $diagnosticYaw")
	}

	@Test
	fun testQuaternionSignEquivalence() {
		val q = EulerAngles(EulerOrder.YZX, toRad(15f), toRad(45f), toRad(20f)).toQuaternion()
		val negQ = Quaternion(-q.w, -q.x, -q.y, -q.z)

		val (correction, diagnosticYaw) = ResetLabelCalculator.computeCorrection(q, negQ)

		assertTrue(correction.w >= 0f, "Canonicalized w must be >= 0")
		assertEquals(1.0f, correction.w, eps, "Sign-equivalent quaternions should produce identity correction")
		assertEquals(0.0f, correction.x, eps)
		assertEquals(0.0f, correction.y, eps)
		assertEquals(0.0f, correction.z, eps)
		assertEquals(0.0f, diagnosticYaw, eps, "Diagnostic yaw for sign-equivalent rotation must be 0")
	}

	@Test
	fun testKnownTransformYaw30Degrees() {
		val qPre = EulerAngles(EulerOrder.YZX, 0f, 0f, 0f).toQuaternion()
		val expectedYawRad = toRad(30f)
		val qPost = EulerAngles(EulerOrder.YZX, 0f, expectedYawRad, 0f).toQuaternion()

		val (correction, diagnosticYaw) = ResetLabelCalculator.computeCorrection(qPre, qPost)

		assertTrue(correction.w >= 0f)
		assertEquals(expectedYawRad, diagnosticYaw, eps, "Diagnostic yaw should match 30 deg in radians")
	}

	@Test
	fun testPartialBodyReset() {
		val trackers = TestTrackerSet()
		val publisher = DefaultResetEventPublisher()
		val capturedEvents = CopyOnWriteArrayList<ResetEvent>()
		publisher.addListener(ResetEventListener { capturedEvents.add(it) })

		val hpm = HumanPoseManager(trackers.allL, resetEventPublisher = publisher)
		trackers.chest.setRotation(EulerAngles(EulerOrder.YZX, 0f, toRad(20f), 0f).toQuaternion())
		trackers.hip.setRotation(EulerAngles(EulerOrder.YZX, 0f, toRad(40f), 0f).toQuaternion())
		trackers.head.setRotation(Quaternion.IDENTITY)

		val chestBodyPart = TrackerPosition.CHEST.bodyPart
		hpm.resetTrackersYaw("unit-test", listOf(chestBodyPart))

		val applied = capturedEvents.firstOrNull { it.outcome == ResetOutcome.APPLIED }
		assertNotNull(applied, "Should have received an APPLIED reset event")
		assertEquals(1, applied!!.labels.size, "Should only have one label for partial chest reset")
		assertEquals(trackers.chest.id, applied.labels[0].trackerId, "Label should belong to chest")
	}

	@Test
	fun testMultiTrackerReset() {
		val trackers = TestTrackerSet()
		val publisher = DefaultResetEventPublisher()
		val capturedEvents = CopyOnWriteArrayList<ResetEvent>()
		publisher.addListener(ResetEventListener { capturedEvents.add(it) })

		val hpm = HumanPoseManager(trackers.allL, resetEventPublisher = publisher)
		for ((i, tracker) in trackers.set.withIndex()) {
			tracker.setRotation(EulerAngles(EulerOrder.YZX, 0f, toRad((i + 1) * 15f), 0f).toQuaternion())
		}
		trackers.head.setRotation(Quaternion.IDENTITY)

		val bodyParts = trackers.set.map { it.trackerPosition!!.bodyPart }
		hpm.resetTrackersYaw("unit-test", bodyParts)

		val applied = capturedEvents.firstOrNull { it.outcome == ResetOutcome.APPLIED }
		assertNotNull(applied, "Should receive APPLIED reset event")
		assertEquals(trackers.set.size, applied!!.labels.size, "All targeted trackers should receive labels")

		val labelTrackerIds = applied.labels.map { it.trackerId }.toSet()
		for (tracker in trackers.set) {
			assertTrue(labelTrackerIds.contains(tracker.id), "Tracker ${tracker.id} should have a label record")
		}
	}

	@Test
	fun testDelayedResetAndCancellation() {
		val timerManager = dev.slimevr.reset.ResetTimerManager()
		val publisher = DefaultResetEventPublisher()
		val capturedEvents = CopyOnWriteArrayList<ResetEvent>()
		publisher.addListener(ResetEventListener { capturedEvents.add(it) })

		val requestNs = System.nanoTime()
		val requestId = "delayed-reset-test"
		publisher.publish(
			ResetEvent(
				requestId = requestId,
				outcome = ResetOutcome.REQUESTED,
				kind = ResetKind.FULL,
				source = "test-timer",
				requestMonotonicNs = requestNs,
				scheduledDelayMs = 3000L,
			),
		)

		resetTimer(
			timerManager,
			3000L,
			onTick = {},
			onComplete = {},
			onCancel = {
				publisher.publish(
					ResetEvent(
						requestId = requestId,
						outcome = ResetOutcome.CANCELLED,
						kind = ResetKind.FULL,
						source = "test-timer",
						requestMonotonicNs = requestNs,
					),
				)
			},
		)

		timerManager.cancelTimers()

		assertEquals(2, capturedEvents.size)
		assertEquals(ResetOutcome.REQUESTED, capturedEvents[0].outcome)
		assertEquals(ResetOutcome.CANCELLED, capturedEvents[1].outcome)
		assertEquals(requestNs, capturedEvents[1].requestMonotonicNs)
	}

	@Test
	fun testInvalidReferenceHmdExclusion() {
		val trackers = TestTrackerSet()
		val publisher = DefaultResetEventPublisher()
		val capturedEvents = CopyOnWriteArrayList<ResetEvent>()
		publisher.addListener(ResetEventListener { capturedEvents.add(it) })

		val hpm = HumanPoseManager(trackers.allL, resetEventPublisher = publisher)
		trackers.head.status = TrackerStatus.DISCONNECTED

		hpm.resetTrackersYaw("unit-test", listOf(TrackerPosition.CHEST.bodyPart))

		val applied = capturedEvents.firstOrNull { it.outcome == ResetOutcome.APPLIED }
		assertNotNull(applied)
		val label = applied!!.labels.first()

		assertTrue((label.qualityFlags and FLAG_INVALID_OR_STALE_HMD) != 0, "Flag should include invalid/stale HMD")
		assertFalse(label.hmdValid, "hmdValid should be false")

		val decision = ResetSupervisionPolicy.evaluate(label.domain, label.qualityFlags, targetTask = ResetKind.YAW)
		assertEquals(SupervisionAction.EXCLUDE, decision.action, "Invalid HMD should result in EXCLUDE supervision action")
		assertEquals(0f, decision.weight)
	}

	@Test
	fun testMountingResetSeparatedDomain() {
		val trackers = TestTrackerSet()
		val publisher = DefaultResetEventPublisher()
		val capturedEvents = CopyOnWriteArrayList<ResetEvent>()
		publisher.addListener(ResetEventListener { capturedEvents.add(it) })

		val hpm = HumanPoseManager(trackers.allL, resetEventPublisher = publisher)
		hpm.resetTrackersMounting("unit-test", listOf(TrackerPosition.CHEST.bodyPart))

		val applied = capturedEvents.firstOrNull { it.outcome == ResetOutcome.APPLIED }
		assertNotNull(applied)
		val label = applied!!.labels.first()

		assertEquals(ResetKind.MOUNTING, label.domain)
		assertEquals(AXIS_MASK_MOUNTING, label.axisMask)

		val yawDecision = ResetSupervisionPolicy.evaluate(label.domain, label.qualityFlags, targetTask = ResetKind.YAW)
		assertEquals(SupervisionAction.EXCLUDE, yawDecision.action)

		val mountingDecision = ResetSupervisionPolicy.evaluate(label.domain, label.qualityFlags, targetTask = ResetKind.MOUNTING)
		assertEquals(SupervisionAction.INCLUDE, mountingDecision.action)
	}

	@Test
	fun testEpochInvalidationOnReset() {
		val trackers = TestTrackerSet()
		val hpm = HumanPoseManager(trackers.allL)

		val chest = trackers.chest
		val initialEpoch = chest.resetsHandler.resetEpoch
		val initialCalibrationEpoch = chest.resetsHandler.calibrationEpoch

		hpm.resetTrackersYaw("unit-test", listOf(TrackerPosition.CHEST.bodyPart))

		val afterYawEpoch = chest.resetsHandler.resetEpoch
		assertEquals(initialEpoch + 1, afterYawEpoch, "Yaw reset must increment resetEpoch")

		hpm.resetTrackersMounting("unit-test", listOf(TrackerPosition.CHEST.bodyPart))

		val afterMountingEpoch = chest.resetsHandler.resetEpoch
		val afterCalibrationEpoch = chest.resetsHandler.calibrationEpoch
		assertEquals(afterYawEpoch + 1, afterMountingEpoch, "Mounting reset must increment resetEpoch")
		assertEquals(initialCalibrationEpoch + 1, afterCalibrationEpoch, "Mounting reset must increment calibrationEpoch")
	}

	@Test
	fun testLateAiResultFromPreviousEpochIsRejected() {
		val tracker = TestTrackerSet().chest
		tracker.resetsHandler.setDriftCorrectionSource(
			DriftCorrectionSource { _, _, _, epoch ->
				DriftCorrectionResult(correction = EulerAngles(EulerOrder.YZX, 0f, toRad(45f), 0f).toQuaternion(), applied = true, epoch = epoch - 1)
			},
		)
		tracker.resetsHandler.resetYaw(Quaternion.IDENTITY)
		tracker.getRotation()
		assertFalse(tracker.resetsHandler.lastAiCorrection.applied)
		assertEquals("STALE_EPOCH", tracker.resetsHandler.lastAiCorrection.rejectionReason)
		assertEquals(tracker.resetsHandler.resetEpoch, tracker.resetsHandler.lastAiCorrection.epoch)
	}

	@Test
	fun testRecorderLinksExclusiveContextWindowsAndLifecycle(@TempDir tempDir: Path) {
		val trackers = TestTrackerSet()
		val publisher = DefaultResetEventPublisher()
		var clock = System.nanoTime()
		val recorder = DatasetRecordingService(
			datasetsRoot = tempDir,
			clockNs = { clock },
			resetPreContextFrames = 2,
			resetPostContextFrames = 2,
		)
		recorder.bindResetPublisher(publisher)
		recorder.startRecording(
			RecordingRequest(privacy = SessionPrivacyOptions(consent = true), minFreeSpaceBytes = 0),
			trackers.allL,
		)
		repeat(3) {
			recorder.sampleIfDue(trackers.allL)
			clock += 20_000_000L
		}
		HumanPoseManager(trackers.allL, resetEventPublisher = publisher)
			.resetTrackersYaw("context-fixture", listOf(TrackerPosition.CHEST.bodyPart))
		repeat(4) {
			recorder.sampleIfDue(trackers.allL)
			clock += 20_000_000L
		}
		val archive = recorder.stopAndFinalize(10)

		val records = mutableListOf<dev.slimevr.dataset.generated.DatasetRecordSummary>()
		ZipFile(archive.toFile()).use { zip ->
			ZstdInputStream(BufferedInputStream(zip.getInputStream(zip.getEntry("telemetry.fbs.zst")))).use { input ->
				while (true) {
					val bytes = DatasetArchiveValidator.readRecord(input) ?: break
					records += DatasetV1Reader.read(bytes)
				}
			}
		}
		val resetEvents = records.flatMap { it.events }.filter { it.resetKind == ResetKind.YAW.name }
		val label = records.flatMap { it.resetLabels }.single()
		assertEquals(listOf("REQUESTED", "APPLIED"), resetEvents.map { it.resetOutcome })
		assertEquals(1, resetEvents.map { it.requestId }.distinct().size)
		assertEquals(resetEvents.last().eventIndex, label.eventIndex)
		assertEquals(1L, label.preStartFrame)
		assertEquals(3L, label.preEndFrame)
		assertEquals(3L, label.postStartFrame)
		assertEquals(5L, label.postEndFrame)
		assertEquals(1f, kotlin.math.sqrt(label.correction.x * label.correction.x + label.correction.y * label.correction.y + label.correction.z * label.correction.z + label.correction.w * label.correction.w), eps)
	}

	@Test
	fun testAllDeterministicQualityFlags() {
		val pre = TestTrackerSet().chest.snapshotResetState()
		val post = pre.copy(
			rawOrientation = Quaternion(Float.NaN, 0f, 0f, 1f),
			acceleration = Vector3(3f, 0f, 0f),
			angularVelocity = Vector3(0f, 2f, 0f),
			status = TrackerStatus.DISCONNECTED,
			trackerPosition = TrackerPosition.HIP,
		)
		val flags = ResetLabelCalculator.computeQualityFlags(
			hmdValid = false,
			preState = pre,
			postState = post,
			targetQuat = Quaternion.IDENTITY,
			packetGap = true,
			overlappingReset = true,
			reconnectOrReassigned = true,
			insufficientContext = true,
			truncatedWindow = true,
		)
		for (flag in listOf(
			FLAG_INVALID_OR_STALE_HMD, FLAG_EXCESS_MOTION, FLAG_PACKET_GAPS,
			FLAG_RECONNECT_OR_REASSIGNMENT, FLAG_INVALID_QUATERNIONS,
			FLAG_OVERLAPPING_RESETS, FLAG_INSUFFICIENT_CONTEXT, FLAG_WINDOW_TRUNCATED,
		)) assertTrue(flags and flag != 0, "Expected quality flag $flag")
	}
}
