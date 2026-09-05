package dev.slimevr.dataset

import dev.slimevr.ai.DriftCorrectionResult
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import dev.slimevr.tracking.trackers.TrackerUtils
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.acos
import kotlin.math.sqrt

data class SessionPrivacyOptions(
	val consent: Boolean,
	val subjectPseudonym: String? = null,
	val hashHardwareIdentifiers: Boolean = false,
)

class SessionTrackerRegistry(
	trackers: List<Tracker>,
	private val privacy: SessionPrivacyOptions,
) {
	private val salt = ByteArray(32).also(SecureRandom()::nextBytes)
	private val ids = linkedMapOf<Int, String>()
	private val metadataByTracker = linkedMapOf<Int, SessionTrackerMetadata>()

	init {
		trackers.filter(Tracker::isImu).forEach(::register)
	}

	val initialRoster: List<SessionTrackerMetadata> = metadataByTracker.values.toList()

	val datasetPrivacy: DatasetPrivacy = DatasetPrivacy(
		consent = privacy.consent,
		subjectPseudonym = privacy.subjectPseudonym,
		identifierPolicy = if (privacy.hashHardwareIdentifiers) "HMAC_SHA256_PER_SESSION" else "OMIT",
		perSessionSaltBase64 = if (privacy.hashHardwareIdentifiers) Base64.getEncoder().encodeToString(salt) else null,
	)

	fun idFor(tracker: Tracker): String = ids[tracker.id] ?: register(tracker).sessionTrackerId

	fun idForTrackerId(trackerId: Int): String? = ids[trackerId]

	fun metadataFor(tracker: Tracker): SessionTrackerMetadata = metadataByTracker[tracker.id] ?: register(tracker)

	private fun register(tracker: Tracker): SessionTrackerMetadata {
		val sessionId = UUID.randomUUID().toString()
		ids[tracker.id] = sessionId
		val device = tracker.device
		val capabilities = linkedSetOf(1, 2, 3, 19).apply {
			if (tracker.hasAcceleration) addAll(listOf(4, 5))
			if (device?.magSupport == true) add(7)
			if (tracker.temperature != null) add(8)
			if (tracker.sampleSequence != null) add(9)
			if (tracker.packetLoss != null) add(10)
			if (tracker.signalStrength != null) add(11)
			if (tracker.ping != null) add(12)
			if (tracker.batteryLevel != null) add(13)
			addAll(tracker.telemetryCapabilities)
		}
		val rawIdentifier = device?.hardwareIdentifier?.takeUnless { it == "Unknown" }
		val pseudonymous = if (privacy.hashHardwareIdentifiers && rawIdentifier != null) hmac(rawIdentifier) else null
		return SessionTrackerMetadata(
			sessionTrackerId = sessionId,
			deviceLocalTrackerNumber = tracker.trackerNum,
			bodyRole = tracker.trackerPosition?.designation ?: "UNASSIGNED",
			imuType = tracker.imuType?.name ?: "UNKNOWN",
			transport = device?.origin?.name ?: "UNKNOWN",
			boardType = device?.boardType?.name ?: "UNKNOWN",
			mcuType = device?.mcuType?.name ?: "UNKNOWN",
			firmwareVersion = device?.firmwareVersion ?: "UNKNOWN",
			manufacturer = device?.manufacturer ?: "UNKNOWN",
			capabilities = capabilities,
			initialCalibration = if (tracker.hasCompletedRestCalibration == true) "REST_CALIBRATED" else "UNKNOWN",
			pseudonymousDeviceId = pseudonymous,
		).also { metadataByTracker[tracker.id] = it }
	}

	private fun hmac(value: String): String {
		val mac = Mac.getInstance("HmacSHA256")
		mac.init(SecretKeySpec(salt, "HmacSHA256"))
		return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
	}
}

class SessionSnapshotFactory(private val registry: SessionTrackerRegistry) {
	private data class PreviousOrientation(val timeNs: Long, val rotation: QuaternionSample)
	private data class Topology(val status: String, val role: String, val calibration: String, val capabilities: Set<Int>)

	private val previous = mutableMapOf<Int, PreviousOrientation>()
	private val topology = mutableMapOf<Int, Topology>()
	private val nativeValues = mutableMapOf<Pair<Int, Int>, Any?>()
	private var previousHmdPosition: Vector3Sample? = null

