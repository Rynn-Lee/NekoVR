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
import dev.slimevr.reset.ResetLabelQualityConfig
import dev.slimevr.reset.ResetOutcome
import dev.slimevr.reset.ResetRequest
import dev.slimevr.reset.ResetTimerManager
import dev.slimevr.reset.ResetSupervisionPolicy
import dev.slimevr.reset.TrackerAdjustmentSnapshot
import dev.slimevr.reset.TrackerResetStateSnapshot
import dev.slimevr.reset.SupervisionAction
import dev.slimevr.reset.resetTimer
import dev.slimevr.reset.event
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
	fun `label target uses adjusted orientations for non commuting rotations`() {
		val identity = Quaternion.IDENTITY
		val adjustedPre = EulerAngles(EulerOrder.YZX, toRad(90f), 0f, 0f).toQuaternion()
		val adjustedPost = EulerAngles(EulerOrder.YZX, 0f, toRad(90f), 0f).toQuaternion()
		val adjustments = TrackerAdjustmentSnapshot(identity, identity, identity, identity, identity, identity, identity)
		fun state(adjusted: Quaternion, epoch: Long) = TrackerResetStateSnapshot(
			1, TrackerPosition.CHEST, identity, identity, adjusted, adjustments,
			Vector3(0f, 0f, 0f), Vector3(0f, 0f, 0f), TrackerStatus.OK, epoch, epoch, 0, 0,
		)
		val label = ResetLabelCalculator.buildLabelRecord(
			1, "tracker-1", TrackerPosition.CHEST, ResetKind.FULL,
			state(adjustedPre, 1), state(adjustedPost, 2), null, null, 10, 20,
		)
		val expected = ResetLabelCalculator.computeCorrection(adjustedPre, adjustedPost).first
		assertEquals(expected.w, label.correction.w, eps)
		assertEquals(expected.x, label.correction.x, eps)
		assertEquals(expected.y, label.correction.y, eps)
		assertEquals(expected.z, label.correction.z, eps)
		assertTrue(abs(label.correction.w - 1f) > eps || abs(label.correction.x) > eps || abs(label.correction.y) > eps || abs(label.correction.z) > eps)
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
	fun `completion and cancellation race emits exactly one terminal callback`() {
		val timerManager = ResetTimerManager()
		val completionEntered = CountDownLatch(1)
		val releaseCompletion = CountDownLatch(1)
		val completionDone = CountDownLatch(1)
		val completed = AtomicInteger()
		val cancelled = AtomicInteger()
		resetTimer(
			timerManager,
			10L,
			onTick = {},
			onComplete = {
				completionEntered.countDown()
				releaseCompletion.await(2, TimeUnit.SECONDS)
				completed.incrementAndGet()
				completionDone.countDown()
			},
			onCancel = { cancelled.incrementAndGet() },
		)
		assertTrue(completionEntered.await(2, TimeUnit.SECONDS))
		timerManager.cancelTimers()
		releaseCompletion.countDown()
		assertTrue(completionDone.await(2, TimeUnit.SECONDS))
		assertEquals(1, completed.get())
		assertEquals(0, cancelled.get())
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
	fun `overflow retains reset lifecycle gap and label until durable enqueue`(@TempDir tempDir: Path) {
		val trackers = TestTrackerSet()
		val publisher = DefaultResetEventPublisher()
		var clock = System.nanoTime()
		val recorder = DatasetRecordingService(
			datasetsRoot = tempDir, clockNs = { clock }, queueCapacity = 1, batchSize = 20,
			writerDelayMillis = 15, resetPreContextFrames = 1, resetPostContextFrames = 1,
		)
		recorder.bindResetPublisher(publisher)
		recorder.startRecording(RecordingRequest(privacy = SessionPrivacyOptions(consent = true), minFreeSpaceBytes = 0), trackers.allL)
		recorder.sampleIfDue(trackers.allL)
		clock += 20_000_000L
		HumanPoseManager(trackers.allL, resetEventPublisher = publisher)
			.resetTrackersYaw("overflow-reset", listOf(TrackerPosition.CHEST.bodyPart))
		repeat(100) {
			recorder.sampleIfDue(trackers.allL)
			clock += 20_000_000L
		}
		assertTrue(recorder.status().droppedFrames > 0)
		assertTrue(recorder.controlMetadataDepth <= recorder.maximumControlItems)
		val archive = recorder.stopAndFinalize(15)
		val records = mutableListOf<dev.slimevr.dataset.generated.DatasetRecordSummary>()
		ZipFile(archive.toFile()).use { zip ->
			ZstdInputStream(BufferedInputStream(zip.getInputStream(zip.getEntry("telemetry.fbs.zst")))).use { input ->
				while (true) records += DatasetV1Reader.read(DatasetArchiveValidator.readRecord(input) ?: break)
			}
		}
		val lifecycle = records.flatMap { it.events }.filter { it.resetKind == ResetKind.YAW.name }
		assertEquals(listOf("REQUESTED", "APPLIED"), lifecycle.map { it.resetOutcome })
		assertEquals(1, records.flatMap { it.resetLabels }.size)
		assertTrue(records.flatMap { it.events }.any { it.type == "GAP" })
		assertTrue(DatasetArchiveValidator().validate(archive).valid)
	}

	@Test
	fun `backlog retains exactly one cancelled failed and multi tracker applied lifecycle`(@TempDir tempDir: Path) {
		val trackers = TestTrackerSet()
		val publisher = DefaultResetEventPublisher()
		val published = CopyOnWriteArrayList<ResetEvent>()
		publisher.addListener(ResetEventListener(published::add))
		var clock = System.nanoTime()
		val recorder = DatasetRecordingService(
			datasetsRoot = tempDir, clockNs = { clock }, queueCapacity = 1, batchSize = 20,
			writerDelayMillis = 15, resetPreContextFrames = 1, resetPostContextFrames = 1,
		)
		recorder.bindResetPublisher(publisher)
		recorder.startRecording(RecordingRequest(privacy = SessionPrivacyOptions(consent = true), minFreeSpaceBytes = 0), trackers.allL)
		repeat(30) {
			recorder.sampleIfDue(trackers.allL)
			clock += 20_000_000L
		}

		val parts = listOf(TrackerPosition.CHEST.bodyPart, TrackerPosition.HIP.bodyPart)
		val cancelled = ResetRequest("cancelled-backlog", ResetKind.FULL, "delayed-test", clock, 3000, parts)
		publisher.publish(cancelled.event(ResetOutcome.REQUESTED))
		publisher.publish(cancelled.event(ResetOutcome.REQUESTED))
		publisher.publish(cancelled.event(ResetOutcome.CANCELLED))
		publisher.publish(cancelled.event(ResetOutcome.CANCELLED))

		val failed = ResetRequest("failed-backlog", ResetKind.MOUNTING, "delayed-test", clock + 1, 3000, parts)
		publisher.publish(failed.event(ResetOutcome.REQUESTED))
		publisher.publish(failed.event(ResetOutcome.FAILED, failureReason = "reference unavailable"))
		publisher.publish(failed.event(ResetOutcome.FAILED, failureReason = "reference unavailable"))

		val applied = ResetRequest("applied-backlog", ResetKind.YAW, "delayed-test", clock + 2, 3000, parts)
		publisher.publish(applied.event(ResetOutcome.REQUESTED))
		publisher.publish(applied.event(ResetOutcome.REQUESTED))
		HumanPoseManager(trackers.allL, resetEventPublisher = publisher).resetTrackersYaw("delayed-test", parts, applied)
		publisher.publish(published.single { it.requestId == applied.requestId && it.outcome == ResetOutcome.APPLIED })

		repeat(40) {
			recorder.sampleIfDue(trackers.allL)
			clock += 20_000_000L
		}
		val archive = recorder.stopAndFinalize(15)
		val records = mutableListOf<dev.slimevr.dataset.generated.DatasetRecordSummary>()
		ZipFile(archive.toFile()).use { zip ->
			ZstdInputStream(BufferedInputStream(zip.getInputStream(zip.getEntry("telemetry.fbs.zst")))).use { input ->
				while (true) records += DatasetV1Reader.read(DatasetArchiveValidator.readRecord(input) ?: break)
			}
		}
		val events = records.flatMap { it.events }.filter { it.requestId != null }.groupBy { it.requestId }
		assertEquals(listOf("REQUESTED", "CANCELLED"), events.getValue(cancelled.requestId).map { it.resetOutcome })
		assertEquals(listOf("REQUESTED", "FAILED"), events.getValue(failed.requestId).map { it.resetOutcome })
		assertEquals(listOf("REQUESTED", "APPLIED"), events.getValue(applied.requestId).map { it.resetOutcome })
		val labels = records.flatMap { it.resetLabels }
		assertEquals(2, labels.count { it.requestId == applied.requestId })
		assertTrue(labels.none { it.requestId == cancelled.requestId || it.requestId == failed.requestId })
		assertTrue(labels.filter { it.requestId == applied.requestId }.all { it.eventIndex == events.getValue(applied.requestId).last().eventIndex })
		assertTrue(DatasetArchiveValidator().validate(archive).valid)
	}

	@Test
	fun `real publisher and recorder retain full yaw mounting partial multi and overlap labels`(@TempDir tempDir: Path) {
		val trackers = TestTrackerSet()
		val publisher = DefaultResetEventPublisher()
		var clock = System.nanoTime()
		val recorder = DatasetRecordingService(tempDir, clockNs = { clock }, resetPreContextFrames = 1, resetPostContextFrames = 1)
		recorder.bindResetPublisher(publisher)
		recorder.startRecording(RecordingRequest(privacy = SessionPrivacyOptions(consent = true), minFreeSpaceBytes = 0), trackers.allL)
		repeat(3) { recorder.sampleIfDue(trackers.allL); clock += 20_000_000L }
		trackers.chest.setRotation(EulerAngles(EulerOrder.YZX, toRad(70f), toRad(25f), toRad(-35f)).toQuaternion())
		val hpm = HumanPoseManager(trackers.allL, resetEventPublisher = publisher)
		hpm.resetTrackersYaw("integration", listOf(TrackerPosition.CHEST.bodyPart))
		val twoParts = listOf(TrackerPosition.CHEST.bodyPart, TrackerPosition.HIP.bodyPart)
		hpm.resetTrackersFull("integration", twoParts)
		hpm.resetTrackersMounting("integration", twoParts)
		repeat(5) { recorder.sampleIfDue(trackers.allL); clock += 20_000_000L }
		val archive = recorder.stopAndFinalize(10)
		val records = mutableListOf<dev.slimevr.dataset.generated.DatasetRecordSummary>()
		ZipFile(archive.toFile()).use { zip ->
			ZstdInputStream(BufferedInputStream(zip.getInputStream(zip.getEntry("telemetry.fbs.zst")))).use { input ->
				while (true) records += DatasetV1Reader.read(DatasetArchiveValidator.readRecord(input) ?: break)
			}
		}
		val events = records.flatMap { it.events }.filter { it.requestId != null }.groupBy { it.requestId }
		assertEquals(3, events.size)
		assertTrue(events.values.all { lifecycle -> lifecycle.map { it.resetOutcome } == listOf("REQUESTED", "APPLIED") })
		val labels = records.flatMap { it.resetLabels }
		assertEquals(mapOf("YAW" to 1, "FULL" to 2, "MOUNTING" to 2), labels.groupingBy { it.domain }.eachCount())
		assertTrue(labels.all { it.qualityFlags and FLAG_OVERLAPPING_RESETS != 0 })
		labels.forEach { label ->
			val pre = Quaternion(label.adjustedOrientationBefore.w, label.adjustedOrientationBefore.x, label.adjustedOrientationBefore.y, label.adjustedOrientationBefore.z)
			val post = Quaternion(label.adjustedOrientationAfter.w, label.adjustedOrientationAfter.x, label.adjustedOrientationAfter.y, label.adjustedOrientationAfter.z)
			val expected = ResetLabelCalculator.computeCorrection(pre, post).first
			assertEquals(expected.w, label.correction.w, eps)
			assertEquals(expected.x, label.correction.x, eps)
			assertEquals(expected.y, label.correction.y, eps)
			assertEquals(expected.z, label.correction.z, eps)
		}
		assertTrue(DatasetArchiveValidator().validate(archive).valid)
	}

	@Test
	fun `real recorder derives invalid reference packet reassignment and truncated flags`(@TempDir tempDir: Path) {
		val trackers = TestTrackerSet()
		val publisher = DefaultResetEventPublisher()
		var clock = System.nanoTime()
		trackers.chest.telemetryCapabilities += 26
		val recorder = DatasetRecordingService(tempDir, clockNs = { clock }, resetPreContextFrames = 1, resetPostContextFrames = 5)
		recorder.bindResetPublisher(publisher)
		recorder.startRecording(RecordingRequest(privacy = SessionPrivacyOptions(consent = true), minFreeSpaceBytes = 0), trackers.allL)
		repeat(2) { recorder.sampleIfDue(trackers.allL); clock += 20_000_000L }
		trackers.head.status = TrackerStatus.DISCONNECTED
		HumanPoseManager(trackers.allL, resetEventPublisher = publisher).resetTrackersYaw("quality-integration", listOf(TrackerPosition.CHEST.bodyPart))
		trackers.chest.packetGaps++
		trackers.chest.status = TrackerStatus.DISCONNECTED
		recorder.sampleIfDue(trackers.allL)
		val archive = recorder.stopAndFinalize(10)
		val records = mutableListOf<dev.slimevr.dataset.generated.DatasetRecordSummary>()
		ZipFile(archive.toFile()).use { zip ->
			ZstdInputStream(BufferedInputStream(zip.getInputStream(zip.getEntry("telemetry.fbs.zst")))).use { input ->
				while (true) records += DatasetV1Reader.read(DatasetArchiveValidator.readRecord(input) ?: break)
			}
		}
		val flags = records.flatMap { it.resetLabels }.single().qualityFlags
		assertTrue(flags and FLAG_INVALID_OR_STALE_HMD != 0)
		assertTrue(flags and FLAG_PACKET_GAPS != 0)
		assertTrue(flags and FLAG_RECONNECT_OR_REASSIGNMENT != 0)
		assertTrue(flags and FLAG_WINDOW_TRUNCATED != 0)
		assertTrue(flags and FLAG_INSUFFICIENT_CONTEXT != 0)
		assertTrue(DatasetArchiveValidator().validate(archive).valid)
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

	@Test
	fun `quality thresholds and invalid quaternion checks use explicit configuration`() {
		val pre = TestTrackerSet().chest.snapshotResetState().copy(sampleAgeNs = 11L)
		val post = pre.copy(acceleration = Vector3(.25f, 0f, 0f))
		val config = ResetLabelQualityConfig(maxHmdSampleAgeNs = 10L, maxLinearAcceleration = .2f, maxAngularVelocity = 10f)
		assertFalse(ResetLabelCalculator.isValidHmdReference(pre, post, config))
		val flags = ResetLabelCalculator.computeQualityFlags(true, pre, post, Quaternion(2f, 0f, 0f, 0f), qualityConfig = config)
		assertTrue(flags and FLAG_EXCESS_MOTION != 0)
		assertTrue(flags and FLAG_INVALID_QUATERNIONS != 0)
	}

	@Test
	fun `all historical AI epochs are rejected after yaw full and mounting resets`() {
		val trackers = TestTrackerSet()
		val tracker = trackers.chest
		var resultEpoch = 0L
		val resetEpochs = mutableListOf<Long>()
		tracker.resetsHandler.setDriftCorrectionSource(object : DriftCorrectionSource {
			override fun correctionFor(trackerId: Int, preAiRotation: Quaternion, acceleration: Vector3, epoch: Long) =
				DriftCorrectionResult(correction = EulerAngles(EulerOrder.YZX, 0f, toRad(45f), 0f).toQuaternion(), applied = true, epoch = resultEpoch)
			override fun resetHistory(trackerId: Int, epoch: Long) { resetEpochs += epoch }
		})
		val hpm = HumanPoseManager(trackers.allL)
		val bodyParts = listOf(TrackerPosition.CHEST.bodyPart)
		repeat(3) { resetIndex ->
			when (resetIndex) {
				0 -> hpm.resetTrackersYaw("epoch-test", bodyParts)
				1 -> hpm.resetTrackersFull("epoch-test", bodyParts)
				else -> hpm.resetTrackersMounting("epoch-test", bodyParts)
			}
			for (previousEpoch in 0L..resetIndex.toLong()) {
				resultEpoch = previousEpoch
				tracker.getRotation()
				assertFalse(tracker.resetsHandler.lastAiCorrection.applied)
				assertEquals("STALE_EPOCH", tracker.resetsHandler.lastAiCorrection.rejectionReason)
				assertEquals(tracker.resetsHandler.resetEpoch, tracker.resetsHandler.lastAiCorrection.epoch)
			}
		}
		assertEquals(listOf(1L, 2L, 3L), resetEpochs)
	}
}
