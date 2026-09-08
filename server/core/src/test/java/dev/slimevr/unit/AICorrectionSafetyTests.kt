package dev.slimevr.unit

import dev.slimevr.VRServer.Companion.getNextLocalTrackerId
import dev.slimevr.ai.AIModelConfig
import dev.slimevr.ai.CorrectionSafetyGate
import dev.slimevr.ai.DriftCorrectionResult
import dev.slimevr.ai.DriftCorrectionSource
import dev.slimevr.ai.LegacyDriftCompensationMode
import dev.slimevr.ai.TrackerInferenceOutput
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.udp.IMUType
import io.github.axisangles.ktmath.EulerAngles
import io.github.axisangles.ktmath.EulerOrder
import io.github.axisangles.ktmath.Quaternion
import kotlin.math.abs
import kotlin.math.acos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AICorrectionSafetyTests {
	@Test
	fun `confidence and finite gating are independent per tracker`() {
		val config = unboundedConfig().apply { confidenceThreshold = 0.7f }
		val gate = CorrectionSafetyGate(config)
		val accepted = gate.evaluate(output(1, confidence = 0.9f), 4L, NOW)
		val rejected = gate.evaluate(output(2, confidence = 0.2f), 4L, NOW)
		val nonFinite = gate.evaluate(output(3, vector = floatArrayOf(Float.NaN, 0f, 0f)), 4L, NOW)

		assertTrue(accepted.applied)
		assertFalse(rejected.applied)
		assertEquals("LOW_CONFIDENCE", rejected.rejectionReason)
		assertFalse(nonFinite.applied)
		assertEquals("NON_FINITE_OUTPUT", nonFinite.rejectionReason)
	}

	@Test
	fun `magnitude rate and acceleration bounds clamp outliers`() {
		val config = AIModelConfig().apply {
			intensity = 1f
			smoothing = 0f
			confidenceThreshold = 0f
			maximumCorrectionRadians = 0.2f
			maximumAngularRateRadiansPerSecond = 0.5f
			maximumAngularAccelerationRadiansPerSecondSquared = 2f
		}
		val result = CorrectionSafetyGate(config).evaluate(
			output(1, vector = floatArrayOf(10f, 0f, 0f)),
			4L,
			NOW,
		)

		assertTrue(result.applied)
		assertTrue(result.outlier)
		assertTrue(quaternionAngle(result.correction) <= 0.2f + 1e-5f)
	}

	@Test
	fun `stale correction decays and then fails open to identity`() {
		val config = unboundedConfig().apply {
			maximumResultAgeMillis = 50L
			staleDecaySeconds = 0.2f
		}
		val gate = CorrectionSafetyGate(config)
		val fresh = gate.evaluate(output(1, vector = floatArrayOf(0.2f, 0f, 0f)), 4L, NOW)
		val decaying = gate.evaluate(output(1, vector = floatArrayOf(0.2f, 0f, 0f)), 4L, NOW + 100_000_000L)
		val identity = gate.evaluate(output(1, vector = floatArrayOf(0.2f, 0f, 0f)), 4L, NOW + 400_000_000L)

		assertTrue(fresh.applied)
		assertTrue(decaying.applied)
		assertEquals("STALE_DECAY", decaying.rejectionReason)
		assertFalse(identity.applied)
		assertEquals(Quaternion.IDENTITY, identity.correction)
	}

	@Test
	fun `explicit correction stage replaces or composes legacy exactly once`() {
		val tracker = Tracker(
			null,
			getNextLocalTrackerId(),
			"ai-stage",
			"ai-stage",
			null,
			hasRotation = true,
			imuType = IMUType.UNKNOWN,
			allowReset = true,
		)
		val calibrated = euler(15f, 20f, 5f)
		val legacy = euler(0f, 30f, 0f)
		val ai = euler(10f, 0f, 0f)
		var mode = LegacyDriftCompensationMode.REPLACE
		tracker.resetsHandler.setDriftCorrectionSource(object : DriftCorrectionSource {
			override fun correctionFor(trackerId: Int, preAiRotation: Quaternion, acceleration: io.github.axisangles.ktmath.Vector3, epoch: Long) = DriftCorrectionResult(correction = ai, applied = true, rejectionReason = null, epoch = epoch)

			override fun legacyDriftCompensationMode(trackerId: Int) = mode
		})

		val replaced = tracker.resetsHandler.applyCorrectionStages(calibrated, legacy)
		assertQuaternionEquivalent(ai * calibrated, replaced)
		assertQuaternionEquivalent(calibrated, tracker.resetsHandler.lastPreAiRotation)

		mode = LegacyDriftCompensationMode.COMPOSE
		val composed = tracker.resetsHandler.applyCorrectionStages(calibrated, legacy)
		assertQuaternionEquivalent(ai * (legacy * calibrated), composed)
		assertQuaternionEquivalent(legacy * calibrated, tracker.resetsHandler.lastPreAiRotation)
	}

	@Test
	fun `unavailable AI fails open and preserves normal legacy tracking`() {
		val tracker = Tracker(
			null,
			getNextLocalTrackerId(),
			"ai-fallback",
			"ai-fallback",
			null,
			hasRotation = true,
			imuType = IMUType.UNKNOWN,
			allowReset = true,
		)
		val calibrated = euler(15f, 20f, 5f)
		val legacy = euler(0f, 30f, 0f)
		tracker.resetsHandler.setDriftCorrectionSource(DriftCorrectionSource.IDENTITY)

		val result = tracker.resetsHandler.applyCorrectionStages(calibrated, legacy)

		assertQuaternionEquivalent(legacy * calibrated, result)
		assertFalse(tracker.resetsHandler.lastAiCorrection.applied)
		assertEquals("UNAVAILABLE", tracker.resetsHandler.lastAiCorrection.rejectionReason)
	}

	private fun unboundedConfig() = AIModelConfig().apply {
		intensity = 1f
		smoothing = 0f
		maximumCorrectionRadians = 100f
		maximumAngularRateRadiansPerSecond = 100_000f
		maximumAngularAccelerationRadiansPerSecondSquared = 10_000_000f
	}

	private fun output(
		trackerId: Int,
		confidence: Float = 1f,
		vector: FloatArray = floatArrayOf(0.1f, 0f, 0f),
	) = TrackerInferenceOutput(
		trackerId = trackerId, bodyRoleId = 1, slot = trackerId - 1, epoch = 4L,
		sequence = 1L, monotonicNanos = NOW, correctionRotationVector = vector,
		confidence = confidence, driftRate = 0f, latencyMicros = 100L,
	)

	private fun euler(pitch: Float, yaw: Float, roll: Float) = EulerAngles(
		EulerOrder.YZX,
		Math.toRadians(pitch.toDouble()).toFloat(),
		Math.toRadians(yaw.toDouble()).toFloat(),
		Math.toRadians(roll.toDouble()).toFloat(),
	).toQuaternion()

	private fun quaternionAngle(value: Quaternion): Float = (2.0 * acos(abs(value.w).toDouble().coerceIn(0.0, 1.0))).toFloat()

	private fun assertQuaternionEquivalent(expected: Quaternion, actual: Quaternion) {
		val dot = abs(expected.w * actual.w + expected.x * actual.x + expected.y * actual.y + expected.z * actual.z)
		assertTrue(dot > 0.99999f, "Expected $expected, got $actual")
	}

	private companion object {
		const val NOW = 1_000_000_000L
	}
}