	fun snapshot(trackers: List<Tracker>, frameIndex: Long, nowNs: Long, deltaNs: Long): Pair<SessionFrame, List<DatasetEvent>> {
		val physical = trackers.filter(Tracker::isImu)
		val events = topologyEvents(physical, frameIndex, nowNs)
		val trackerSamples = physical.map { trackerSample(it, nowNs) }
		val context = trackers.filter { !it.isImu() && (it.hasRotation || it.hasPosition) }.map { contextSample(it, nowNs) }
		val hmdTracker = TrackerUtils.getTrackerForSkeleton(trackers, TrackerPosition.HEAD)
		val hmd = hmdTracker?.let { referenceSample(it, nowNs) } ?: ReferenceFrameSample(
			QuaternionSample(0f, 0f, 0f, 1f),
			Vector3Sample(0f, 0f, 0f),
			ChannelValidity.UNAVAILABLE,
			0,
		)
		val activity = deriveActivity(hmd, frameIndex, deltaNs)
		return SessionFrame(frameIndex, nowNs, deltaNs, hmd, trackerSamples, context, activity) to events
	}

	private fun referenceSample(tracker: Tracker, nowNs: Long): ReferenceFrameSample {
		val orientation = quaternion(tracker.getRotation())
		val position = vector(tracker.position)
		val valid = tracker.status.sendData && finite(orientation) && finite(position)
		return ReferenceFrameSample(
			if (finite(orientation)) normalize(orientation) else QuaternionSample(0f, 0f, 0f, 1f),
			if (finite(position)) position else Vector3Sample(0f, 0f, 0f),
			if (valid) ChannelValidity.VALID else ChannelValidity.INVALID,
			(nowNs - tracker.lastDataMonotonicNs).coerceAtLeast(0L),
		)
	}

	private fun trackerSample(tracker: Tracker, nowNs: Long): TrackerFrameSample {
		val rawValue = quaternion(tracker.getRawRotation())
		val finalValue = quaternion(tracker.getRotation())
		val preAiValue = quaternion(tracker.resetsHandler.lastPreAiRotation)
		val orientationValid = tracker.status.sendData && finite(rawValue) && normSquared(rawValue) in 0.98f..1.02f
		val raw = if (orientationValid) normalize(rawValue) else QuaternionSample(0f, 0f, 0f, 1f)
		val preAi = if (finite(preAiValue)) normalize(preAiValue) else raw
		val final = if (finite(finalValue)) normalize(finalValue) else preAi
		val rawAcceleration = vector(tracker.getRawAcceleration())
		val linearAcceleration = vector(tracker.getAcceleration())
		val accelValid = tracker.hasAcceleration && finite(rawAcceleration) && finite(linearAcceleration)
		val angular = measuredOrDerivedAngularVelocity(tracker, raw, nowNs, orientationValid)
		val magnetic = vector(tracker.getRawMagVector())
		val correction = correction(tracker.resetsHandler.lastAiCorrection)
		return TrackerFrameSample(
			sessionTrackerId = registry.idFor(tracker),
			rawOrientation = raw,
			calibratedPreAiOrientation = preAi,
			finalOrientation = final,
			rawAcceleration = if (accelValid) rawAcceleration else Vector3Sample(0f, 0f, 0f),
			linearAcceleration = if (accelValid) linearAcceleration else Vector3Sample(0f, 0f, 0f),
			angularVelocity = angular.first,
			magneticVector = if (finite(magnetic)) magnetic else Vector3Sample(0f, 0f, 0f),
			orientationValidity = if (orientationValid) ChannelValidity.VALID else ChannelValidity.INVALID,
			accelerationValidity = if (accelValid) ChannelValidity.VALID else ChannelValidity.UNAVAILABLE,
			angularVelocityValidity = if (angular.second == ChannelProvenance.UNAVAILABLE) ChannelValidity.UNAVAILABLE else ChannelValidity.VALID,
			angularVelocityProvenance = angular.second,
			driftProvenance = if (correction.applied) ChannelProvenance.MODEL_DERIVED else ChannelProvenance.UNAVAILABLE,
			trackerStatus = tracker.status.name,
			sampleSequence = tracker.sampleSequence ?: 0,
			sampleAgeNs = (nowNs - tracker.lastDataMonotonicNs).coerceAtLeast(0L),
			correction = correction,
			nativeChannels = nativeChannels(tracker, nowNs),
		)
	}

	private fun contextSample(tracker: Tracker, nowNs: Long): TrackerFrameSample {
		val rotation = quaternion(tracker.getRotation()).let { if (finite(it)) normalize(it) else QuaternionSample(0f, 0f, 0f, 1f) }
		return TrackerFrameSample(
			registry.idFor(tracker), rotation, rotation, rotation,
			Vector3Sample(0f, 0f, 0f), Vector3Sample(0f, 0f, 0f), Vector3Sample(0f, 0f, 0f), Vector3Sample(0f, 0f, 0f),
			if (tracker.status.sendData) ChannelValidity.VALID else ChannelValidity.INVALID,
			ChannelValidity.UNAVAILABLE, ChannelValidity.UNAVAILABLE,
			ChannelProvenance.UNAVAILABLE, ChannelProvenance.UNAVAILABLE, tracker.status.name,
			tracker.sampleSequence ?: 0, (nowNs - tracker.lastDataMonotonicNs).coerceAtLeast(0L), CorrectionTelemetry(),
		)
	}

