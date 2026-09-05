package dev.slimevr.reset

import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import io.github.axisangles.ktmath.EulerAngles
import io.github.axisangles.ktmath.EulerOrder
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import java.util.UUID
import kotlin.math.abs

enum class ResetKind {
	YAW,
	FULL,
	MOUNTING,
}

enum class ResetOutcome {
	REQUESTED,
	APPLIED,
	CANCELLED,
	FAILED,
}

const val AXIS_MASK_NONE: Byte = 0
const val AXIS_MASK_YAW: Byte = 1
const val AXIS_MASK_PITCH: Byte = 2
const val AXIS_MASK_ROLL: Byte = 4
const val AXIS_MASK_FULL: Byte = 7 // YAW or PITCH or ROLL
const val AXIS_MASK_MOUNTING: Byte = 8

const val FLAG_OK: Int = 0
const val FLAG_INVALID_OR_STALE_HMD: Int = 1 shl 0
const val FLAG_EXCESS_MOTION: Int = 1 shl 1
const val FLAG_PACKET_GAPS: Int = 1 shl 2
const val FLAG_RECONNECT_OR_REASSIGNMENT: Int = 1 shl 3
const val FLAG_INVALID_QUATERNIONS: Int = 1 shl 4
const val FLAG_OVERLAPPING_RESETS: Int = 1 shl 5
const val FLAG_INSUFFICIENT_CONTEXT: Int = 1 shl 6
const val FLAG_WINDOW_TRUNCATED: Int = 1 shl 7

enum class SupervisionAction {
	INCLUDE,
	DOWNWEIGHT,
	EXCLUDE,
}

data class SupervisionDecision(
	val action: SupervisionAction,
	val weight: Float,
	val reason: String,
)

data class TrackerAdjustmentSnapshot(
	val mountingOrientation: Quaternion,
	val gyroFix: Quaternion,
	val attachmentFix: Quaternion,
	val mountRotFix: Quaternion,
	val tposeDownFix: Quaternion,
	val yawFix: Quaternion,
	val constraintFix: Quaternion,
)

data class TrackerResetStateSnapshot(
	val trackerId: Int,
	val trackerPosition: TrackerPosition?,
	val rawOrientation: Quaternion,
	val calibratedPreAiOrientation: Quaternion,
	val adjustedOrientation: Quaternion,
	val adjustments: TrackerAdjustmentSnapshot,
	val acceleration: Vector3,
	val angularVelocity: Vector3,
	val status: TrackerStatus,
	val resetEpoch: Long,
	val calibrationEpoch: Long,
	val sampleAgeNs: Long,
	val packetGapCount: Long,
)

data class ResetLabelRecord(
	val eventIndex: Long = 0L,
	val trackerId: Int,
	val sessionTrackerId: String? = null,
	val trackerPosition: TrackerPosition?,
	val domain: ResetKind,
	val correction: Quaternion,
	val diagnosticYawRadians: Float,
	val axisMask: Byte,
	val preStartFrame: Long = 0L,
	val preEndFrame: Long = 0L,
	val postStartFrame: Long = 0L,
	val postEndFrame: Long = 0L,
	val qualityFlags: Int,
	val preResetState: TrackerResetStateSnapshot,
	val postResetState: TrackerResetStateSnapshot,
	val hmdReferenceBefore: Quaternion,
	val hmdReferenceAfter: Quaternion,
	val hmdValid: Boolean,
	val hmdSampleAgeBeforeNs: Long,
	val hmdSampleAgeAfterNs: Long,
	val requestId: String? = null,
	val requestMonotonicNs: Long = 0L,
	val appliedMonotonicNs: Long = 0L,
	val trainingPolicy: SupervisionAction = SupervisionAction.INCLUDE,
)

data class ResetRequest(
	val requestId: String = UUID.randomUUID().toString(),
	val kind: ResetKind,
	val source: String,
	val requestMonotonicNs: Long,
	val scheduledDelayMs: Long = 0L,
	val bodyParts: List<Int> = emptyList(),
)

data class ResetEvent(
	val requestId: String,
	val kind: ResetKind,
	val outcome: ResetOutcome,
	val source: String? = null,
	val requestMonotonicNs: Long,
	val appliedMonotonicNs: Long? = null,
	val scheduledDelayMs: Long = 0L,
	val bodyParts: List<Int> = emptyList(),
	val referenceValid: Boolean = true,
	val failureReason: String? = null,
	val labels: List<ResetLabelRecord> = emptyList(),
)

fun ResetRequest.event(
	outcome: ResetOutcome,
	appliedMonotonicNs: Long? = null,
	referenceValid: Boolean = true,
	failureReason: String? = null,
	labels: List<ResetLabelRecord> = emptyList(),
) = ResetEvent(
	requestId = requestId,
	kind = kind,
	outcome = outcome,
	source = source,
	requestMonotonicNs = requestMonotonicNs,
	appliedMonotonicNs = appliedMonotonicNs,
	scheduledDelayMs = scheduledDelayMs,
	bodyParts = bodyParts,
	referenceValid = referenceValid,
	failureReason = failureReason,
	labels = labels,
)

