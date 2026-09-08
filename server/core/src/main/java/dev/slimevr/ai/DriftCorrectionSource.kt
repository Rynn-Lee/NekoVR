package dev.slimevr.ai

import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3

/**
 * Result of the optional drift-correction stage. The world-frame correction is
 * composed on the left of the calibrated tracker orientation. Identity is the mandatory
 * fail-open value whenever the feature is disabled or unavailable.
 */
data class DriftCorrectionResult(
	val correction: Quaternion = Quaternion.IDENTITY,
	val prediction: Quaternion = Quaternion.IDENTITY,
	val applied: Boolean = false,
	val rejectionReason: String? = "UNAVAILABLE",
	val modelHash: String? = null,
	val provider: String? = null,
	val historyValid: Boolean = false,
	val latencyMicros: Long? = null,
	val epoch: Long = 0L,
)

interface DriftCorrectionSource {
	fun correctionFor(
		trackerId: Int,
		preAiRotation: Quaternion,
		acceleration: Vector3,
		epoch: Long = 0L,
	): DriftCorrectionResult

	fun legacyDriftCompensationMode(trackerId: Int): LegacyDriftCompensationMode = LegacyDriftCompensationMode.COMPOSE

	fun resetHistory(trackerId: Int, epoch: Long) {}

	companion object {
		inline operator fun invoke(
			crossinline fn: (Int, Quaternion, Vector3) -> DriftCorrectionResult,
		): DriftCorrectionSource = object : DriftCorrectionSource {
			override fun correctionFor(
				trackerId: Int,
				preAiRotation: Quaternion,
				acceleration: Vector3,
				epoch: Long,
			): DriftCorrectionResult = fn(trackerId, preAiRotation, acceleration)
		}

		inline operator fun invoke(
			crossinline fn: (Int, Quaternion, Vector3, Long) -> DriftCorrectionResult,
		): DriftCorrectionSource = object : DriftCorrectionSource {
			override fun correctionFor(
				trackerId: Int,
				preAiRotation: Quaternion,
				acceleration: Vector3,
				epoch: Long,
			): DriftCorrectionResult = fn(trackerId, preAiRotation, acceleration, epoch)
		}

		@JvmField
		val IDENTITY: DriftCorrectionSource = object : DriftCorrectionSource {
			override fun correctionFor(
				trackerId: Int,
				preAiRotation: Quaternion,
				acceleration: Vector3,
				epoch: Long,
			): DriftCorrectionResult = DriftCorrectionResult(epoch = epoch)
		}
	}
}