	private fun measuredOrDerivedAngularVelocity(tracker: Tracker, raw: QuaternionSample, nowNs: Long, valid: Boolean): Pair<Vector3Sample, ChannelProvenance> {
		tracker.rawAngularVelocity?.let {
			val value = vector(it)
			if (finite(value) && listOf(value.x, value.y, value.z).all { component -> component in -64f..64f }) {
				previous[tracker.id] = PreviousOrientation(nowNs, raw)
				return value to ChannelProvenance.MEASURED
			}
		}
		val old = previous.put(tracker.id, PreviousOrientation(nowNs, raw))
		if (!valid || old == null) return Vector3Sample(0f, 0f, 0f) to ChannelProvenance.UNAVAILABLE
		val dt = (nowNs - old.timeNs) / 1_000_000_000.0
		if (dt !in 0.001..0.25) return Vector3Sample(0f, 0f, 0f) to ChannelProvenance.UNAVAILABLE
		val q = multiply(inverse(old.rotation), raw).let { if (it.w < 0) QuaternionSample(-it.x, -it.y, -it.z, -it.w) else it }
		val angle = 2.0 * acos(q.w.toDouble().coerceIn(-1.0, 1.0))
		val sinHalf = sqrt((1.0 - q.w.toDouble() * q.w.toDouble()).coerceAtLeast(0.0))
		if (sinHalf < 1e-6 || angle < 1e-6) return Vector3Sample(0f, 0f, 0f) to ChannelProvenance.SERVER_DERIVED
		val scale = (angle / sinHalf / dt).toFloat()
		val value = Vector3Sample(q.x * scale, q.y * scale, q.z * scale)
		return if (finite(value) && listOf(value.x, value.y, value.z).all { it in -64f..64f }) value to ChannelProvenance.SERVER_DERIVED
		else Vector3Sample(0f, 0f, 0f) to ChannelProvenance.UNAVAILABLE
	}

	private fun nativeChannels(tracker: Tracker, nowNs: Long): List<NativeChannelSample> {
		val values = listOfNotNull(
			tracker.temperature?.let { 8 to it }, tracker.sampleSequence?.let { 9 to it }, tracker.packetLoss?.let { 10 to it },
			tracker.signalStrength?.let { 11 to it }, tracker.ping?.let { 12 to it }, tracker.batteryLevel?.let { 13 to it },
			tracker.charging?.let { 14 to it }, tracker.deviceTimestamp?.let { 15 to it }, tracker.rawAngularVelocity?.let { 16 to it },
			tracker.firmwareFeatures?.let { 20 to it },
			(21 to tracker.magStatus.name), tracker.calibrationQuality?.let { 22 to it }, tracker.fusionStatus?.let { 23 to it },
			tracker.packetsReceived?.let { 24 to it }, tracker.packetsLost?.let { 25 to it },
			tracker.packetGaps.takeIf { it > 0 || 26 in tracker.telemetryCapabilities }?.let { 26 to it },
			tracker.packetReordered.takeIf { it > 0 || 27 in tracker.telemetryCapabilities }?.let { 27 to it },
			tracker.packetDuplicates.takeIf { it > 0 || 28 in tracker.telemetryCapabilities }?.let { 28 to it },
			tracker.packetCorrupt.takeIf { it > 0 || 29 in tracker.telemetryCapabilities }?.let { 29 to it },
			tracker.batteryVoltage?.let { 30 to it }, tracker.powerMode?.let { 31 to it }, tracker.deviceUptimeMs?.let { 32 to it },
			tracker.resetReason?.let { 33 to it }, (34 to tracker.tps),
		)
		return values.mapNotNull { (channel, value) ->
			val key = tracker.id to channel
			if (nativeValues.put(key, value) == value && channel !in setOf(9, 15, 16)) return@mapNotNull null
			when (value) {
				is Vector3 -> NativeChannelSample(channel, nowNs, values = listOf(value.x, value.y, value.z), validity = ChannelValidity.VALID, provenance = ChannelProvenance.MEASURED)
				is Float -> NativeChannelSample(channel, nowNs, values = listOf(value), validity = ChannelValidity.VALID, provenance = ChannelProvenance.FIRMWARE_REPORTED)
				is Number -> NativeChannelSample(channel, nowNs, integerValue = value.toLong(), validity = ChannelValidity.VALID, provenance = ChannelProvenance.FIRMWARE_REPORTED)
				is Boolean -> NativeChannelSample(channel, nowNs, integerValue = if (value) 1 else 0, validity = ChannelValidity.VALID, provenance = ChannelProvenance.FIRMWARE_REPORTED)
				is String -> NativeChannelSample(channel, nowNs, textValue = value, validity = ChannelValidity.VALID, provenance = ChannelProvenance.FIRMWARE_REPORTED)
				else -> null
			}
		}
	}