object ResetLabelCalculator {
	private const val EPSILON = 1e-6f

	fun buildLabelRecord(
		trackerId: Int,
		sessionTrackerId: String? = null,
		trackerPosition: TrackerPosition?,
		kind: ResetKind,
		preState: TrackerResetStateSnapshot,
		postState: TrackerResetStateSnapshot,
		hmdPre: TrackerResetStateSnapshot?,
		hmdPost: TrackerResetStateSnapshot?,
		requestMonotonicNs: Long,
		applyMonotonicNs: Long,
		preStartFrame: Long = 0L,
		preEndFrame: Long = 0L,
		postStartFrame: Long = 0L,
		postEndFrame: Long = 0L,
		requestId: String? = null,
	): ResetLabelRecord {
		val (correction, yaw) = computeCorrection(preState.calibratedPreAiOrientation, postState.calibratedPreAiOrientation)
		val hmdValid = isValidHmdReference(hmdPre, hmdPost)
                val flags = computeQualityFlags(
                        hmdValid = hmdValid,
                        preState = preState,
			postState = postState,
			targetQuat = correction,
			packetGap = postState.packetGapCount > preState.packetGapCount,
			reconnectOrReassigned = preState.status != postState.status || preState.trackerPosition != postState.trackerPosition,
		)
		val axisMask = when (kind) {
			ResetKind.YAW -> AXIS_MASK_YAW
			ResetKind.FULL -> AXIS_MASK_FULL
			ResetKind.MOUNTING -> AXIS_MASK_MOUNTING
		}
		val policy = ResetSupervisionPolicy.evaluate(kind, flags)
		return ResetLabelRecord(
			trackerId = trackerId,
			sessionTrackerId = sessionTrackerId,
			trackerPosition = trackerPosition,
			domain = kind,
			correction = correction,
			diagnosticYawRadians = yaw,
			axisMask = axisMask,
			preStartFrame = preStartFrame,
			preEndFrame = preEndFrame,
			postStartFrame = postStartFrame,
			postEndFrame = postEndFrame,
			qualityFlags = flags,
			preResetState = preState,
			postResetState = postState,
			hmdReferenceBefore = hmdPre?.calibratedPreAiOrientation ?: Quaternion.IDENTITY,
                        hmdReferenceAfter = hmdPost?.calibratedPreAiOrientation ?: Quaternion.IDENTITY,
                        hmdValid = hmdValid,
                        hmdSampleAgeBeforeNs = hmdPre?.sampleAgeNs ?: Long.MAX_VALUE,
                        hmdSampleAgeAfterNs = hmdPost?.sampleAgeNs ?: Long.MAX_VALUE,
			requestId = requestId,
			requestMonotonicNs = requestMonotonicNs,
			appliedMonotonicNs = applyMonotonicNs,
			trainingPolicy = policy.action,
		)
	}

	/**
	 * Computes the canonical normalized pre-AI correction: q_target = q_post_preAI * inv(q_pre_preAI).
	 * Enforces quaternion sign equivalence (canonical w >= 0) and computes diagnostic yaw.
	 */
	fun computeCorrection(qPre: Quaternion, qPost: Quaternion): Pair<Quaternion, Float> {
		val preFinite = isFiniteQuaternion(qPre)
		val postFinite = isFiniteQuaternion(qPost)
		if (!preFinite || !postFinite) {
			return Pair(Quaternion.IDENTITY, 0f)
		}

		val raw = qPost * qPre.inv()
		if (!isFiniteQuaternion(raw) || raw.lenSq() < EPSILON * EPSILON) {
			return Pair(Quaternion.IDENTITY, 0f)
		}

		var unit = raw.unit()
		// Sign equivalence: q and -q represent identical 3D rotations.
		// Canonicalize to w >= 0 (shortest arc / identity equivalence)
		if (unit.w < 0f) {
			unit = Quaternion(-unit.w, -unit.x, -unit.y, -unit.z)
		}

		val diagnosticYaw = unit.toEulerAngles(EulerOrder.YZX).y
		return Pair(unit, diagnosticYaw)
	}

	fun isFiniteQuaternion(q: Quaternion): Boolean =
		q.w.isFinite() && q.x.isFinite() && q.y.isFinite() && q.z.isFinite()

	private const val MAX_REFERENCE_AGE_NS = 250_000_000L

	fun isValidHmdReference(
		before: TrackerResetStateSnapshot?,
		after: TrackerResetStateSnapshot?,
	): Boolean = before != null &&
		after != null &&
		before.status == TrackerStatus.OK &&
		after.status == TrackerStatus.OK &&
		isFiniteQuaternion(before.calibratedPreAiOrientation) &&
		isFiniteQuaternion(after.calibratedPreAiOrientation) &&
		before.calibratedPreAiOrientation.lenSq() in 0.98f..1.02f &&
		after.calibratedPreAiOrientation.lenSq() in 0.98f..1.02f &&
		before.sampleAgeNs <= MAX_REFERENCE_AGE_NS &&
		after.sampleAgeNs <= MAX_REFERENCE_AGE_NS

