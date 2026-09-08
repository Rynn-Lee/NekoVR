package dev.slimevr.ai

import io.github.axisangles.ktmath.Quaternion
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class SafetyGateResult(
	val correction: Quaternion = Quaternion.IDENTITY,
	val prediction: Quaternion = Quaternion.IDENTITY,
	val applied: Boolean = false,
	val rejectionReason: String? = null,
	val outlier: Boolean = false,
	val stale: Boolean = false,
)

/** Stateful, per-tracker safety envelope for raw model rotation-vector outputs. */
internal class CorrectionSafetyGate(
	private val config: AIModelConfig,
) {
	private data class State(
		var applied: FloatArray = FloatArray(3),
		var velocity: FloatArray = FloatArray(3),
		var lastUpdateNanos: Long = 0L,
		var lastSequence: Long = Long.MIN_VALUE,
	)

	private val states = mutableMapOf<Int, State>()

	@Synchronized
	fun evaluate(output: TrackerInferenceOutput, expectedEpoch: Long, nowNanos: Long): SafetyGateResult {
		val state = states.getOrPut(output.trackerId) { State() }
		if (output.epoch != expectedEpoch) {
			states.remove(output.trackerId)
			return SafetyGateResult(rejectionReason = "STALE_EPOCH", stale = true)
		}
		val ageNanos = (nowNanos - output.monotonicNanos).coerceAtLeast(0L)
		if (ageNanos > config.maximumResultAgeMillis.coerceAtLeast(0L) * 1_000_000L) {
			return decayToIdentity(output.trackerId, nowNanos, "STALE_RESULT")
		}
		val raw = output.correctionRotationVector
		if (raw.size != 3 || raw.any { !it.isFinite() } || !output.confidence.isFinite() || output.confidence !in 0f..1f || !output.driftRate.isFinite()) {
			states.remove(output.trackerId)
			return SafetyGateResult(rejectionReason = "NON_FINITE_OUTPUT", outlier = true)
		}
		if (output.confidence < config.confidenceThreshold.coerceIn(0f, 1f)) {
			states.remove(output.trackerId)
			return SafetyGateResult(rejectionReason = "LOW_CONFIDENCE")
		}

		val target = raw.copyOf().also { vector ->
			for (index in vector.indices) vector[index] *= config.intensity.coerceAtLeast(0f)
		}
		var outlier = false
		val magnitude = norm(target)
		val maximumMagnitude = config.maximumCorrectionRadians.coerceAtLeast(0f)
		if (magnitude > maximumMagnitude && magnitude > 0f) {
			scale(target, maximumMagnitude / magnitude)
			outlier = true
		}

		val dt = if (state.lastUpdateNanos == 0L) 0.02f else ((nowNanos - state.lastUpdateNanos).coerceAtLeast(1L) / 1_000_000_000f).coerceAtMost(0.25f)
		val smoothingAlpha = (1f - config.smoothing.coerceIn(0f, 0.999f))
		val desired = FloatArray(3) { state.applied[it] + (target[it] - state.applied[it]) * smoothingAlpha }
		val desiredVelocity = FloatArray(3) { (desired[it] - state.applied[it]) / dt }
		if (clampMagnitude(desiredVelocity, config.maximumAngularRateRadiansPerSecond.coerceAtLeast(0f))) outlier = true
		val acceleration = FloatArray(3) { (desiredVelocity[it] - state.velocity[it]) / dt }
		if (clampMagnitude(acceleration, config.maximumAngularAccelerationRadiansPerSecondSquared.coerceAtLeast(0f))) outlier = true
		val boundedVelocity = FloatArray(3) { state.velocity[it] + acceleration[it] * dt }
		if (clampMagnitude(boundedVelocity, config.maximumAngularRateRadiansPerSecond.coerceAtLeast(0f))) outlier = true
		val applied = FloatArray(3) { state.applied[it] + boundedVelocity[it] * dt }
		if (clampMagnitude(applied, maximumMagnitude)) outlier = true

		state.applied = applied
		state.velocity = boundedVelocity
		state.lastUpdateNanos = nowNanos
		val isNewOutput = state.lastSequence != output.sequence
		state.lastSequence = output.sequence
		return SafetyGateResult(
			correction = rotationVectorToQuaternion(applied),
			prediction = rotationVectorToQuaternion(raw),
			applied = true,
			outlier = outlier && isNewOutput,
		)
	}

	@Synchronized
	fun decayToIdentity(trackerId: Int, nowNanos: Long, reason: String): SafetyGateResult {
		val state = states[trackerId] ?: return SafetyGateResult(rejectionReason = reason, stale = true)
		val dt = ((nowNanos - state.lastUpdateNanos).coerceAtLeast(1L) / 1_000_000_000f).coerceAtMost(0.25f)
		val decaySeconds = config.staleDecaySeconds.coerceAtLeast(0f)
		if (decaySeconds == 0f) {
			states.remove(trackerId)
			return SafetyGateResult(rejectionReason = reason, stale = true)
		}
		val factor = (1f - dt / decaySeconds).coerceIn(0f, 1f)
		scale(state.applied, factor)
		scale(state.velocity, factor)
		state.lastUpdateNanos = nowNanos
		if (norm(state.applied) < 1e-5f) {
			states.remove(trackerId)
			return SafetyGateResult(rejectionReason = reason, stale = true)
		}
		return SafetyGateResult(
			correction = rotationVectorToQuaternion(state.applied),
			applied = true,
			rejectionReason = "STALE_DECAY",
			stale = true,
		)
	}

	@Synchronized
	fun reset(trackerId: Int) {
		states.remove(trackerId)
	}

	@Synchronized
	fun clear() = states.clear()

	private fun clampMagnitude(vector: FloatArray, maximum: Float): Boolean {
		val magnitude = norm(vector)
		if (magnitude <= maximum || magnitude == 0f) return false
		scale(vector, maximum / magnitude)
		return true
	}

	private fun norm(vector: FloatArray): Float = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()

	private fun scale(vector: FloatArray, factor: Float) {
		for (index in vector.indices) vector[index] *= factor
	}

	private fun rotationVectorToQuaternion(vector: FloatArray): Quaternion {
		val angle = norm(vector)
		if (!angle.isFinite() || angle < 1e-7f) return Quaternion.IDENTITY
		val halfAngle = angle * 0.5f
		val scale = sin(halfAngle) / angle
		return Quaternion(cos(halfAngle), vector[0] * scale, vector[1] * scale, vector[2] * scale).unit()
	}
}