	private fun topologyEvents(trackers: List<Tracker>, frameIndex: Long, nowNs: Long): List<DatasetEvent> = buildList {
		for (tracker in trackers) {
			val metadata = registry.metadataFor(tracker)
			val current = Topology(tracker.status.name, tracker.trackerPosition?.designation ?: "UNASSIGNED", if (tracker.hasCompletedRestCalibration == true) "REST_CALIBRATED" else "UNKNOWN", metadata.capabilities)
			val old = topology.put(tracker.id, current)
			if (old == null) add(DatasetEvent("CONNECT", nowNs, frameIndex, metadata.sessionTrackerId, newValue = current.status))
			else {
				if (old.status != current.status) add(DatasetEvent(if (tracker.status == TrackerStatus.DISCONNECTED) "DISCONNECT" else "CONNECT", nowNs, frameIndex, metadata.sessionTrackerId, old.status, current.status))
				if (old.role != current.role) add(DatasetEvent("ASSIGNMENT", nowNs, frameIndex, metadata.sessionTrackerId, old.role, current.role))
				if (old.calibration != current.calibration) add(DatasetEvent("CALIBRATION", nowNs, frameIndex, metadata.sessionTrackerId, old.calibration, current.calibration))
				if (old.capabilities != current.capabilities) add(DatasetEvent("CAPABILITY_CHANGE", nowNs, frameIndex, metadata.sessionTrackerId, old.capabilities.toString(), current.capabilities.toString()))
			}
		}
	}

	private fun correction(value: DriftCorrectionResult) = CorrectionTelemetry(
		prediction = quaternion(value.prediction), appliedCorrection = quaternion(value.correction), applied = value.applied,
		rejectionReason = value.rejectionReason, modelHash = value.modelHash, provider = value.provider,
		historyValid = value.historyValid, latencyMicros = value.latencyMicros,
		provenance = if (value.applied || value.modelHash != null) ChannelProvenance.MODEL_DERIVED else ChannelProvenance.UNAVAILABLE,
	)

	private fun deriveActivity(hmd: ReferenceFrameSample, frame: Long, deltaNs: Long): ActivitySample {
		if (hmd.validity != ChannelValidity.VALID) return ActivitySample(ActivityType.UNKNOWN, 0f, frame, frame)
		val previousPosition = previousHmdPosition.also { previousHmdPosition = hmd.position }
		val speed = if (previousPosition == null || deltaNs <= 0) 0f else {
			val dx = hmd.position.x - previousPosition.x
			val dy = hmd.position.y - previousPosition.y
			val dz = hmd.position.z - previousPosition.z
			(sqrt((dx * dx + dy * dy + dz * dz).toDouble()) / (deltaNs / 1e9)).toFloat()
		}
		return when {
			hmd.position.y < 0.65f -> ActivitySample(ActivityType.LYING, 0.7f, frame, frame)
			hmd.position.y < 1.15f -> ActivitySample(ActivityType.UNKNOWN, 0.35f, frame, frame)
			speed > 1.5f -> ActivitySample(ActivityType.DANCE, 0.65f, frame, frame)
			speed > 0.3f -> ActivitySample(ActivityType.LOCOMOTION, 0.65f, frame, frame)
			speed < 0.04f -> ActivitySample(ActivityType.STATIONARY, 0.75f, frame, frame)
			else -> ActivitySample(ActivityType.STANDING, 0.65f, frame, frame)
		}
	}

	private fun quaternion(value: Quaternion) = QuaternionSample(value.x, value.y, value.z, value.w)
	private fun vector(value: Vector3) = Vector3Sample(value.x, value.y, value.z)
	private fun finite(value: Vector3Sample) = value.x.isFinite() && value.y.isFinite() && value.z.isFinite()
	private fun finite(value: QuaternionSample) = value.x.isFinite() && value.y.isFinite() && value.z.isFinite() && value.w.isFinite()
	private fun normSquared(q: QuaternionSample) = q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w
	private fun normalize(q: QuaternionSample): QuaternionSample {
		val norm = sqrt(normSquared(q).toDouble()).toFloat()
		if (!norm.isFinite() || norm < 1e-6f) return QuaternionSample(0f, 0f, 0f, 1f)
		return QuaternionSample(q.x / norm, q.y / norm, q.z / norm, q.w / norm)
	}
	private fun inverse(q: QuaternionSample) = QuaternionSample(-q.x, -q.y, -q.z, q.w)
	private fun multiply(a: QuaternionSample, b: QuaternionSample) = QuaternionSample(
		a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
		a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
		a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
		a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z,
	)
}