	fun computeQualityFlags(
		hmdValid: Boolean,
		preState: TrackerResetStateSnapshot,
		postState: TrackerResetStateSnapshot,
		targetQuat: Quaternion,
		packetGap: Boolean = false,
		overlappingReset: Boolean = false,
		reconnectOrReassigned: Boolean = false,
		insufficientContext: Boolean = false,
		truncatedWindow: Boolean = false,
		motionThresholdLinear: Float = 2.5f,
		motionThresholdAngular: Float = 1.0f,
	): Int {
		var flags = FLAG_OK
		if (!hmdValid) {
			flags = flags or FLAG_INVALID_OR_STALE_HMD
		}
		if (!isFiniteQuaternion(preState.rawOrientation) ||
			!isFiniteQuaternion(postState.rawOrientation) ||
			!isFiniteQuaternion(preState.calibratedPreAiOrientation) ||
			!isFiniteQuaternion(postState.calibratedPreAiOrientation) ||
			!isFiniteQuaternion(preState.adjustedOrientation) ||
			!isFiniteQuaternion(postState.adjustedOrientation) ||
			!isFiniteQuaternion(targetQuat)
		) {
			flags = flags or FLAG_INVALID_QUATERNIONS
		}
		val accelMag = maxOf(preState.acceleration.len(), postState.acceleration.len())
		val angMag = maxOf(preState.angularVelocity.len(), postState.angularVelocity.len())
		if (accelMag > motionThresholdLinear || angMag > motionThresholdAngular) {
			flags = flags or FLAG_EXCESS_MOTION
		}
		if (packetGap) {
			flags = flags or FLAG_PACKET_GAPS
		}
		if (reconnectOrReassigned || preState.status != TrackerStatus.OK || postState.status != TrackerStatus.OK) {
			flags = flags or FLAG_RECONNECT_OR_REASSIGNMENT
		}
		if (overlappingReset) {
			flags = flags or FLAG_OVERLAPPING_RESETS
		}
		if (insufficientContext) {
			flags = flags or FLAG_INSUFFICIENT_CONTEXT
		}
		if (truncatedWindow) {
			flags = flags or FLAG_WINDOW_TRUNCATED
		}
		return flags
	}
}

object ResetSupervisionPolicy {
	fun evaluate(
		domain: ResetKind,
		qualityFlags: Int,
		targetTask: ResetKind = ResetKind.YAW,
	): SupervisionDecision {
		if (targetTask == ResetKind.YAW && domain == ResetKind.MOUNTING) {
			return SupervisionDecision(
				SupervisionAction.EXCLUDE,
				0.0f,
				"Mounting resets belong to mounting domain and are excluded from yaw drift supervision",
			)
		}
		if (targetTask == ResetKind.MOUNTING && domain != ResetKind.MOUNTING) {
			return SupervisionDecision(
				SupervisionAction.EXCLUDE,
				0.0f,
				"Non-mounting resets are excluded from mounting calibration training",
			)
		}
		if ((qualityFlags and FLAG_INVALID_QUATERNIONS) != 0) {
			return SupervisionDecision(
				SupervisionAction.EXCLUDE,
				0.0f,
				"Invalid or non-finite quaternions in reset snapshot",
			)
		}
		if ((qualityFlags and FLAG_INVALID_OR_STALE_HMD) != 0) {
			return SupervisionDecision(
				SupervisionAction.EXCLUDE,
				0.0f,
				"HMD reference was invalid or stale during reset",
			)
		}
		if ((qualityFlags and FLAG_OVERLAPPING_RESETS) != 0) {
			return SupervisionDecision(
				SupervisionAction.EXCLUDE,
				0.0f,
				"Reset overlaps with another reset in surrounding window",
			)
		}
		if ((qualityFlags and FLAG_RECONNECT_OR_REASSIGNMENT) != 0) {
			return SupervisionDecision(
				SupervisionAction.EXCLUDE,
				0.0f,
				"Tracker disconnected or was reassigned during reset window",
			)
		}
		if ((qualityFlags and FLAG_EXCESS_MOTION) != 0) {
			return SupervisionDecision(
				SupervisionAction.DOWNWEIGHT,
				0.25f,
				"Excessive player motion detected during reset window",
			)
		}
		if ((qualityFlags and FLAG_PACKET_GAPS) != 0) {
			return SupervisionDecision(
				SupervisionAction.DOWNWEIGHT,
				0.5f,
				"Packet gaps detected in reset context window",
			)
		}
		if ((qualityFlags and (FLAG_INSUFFICIENT_CONTEXT or FLAG_WINDOW_TRUNCATED)) != 0) {
			return SupervisionDecision(
				SupervisionAction.DOWNWEIGHT,
				0.5f,
				"Context window truncated at session boundary or insufficient frames",
			)
		}
		if (domain == ResetKind.FULL && targetTask == ResetKind.YAW) {
			return SupervisionDecision(
				SupervisionAction.DOWNWEIGHT,
				0.75f,
				"Full reset label applied to yaw drift task",
			)
		}
		return SupervisionDecision(
			SupervisionAction.INCLUDE,
			1.0f,
			"Clean reset supervision label",
		)
	}
}
