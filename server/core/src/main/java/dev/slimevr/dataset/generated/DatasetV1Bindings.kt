// Generated-style bindings for nekovr_dataset_v1.fbs.
// Keep field numbers append-only and regenerate alongside the Python binding.
package dev.slimevr.dataset.generated

import com.google.flatbuffers.FlatBufferBuilder
import dev.slimevr.dataset.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

object DatasetV1Bindings {
	const val IDENTIFIER = "NVRD"
	const val RECORD_HEADER = 0
	const val RECORD_ROSTER = 1
	const val RECORD_FRAMES = 2
	const val RECORD_EVENTS = 3
	const val RECORD_FOOTER = 4

	private fun offsets(builder: FlatBufferBuilder, values: IntArray): Int {
		builder.startVector(4, values.size, 4)
		for (index in values.indices.reversed()) builder.addOffset(values[index])
		return builder.endVector()
	}

	private fun ints(builder: FlatBufferBuilder, values: Collection<Int>): Int {
		builder.startVector(4, values.size, 4)
		for (value in values.toList().asReversed()) builder.addInt(value)
		return builder.endVector()
	}

	private fun bytes(builder: FlatBufferBuilder, values: Collection<Int>): Int {
		builder.startVector(1, values.size, 1)
		for (value in values.toList().asReversed()) builder.addByte(value.toByte())
		return builder.endVector()
	}

	private fun floats(builder: FlatBufferBuilder, values: Collection<Float>): Int {
		builder.startVector(4, values.size, 4)
		for (value in values.toList().asReversed()) builder.addFloat(value)
		return builder.endVector()
	}

	private fun quat(builder: FlatBufferBuilder, value: QuaternionSample, checkedHalf: Boolean): Int {
		builder.startTable(4)
		if (checkedHalf) {
			val norm = value.x * value.x + value.y * value.y + value.z * value.z + value.w * value.w
			require(norm.isFinite() && norm in 0.98f..1.02f) { "quaternion must be finite and normalized" }
			builder.addShort(3, FP16BinaryPacker.encodeFinite(value.w, -1f, 1f, "quaternion.w").toShort(), 0)
			builder.addShort(2, FP16BinaryPacker.encodeFinite(value.z, -1f, 1f, "quaternion.z").toShort(), 0)
			builder.addShort(1, FP16BinaryPacker.encodeFinite(value.y, -1f, 1f, "quaternion.y").toShort(), 0)
			builder.addShort(0, FP16BinaryPacker.encodeFinite(value.x, -1f, 1f, "quaternion.x").toShort(), 0)
		} else {
			builder.addFloat(3, value.w, 1.0)
			builder.addFloat(2, value.z, 0.0)
			builder.addFloat(1, value.y, 0.0)
			builder.addFloat(0, value.x, 0.0)
		}
		return builder.endTable()
	}

	private fun vec(builder: FlatBufferBuilder, value: Vector3Sample, limit: Float, checkedHalf: Boolean): Int {
		builder.startTable(3)
		if (checkedHalf) {
			builder.addShort(2, FP16BinaryPacker.encodeFinite(value.z, -limit, limit, "vector.z").toShort(), 0)
			builder.addShort(1, FP16BinaryPacker.encodeFinite(value.y, -limit, limit, "vector.y").toShort(), 0)
			builder.addShort(0, FP16BinaryPacker.encodeFinite(value.x, -limit, limit, "vector.x").toShort(), 0)
		} else {
			builder.addFloat(2, value.z, 0.0)
			builder.addFloat(1, value.y, 0.0)
			builder.addFloat(0, value.x, 0.0)
		}
		return builder.endTable()
	}

	fun header(
		sessionId: String,
		createdUtc: String,
		applicationVersion: String,
		applicationCommit: String,
		profile: CollectionProfile,
	): ByteArray {
		val builder = FlatBufferBuilder(16 * 1024)
		val channelOffsets = TelemetryChannelRegistry.channels.map { channel ->
			val name = builder.createString(channel.name)
			val unit = builder.createString(channel.unit)
			val frame = builder.createString(channel.coordinateFrame)
			val cadence = builder.createString(channel.cadence)
			val precision = builder.createString(channel.precision)
			val provenance = bytes(builder, channel.allowedProvenance.map { it.ordinal })
			builder.startTable(8)
			builder.addOffset(7, provenance, 0)
			builder.addByte(6, channel.minimumProfile.ordinal.toByte(), 0)
			builder.addOffset(5, precision, 0)
			builder.addOffset(4, cadence, 0)
			builder.addOffset(3, frame, 0)
			builder.addOffset(2, unit, 0)
			builder.addOffset(1, name, 0)
			builder.addInt(0, channel.id, 0)
			builder.endTable()
		}.toIntArray()
		val channels = offsets(builder, channelOffsets)
		val id = builder.createString(sessionId)
		val utc = builder.createString(createdUtc)
		val version = builder.createString(applicationVersion)
		val commit = builder.createString(applicationCommit)
		builder.startTable(9)
		builder.addOffset(8, channels, 0)
		builder.addShort(7, CANONICAL_SAMPLE_RATE_HZ.toShort(), 50)
		builder.addByte(6, profile.ordinal.toByte(), 0)
		builder.addOffset(5, commit, 0)
		builder.addOffset(4, version, 0)
		builder.addOffset(3, utc, 0)
		builder.addOffset(2, id, 0)
		builder.addShort(1, DATASET_SCHEMA_MINOR.toShort(), 0)
		builder.addShort(0, DATASET_SCHEMA_MAJOR.toShort(), 1)
		val payload = builder.endTable()
		return record(builder, RECORD_HEADER, 0, header = payload)
	}

	fun roster(entries: List<SessionTrackerMetadata>, revision: Int = 0, sequence: Long = 1): ByteArray {
		val builder = FlatBufferBuilder(16 * 1024)
		val entryOffsets = entries.map { entry ->
			val strings = listOf(
				entry.sessionTrackerId,
				entry.bodyRole,
				entry.imuType,
				entry.transport,
				entry.boardType,
				entry.mcuType,
				entry.firmwareVersion,
				entry.manufacturer,
				entry.initialCalibration,
				entry.pseudonymousDeviceId ?: "",
			).map(builder::createString)
			val capabilities = ints(builder, entry.capabilities)
			builder.startTable(12)
			builder.addOffset(11, strings[9], 0)
			builder.addOffset(10, strings[8], 0)
			builder.addOffset(9, capabilities, 0)
			builder.addOffset(8, strings[7], 0)
			builder.addOffset(7, strings[6], 0)
			builder.addOffset(6, strings[5], 0)
			builder.addOffset(5, strings[4], 0)
			builder.addOffset(4, strings[3], 0)
			builder.addOffset(3, strings[2], 0)
			builder.addOffset(2, strings[1], 0)
			builder.addInt(1, entry.deviceLocalTrackerNumber, 0)
			builder.addOffset(0, strings[0], 0)
			builder.endTable()
		}.toIntArray()
		val entriesVector = offsets(builder, entryOffsets)
		builder.startTable(2)
		builder.addOffset(1, entriesVector, 0)
		builder.addInt(0, revision, 0)
		return record(builder, RECORD_ROSTER, sequence, roster = builder.endTable())
	}

	private fun nativeChannel(builder: FlatBufferBuilder, value: NativeChannelSample): Int {
		val vector = if (value.values.isEmpty()) 0 else floats(builder, value.values)
		val text = value.textValue?.let(builder::createString) ?: 0
		builder.startTable(7)
		builder.addByte(6, value.provenance.ordinal.toByte(), 0)
		builder.addByte(5, value.validity.ordinal.toByte(), 0)
		builder.addOffset(4, text, 0)
		builder.addLong(3, value.integerValue ?: 0, 0)
		builder.addOffset(2, vector, 0)
		builder.addLong(1, value.monotonicNs, 0)
		builder.addInt(0, value.channelId, 0)
		return builder.endTable()
	}

	private fun correction(builder: FlatBufferBuilder, correction: CorrectionTelemetry): Int {
		val prediction = quat(builder, correction.prediction, false)
		val applied = quat(builder, correction.appliedCorrection, false)
		val rejection = correction.rejectionReason?.let(builder::createString) ?: 0
		val hash = correction.modelHash?.let(builder::createString) ?: 0
		val provider = correction.provider?.let(builder::createString) ?: 0
		builder.startTable(10)
		builder.addByte(9, correction.provenance.ordinal.toByte(), 0)
		builder.addLong(8, correction.latencyMicros ?: 0, 0)
		builder.addBoolean(7, correction.historyValid, false)
		builder.addInt(6, correction.slot, -1)
		builder.addOffset(5, provider, 0)
		builder.addOffset(4, hash, 0)
		builder.addOffset(3, rejection, 0)
		builder.addBoolean(2, correction.applied, false)
		builder.addOffset(1, applied, 0)
		builder.addOffset(0, prediction, 0)
		return builder.endTable()
	}

	private fun tracker(builder: FlatBufferBuilder, sample: TrackerFrameSample): Int {
		val id = builder.createString(sample.sessionTrackerId)
		val raw = quat(builder, sample.rawOrientation, true)
		val calibrated = quat(builder, sample.calibratedPreAiOrientation, true)
		val final = quat(builder, sample.finalOrientation, true)
		val rawAccel = vec(builder, sample.rawAcceleration, 128f, true)
		val linearAccel = vec(builder, sample.linearAcceleration, 128f, true)
		val angular = vec(builder, sample.angularVelocity, 64f, true)
		val magnetic = vec(builder, sample.magneticVector, 4096f, true)
		val status = builder.createString(sample.trackerStatus)
		val correction = correction(builder, sample.correction)
		val nativeOffsets = sample.nativeChannels.map { nativeChannel(builder, it) }.toIntArray()
		val native = if (nativeOffsets.isEmpty()) 0 else offsets(builder, nativeOffsets)
		builder.startTable(18)
		builder.addOffset(17, native, 0)
		builder.addOffset(16, correction, 0)
		builder.addLong(15, sample.sampleAgeNs, 0)
		builder.addLong(14, sample.sampleSequence, 0)
		builder.addOffset(13, status, 0)
		builder.addByte(12, sample.driftProvenance.ordinal.toByte(), 0)
		builder.addByte(11, sample.angularVelocityProvenance.ordinal.toByte(), 0)
		builder.addByte(10, sample.angularVelocityValidity.ordinal.toByte(), 0)
		builder.addByte(9, sample.accelerationValidity.ordinal.toByte(), 0)
		builder.addByte(8, sample.orientationValidity.ordinal.toByte(), 0)
		builder.addOffset(7, magnetic, 0)
		builder.addOffset(6, angular, 0)
		builder.addOffset(5, linearAccel, 0)
		builder.addOffset(4, rawAccel, 0)
		builder.addOffset(3, final, 0)
		builder.addOffset(2, calibrated, 0)
		builder.addOffset(1, raw, 0)
		builder.addOffset(0, id, 0)
		return builder.endTable()
	}

	private fun reference(builder: FlatBufferBuilder, sample: ReferenceFrameSample): Int {
		val orientation = quat(builder, sample.orientation, false)
		val position = vec(builder, sample.position, 1000f, false)
		builder.startTable(4)
		builder.addLong(3, sample.sampleAgeNs, 0)
		builder.addByte(2, sample.validity.ordinal.toByte(), 0)
		builder.addOffset(1, position, 0)
		builder.addOffset(0, orientation, 0)
		return builder.endTable()
	}

	private fun activity(builder: FlatBufferBuilder, sample: ActivitySample): Int {
		builder.startTable(5)
		builder.addByte(4, sample.provenance.ordinal.toByte(), 0)
		builder.addLong(3, sample.endFrame, 0)
		builder.addLong(2, sample.startFrame, 0)
		builder.addFloat(1, sample.confidence, 0.0)
		builder.addByte(0, sample.type.ordinal.toByte(), 0)
		return builder.endTable()
	}

	private fun frame(builder: FlatBufferBuilder, frame: SessionFrame): Int {
		val hmd = reference(builder, frame.hmd)
		val trackers = offsets(builder, frame.trackers.map { tracker(builder, it) }.toIntArray())
		val context = if (frame.contextSamples.isEmpty()) 0 else offsets(builder, frame.contextSamples.map { tracker(builder, it) }.toIntArray())
		val activity = activity(builder, frame.activity)
		builder.startTable(7)
		builder.addOffset(6, activity, 0)
		builder.addOffset(5, context, 0)
		builder.addOffset(4, trackers, 0)
		builder.addOffset(3, hmd, 0)
		builder.addLong(2, frame.deltaNs, 0)
		builder.addLong(1, frame.monotonicNs, 0)
		builder.addLong(0, frame.frameIndex, 0)
		return builder.endTable()
	}

	fun frames(frames: List<SessionFrame>, sequence: Long): ByteArray {
		require(frames.isNotEmpty()) { "frame batch must not be empty" }
		val builder = FlatBufferBuilder(64 * 1024)
		val frameVector = offsets(builder, frames.map { frame(builder, it) }.toIntArray())
		builder.startTable(2)
		builder.addOffset(1, frameVector, 0)
		builder.addLong(0, frames.first().frameIndex, 0)
		return record(builder, RECORD_FRAMES, sequence, frames = builder.endTable())
	}

	private fun resetLabel(builder: FlatBufferBuilder, label: DatasetResetLabel): Int {
		val trackerId = builder.createString(label.sessionTrackerId)
		val correction = quat(builder, label.correction, false)
		val requestId = label.requestId?.let(builder::createString) ?: 0
		val resetDomain = builder.createString(label.domain)
		val rawBefore = quat(builder, label.rawOrientationBefore, false)
		val rawAfter = quat(builder, label.rawOrientationAfter, false)
		val preAiBefore = quat(builder, label.calibratedPreAiBefore, false)
		val preAiAfter = quat(builder, label.calibratedPreAiAfter, false)
		val attachBefore = quat(builder, label.attachmentRotBefore, false)
		val attachAfter = quat(builder, label.attachmentRotAfter, false)
		val mountBefore = quat(builder, label.mountingRotBefore, false)
		val mountAfter = quat(builder, label.mountingRotAfter, false)
		val yawBefore = quat(builder, label.yawRotBefore, false)
		val yawAfter = quat(builder, label.yawRotAfter, false)
		val hmdBefore = quat(builder, label.hmdReferenceBefore, false)
		val hmdAfter = quat(builder, label.hmdReferenceAfter, false)
		val trainingPolicy = builder.createString(label.trainingPolicy)
		val gyroBefore = quat(builder, label.gyroFixBefore, false)
		val gyroAfter = quat(builder, label.gyroFixAfter, false)
		val mountFixBefore = quat(builder, label.mountRotFixBefore, false)
		val mountFixAfter = quat(builder, label.mountRotFixAfter, false)
		val tposeBefore = quat(builder, label.tposeDownFixBefore, false)
		val tposeAfter = quat(builder, label.tposeDownFixAfter, false)
		val constraintBefore = quat(builder, label.constraintFixBefore, false)
		val constraintAfter = quat(builder, label.constraintFixAfter, false)
		val bodyRole = builder.createString(label.bodyRole)

		builder.startTable(41)
		builder.addLong(40, label.hmdSampleAgeAfterNs, 0L)
		builder.addLong(39, label.hmdSampleAgeBeforeNs, 0L)
		builder.addOffset(38, bodyRole, 0)
		builder.addLong(37, label.calibrationEpoch, 0L)
		builder.addOffset(36, constraintAfter, 0)
		builder.addOffset(35, constraintBefore, 0)
		builder.addOffset(34, tposeAfter, 0)
		builder.addOffset(33, tposeBefore, 0)
		builder.addOffset(32, mountFixAfter, 0)
		builder.addOffset(31, mountFixBefore, 0)
		builder.addOffset(30, gyroAfter, 0)
		builder.addOffset(29, gyroBefore, 0)
		builder.addOffset(28, trainingPolicy, 0)
		builder.addInt(27, label.resetEpoch.toInt(), 0)
		builder.addBoolean(26, label.hmdValid, true)
		builder.addOffset(25, hmdAfter, 0)
		builder.addOffset(24, hmdBefore, 0)
		builder.addOffset(23, yawAfter, 0)
		builder.addOffset(22, yawBefore, 0)
		builder.addOffset(21, mountAfter, 0)
		builder.addOffset(20, mountBefore, 0)
		builder.addOffset(19, attachAfter, 0)
		builder.addOffset(18, attachBefore, 0)
		builder.addOffset(17, preAiAfter, 0)
		builder.addOffset(16, preAiBefore, 0)
		builder.addOffset(15, rawAfter, 0)
		builder.addOffset(14, rawBefore, 0)
		builder.addOffset(13, resetDomain, 0)
		builder.addLong(12, label.appliedMonotonicNs, 0L)
		builder.addLong(11, label.requestMonotonicNs, 0L)
		builder.addOffset(10, requestId, 0)
		builder.addInt(9, label.qualityFlags, 0)
		builder.addLong(8, label.postEndFrame, 0)
		builder.addLong(7, label.postStartFrame, 0)
		builder.addLong(6, label.preEndFrame, 0)
		builder.addLong(5, label.preStartFrame, 0)
		builder.addByte(4, label.axisMask, 0)
		builder.addFloat(3, label.diagnosticYawRadians, 0.0)
		builder.addOffset(2, correction, 0)
		builder.addOffset(1, trackerId, 0)
		builder.addLong(0, label.eventIndex, 0)
		return builder.endTable()
	}

	fun events(
		events: List<DatasetEvent>,
		resetLabels: List<DatasetResetLabel> = emptyList(),
		sequence: Long,
	): ByteArray {
		val builder = FlatBufferBuilder(16 * 1024)
		val eventOffsets = events.map { event ->
			val tracker = event.sessionTrackerId?.let(builder::createString) ?: 0
			val old = event.oldValue?.let(builder::createString) ?: 0
			val new = event.newValue?.let(builder::createString) ?: 0
			val detail = event.detail?.let(builder::createString) ?: 0
			val requestId = event.requestId?.let(builder::createString) ?: 0
			val outcome = event.resetOutcome?.let(builder::createString) ?: 0
			val source = event.resetSource?.let(builder::createString) ?: 0
			val kind = event.resetKind?.let(builder::createString) ?: 0
			val partsOffsets = event.affectedBodyParts.map(builder::createString).toIntArray()
			val partsVector = if (partsOffsets.isNotEmpty()) offsets(builder, partsOffsets) else 0

			val type = listOf("CONNECT", "DISCONNECT", "ASSIGNMENT", "CALIBRATION", "CAPABILITY_CHANGE", "GAP", "RECORDING_MARKER", "RESET", "MODEL_CHANGE")
				.indexOf(event.type).coerceAtLeast(6)
			builder.startTable(15)
			builder.addOffset(14, partsVector, 0)
			builder.addOffset(13, kind, 0)
			builder.addOffset(12, source, 0)
			builder.addOffset(11, outcome, 0)
			builder.addLong(10, event.eventIndex, 0L)
			builder.addLong(9, event.appliedMonotonicNs, 0L)
			builder.addLong(8, event.requestMonotonicNs, 0L)
			builder.addOffset(7, requestId, 0)
			builder.addOffset(6, detail, 0)
			builder.addOffset(5, new, 0)
			builder.addOffset(4, old, 0)
			builder.addOffset(3, tracker, 0)
			builder.addLong(2, event.frameIndex, 0)
			builder.addLong(1, event.monotonicNs, 0)
			builder.addByte(0, type.toByte(), 0)
			builder.endTable()
		}.toIntArray()
		val labelOffsets = resetLabels.map { resetLabel(builder, it) }.toIntArray()
		val eventVector = if (eventOffsets.isEmpty()) 0 else offsets(builder, eventOffsets)
		val labelVector = if (labelOffsets.isEmpty()) 0 else offsets(builder, labelOffsets)
		builder.startTable(2)
		builder.addOffset(1, labelVector, 0)
		builder.addOffset(0, eventVector, 0)
		return record(builder, RECORD_EVENTS, sequence, events = builder.endTable())
	}

	fun footer(endedNs: Long, durationNs: Long, counters: DatasetQualityCounters, telemetrySha256: String, complete: Boolean, sequence: Long): ByteArray {
		val builder = FlatBufferBuilder(2048)
		builder.startTable(10)
		builder.addLong(9, counters.packetCorrupt, 0)
		builder.addLong(8, counters.packetDuplicates, 0)
		builder.addLong(7, counters.packetReordered, 0)
		builder.addLong(6, counters.packetGaps, 0)
		builder.addInt(5, counters.queueHighWatermark, 0)
		builder.addLong(4, counters.invalidSamples, 0)
		builder.addLong(3, counters.gapEvents, 0)
		builder.addLong(2, counters.droppedFrames, 0)
		builder.addLong(1, counters.writtenFrames, 0)
		builder.addLong(0, counters.sampledFrames, 0)
		val quality = builder.endTable()
		val checksum = builder.createString(telemetrySha256)
		builder.startTable(5)
		builder.addBoolean(4, complete, false)
		builder.addOffset(3, checksum, 0)
		builder.addOffset(2, quality, 0)
		builder.addLong(1, durationNs, 0)
		builder.addLong(0, endedNs, 0)
		return record(builder, RECORD_FOOTER, sequence, footer = builder.endTable())
	}

	private fun record(
		builder: FlatBufferBuilder,
		type: Int,
		sequence: Long,
		header: Int = 0,
		roster: Int = 0,
		frames: Int = 0,
		events: Int = 0,
		footer: Int = 0,
	): ByteArray {
		builder.startTable(7)
		builder.addOffset(6, footer, 0)
		builder.addOffset(5, events, 0)
		builder.addOffset(4, frames, 0)
		builder.addOffset(3, roster, 0)
		builder.addOffset(2, header, 0)
		builder.addLong(1, sequence, 0)
		builder.addByte(0, type.toByte(), 0)
		val root = builder.endTable()
		builder.finish(root, IDENTIFIER)
		return builder.sizedByteArray()
	}
}

data class DatasetRecordSummary(
	val type: Int,
	val sequence: Long,
	val schemaMajor: Int? = null,
	val schemaMinor: Int? = null,
	val sessionId: String? = null,
	val header: DatasetFileHeader? = null,
	val roster: DatasetTrackerRoster? = null,
	val frames: List<SessionFrame> = emptyList(),
	val events: List<DatasetEvent> = emptyList(),
	val resetLabels: List<DatasetResetLabel> = emptyList(),
	val footer: DatasetFooter? = null,
)

private class FbTable(private val buffer: ByteBuffer, private val table: Int) {
	private fun field(index: Int): Int {
		val vtable = table - buffer.getInt(table)
		val length = buffer.getShort(vtable).toInt() and 0xffff
		val entry = vtable + 4 + index * 2
		if (entry + 2 > vtable + length) return 0
		val offset = buffer.getShort(entry).toInt() and 0xffff
		return if (offset == 0) 0 else table + offset
	}

	fun byte(index: Int, default: Int = 0): Int = field(index).let { if (it == 0) default else buffer.get(it).toInt() and 0xff }
	fun ushort(index: Int, default: Int = 0): Int = field(index).let { if (it == 0) default else buffer.getShort(it).toInt() and 0xffff }
	fun int(index: Int, default: Int = 0): Int = field(index).let { if (it == 0) default else buffer.getInt(it) }
	fun long(index: Int, default: Long = 0): Long = field(index).let { if (it == 0) default else buffer.getLong(it) }
	fun float(index: Int, default: Float = 0f): Float = field(index).let { if (it == 0) default else buffer.getFloat(it) }
	fun bool(index: Int): Boolean = byte(index) != 0
	fun table(index: Int): FbTable? = field(index).takeIf { it != 0 }?.let { FbTable(buffer, it + buffer.getInt(it)) }
	fun string(index: Int): String? = field(index).takeIf { it != 0 }?.let { location ->
		val start = location + buffer.getInt(location)
		val length = buffer.getInt(start)
		val bytes = ByteArray(length)
		val duplicate = buffer.duplicate()
		duplicate.position(start + 4)
		duplicate.get(bytes)
		bytes.toString(Charsets.UTF_8)
	}

	fun hasField(index: Int): Boolean = field(index) != 0

	fun vectorLength(index: Int): Int = field(index).takeIf { it != 0 }?.let { location ->
		val vector = location + buffer.getInt(location)
		buffer.getInt(vector)
	} ?: 0

	fun vectorTable(index: Int, element: Int): FbTable {
		val location = field(index)
		require(location != 0) { "missing vector field $index" }
		val vector = location + buffer.getInt(location)
		require(element in 0 until buffer.getInt(vector)) { "vector index out of range" }
		val slot = vector + 4 + element * 4
		return FbTable(buffer, slot + buffer.getInt(slot))
	}

	fun vectorString(index: Int, element: Int): String {
		val location = field(index)
		require(location != 0) { "missing vector field $index" }
		val vector = location + buffer.getInt(location)
		require(element in 0 until buffer.getInt(vector)) { "vector index out of range" }
		val slot = vector + 4 + element * 4
		val start = slot + buffer.getInt(slot)
		val length = buffer.getInt(start)
		val bytes = ByteArray(length)
		val duplicate = buffer.duplicate()
		duplicate.position(start + 4)
		duplicate.get(bytes)
		return bytes.toString(Charsets.UTF_8)
	}

	private fun vectorElement(index: Int, element: Int, width: Int): Int {
		val location = field(index)
		require(location != 0) { "missing vector field $index" }
		val vector = location + buffer.getInt(location)
		require(element in 0 until buffer.getInt(vector)) { "vector index out of range" }
		return vector + 4 + element * width
	}

	fun vectorByte(index: Int, element: Int): Int = buffer.get(vectorElement(index, element, 1)).toInt() and 0xff
	fun vectorInt(index: Int, element: Int): Int = buffer.getInt(vectorElement(index, element, 4))
	fun vectorFloat(index: Int, element: Int): Float = buffer.getFloat(vectorElement(index, element, 4))
}

object DatasetV1Reader {
	fun read(bytes: ByteArray): DatasetRecordSummary {
		require(bytes.size >= 8) { "FlatBuffer record is truncated" }
		require(bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) == DatasetV1Bindings.IDENTIFIER) { "Invalid dataset identifier" }
		val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
		val root = FbTable(buffer, buffer.getInt(0))
		val type = root.byte(0)
		val sequence = root.long(1)
		return when (type) {
			DatasetV1Bindings.RECORD_HEADER -> {
				val header = root.table(2) ?: error("header payload missing")
				val decoded = readHeader(header)
				DatasetRecordSummary(
					type = type,
					sequence = sequence,
					schemaMajor = decoded.schemaMajor,
					schemaMinor = decoded.schemaMinor,
					sessionId = decoded.sessionId,
					header = decoded,
				)
			}
			DatasetV1Bindings.RECORD_ROSTER -> DatasetRecordSummary(
				type = type,
				sequence = sequence,
				roster = readRoster(root.table(3) ?: error("roster payload missing")),
			)
			DatasetV1Bindings.RECORD_FRAMES -> DatasetRecordSummary(type = type, sequence = sequence, frames = readFrames(root.table(4) ?: error("frame payload missing")))
			DatasetV1Bindings.RECORD_EVENTS -> {
				val batch = root.table(5) ?: error("event payload missing")
				DatasetRecordSummary(
					type = type,
					sequence = sequence,
					events = readEvents(batch),
					resetLabels = readResetLabels(batch),
				)
			}
			DatasetV1Bindings.RECORD_FOOTER -> DatasetRecordSummary(
				type = type,
				sequence = sequence,
				footer = readFooter(root.table(6) ?: error("footer payload missing")),
			)
			else -> error("unknown dataset record type $type")
		}
	}

	private inline fun <reified T : Enum<T>> enumValue(value: Int, field: String): T {
		val entries = enumValues<T>()
		require(value in entries.indices) { "$field has unknown ordinal $value" }
		return entries[value]
	}

	private fun readHeader(table: FbTable): DatasetFileHeader {
		val channels = List(table.vectorLength(8)) { index ->
			val channel = table.vectorTable(8, index)
			TelemetryChannel(
				id = channel.int(0),
				name = channel.string(1) ?: "",
				unit = channel.string(2) ?: "",
				coordinateFrame = channel.string(3) ?: "",
				cadence = channel.string(4) ?: "",
				precision = channel.string(5) ?: "",
				minimumProfile = enumValue(channel.byte(6), "channel.required_profile"),
				allowedProvenance = List(channel.vectorLength(7)) { provenanceIndex ->
					enumValue<ChannelProvenance>(channel.vectorByte(7, provenanceIndex), "channel.allowed_provenance")
				}.toSet(),
			)
		}
		return DatasetFileHeader(
			schemaMajor = table.ushort(0, 1),
			schemaMinor = table.ushort(1),
			sessionId = table.string(2) ?: "",
			createdUtc = table.string(3) ?: "",
			applicationVersion = table.string(4) ?: "",
			applicationCommit = table.string(5) ?: "",
			profile = enumValue(table.byte(6), "header.profile"),
			canonicalRateHz = table.ushort(7, 50),
			channels = channels,
		)
	}

	private fun readRoster(table: FbTable): DatasetTrackerRoster = DatasetTrackerRoster(
		revision = table.int(0),
		trackers = List(table.vectorLength(1)) { index ->
			val tracker = table.vectorTable(1, index)
			SessionTrackerMetadata(
				sessionTrackerId = tracker.string(0) ?: "",
				deviceLocalTrackerNumber = tracker.int(1),
				bodyRole = tracker.string(2) ?: "",
				imuType = tracker.string(3) ?: "",
				transport = tracker.string(4) ?: "",
				boardType = tracker.string(5) ?: "",
				mcuType = tracker.string(6) ?: "",
				firmwareVersion = tracker.string(7) ?: "",
				manufacturer = tracker.string(8) ?: "",
				capabilities = List(tracker.vectorLength(9)) { tracker.vectorInt(9, it) }.toSet(),
				initialCalibration = tracker.string(10) ?: "",
				pseudonymousDeviceId = tracker.string(11)?.takeIf(String::isNotEmpty),
			)
		},
	)

	private fun readFooter(table: FbTable): DatasetFooter {
		val counters = table.table(2) ?: error("footer quality counters missing")
		return DatasetFooter(
			endedMonotonicNs = table.long(0),
			durationNs = table.long(1),
			counters = DatasetQualityCounters(
				sampledFrames = counters.long(0),
				writtenFrames = counters.long(1),
				droppedFrames = counters.long(2),
				gapEvents = counters.long(3),
				invalidSamples = counters.long(4),
				queueHighWatermark = counters.int(5),
				packetGaps = counters.long(6),
				packetReordered = counters.long(7),
				packetDuplicates = counters.long(8),
				packetCorrupt = counters.long(9),
			),
			telemetrySha256 = table.string(3) ?: "",
			complete = table.bool(4),
		)
	}

	private fun readEvents(batch: FbTable): List<DatasetEvent> {
		val count = batch.vectorLength(0)
		return List(count) { index ->
			val table = batch.vectorTable(0, index)
			val typeIdx = table.byte(0)
			val typeNames = listOf("CONNECT", "DISCONNECT", "ASSIGNMENT", "CALIBRATION", "CAPABILITY_CHANGE", "GAP", "RECORDING_MARKER", "RESET", "MODEL_CHANGE")
			val type = if (typeIdx in typeNames.indices) typeNames[typeIdx] else "EVENT"
			val partsLen = table.vectorLength(14)
			val parts = List(partsLen) { pIdx -> table.vectorString(14, pIdx) }
			DatasetEvent(
				type = type,
				monotonicNs = table.long(1),
				frameIndex = table.long(2),
				sessionTrackerId = table.string(3),
				oldValue = table.string(4),
				newValue = table.string(5),
				detail = table.string(6),
				requestId = table.string(7),
				requestMonotonicNs = table.long(8),
				appliedMonotonicNs = table.long(9),
				eventIndex = table.long(10),
				resetOutcome = table.string(11),
				resetSource = table.string(12),
				resetKind = table.string(13),
				affectedBodyParts = parts,
			)
		}
	}

	private fun resetLabel(table: FbTable): DatasetResetLabel = DatasetResetLabel(
		eventIndex = table.long(0),
		sessionTrackerId = table.string(1) ?: "",
		correction = quat(table.table(2), false),
		diagnosticYawRadians = table.float(3),
		axisMask = table.byte(4).toByte(),
		preStartFrame = table.long(5),
		preEndFrame = table.long(6),
		postStartFrame = table.long(7),
		postEndFrame = table.long(8),
		qualityFlags = table.int(9),
		requestId = table.string(10),
		requestMonotonicNs = table.long(11),
		appliedMonotonicNs = table.long(12),
		domain = table.string(13) ?: "YAW",
		rawOrientationBefore = quat(table.table(14), false),
		rawOrientationAfter = quat(table.table(15), false),
		calibratedPreAiBefore = quat(table.table(16), false),
		calibratedPreAiAfter = quat(table.table(17), false),
		attachmentRotBefore = quat(table.table(18), false),
		attachmentRotAfter = quat(table.table(19), false),
		mountingRotBefore = quat(table.table(20), false),
		mountingRotAfter = quat(table.table(21), false),
		yawRotBefore = quat(table.table(22), false),
		yawRotAfter = quat(table.table(23), false),
		hmdReferenceBefore = quat(table.table(24), false),
		hmdReferenceAfter = quat(table.table(25), false),
		hmdValid = if (table.hasField(26)) table.bool(26) else true,
		resetEpoch = table.int(27).toLong(),
		trainingPolicy = table.string(28) ?: "INCLUDE",
		gyroFixBefore = quat(table.table(29), false),
		gyroFixAfter = quat(table.table(30), false),
		mountRotFixBefore = quat(table.table(31), false),
		mountRotFixAfter = quat(table.table(32), false),
		tposeDownFixBefore = quat(table.table(33), false),
		tposeDownFixAfter = quat(table.table(34), false),
		constraintFixBefore = quat(table.table(35), false),
		constraintFixAfter = quat(table.table(36), false),
		calibrationEpoch = table.long(37),
		bodyRole = table.string(38) ?: "UNASSIGNED",
		hmdSampleAgeBeforeNs = table.long(39),
		hmdSampleAgeAfterNs = table.long(40),
	)

	private fun readResetLabels(eventBatch: FbTable): List<DatasetResetLabel> =
		List(eventBatch.vectorLength(1)) { resetLabel(eventBatch.vectorTable(1, it)) }

	private fun quat(table: FbTable?, half: Boolean, channel: String = "quaternion"): QuaternionSample {
		if (table == null) return QuaternionSample(0f, 0f, 0f, 1f)
		val value = if (half) QuaternionSample(
			FP16BinaryPacker.decodeFinite(table.ushort(0).toShort(), -1f, 1f, "$channel.x"),
			FP16BinaryPacker.decodeFinite(table.ushort(1).toShort(), -1f, 1f, "$channel.y"),
			FP16BinaryPacker.decodeFinite(table.ushort(2).toShort(), -1f, 1f, "$channel.z"),
			FP16BinaryPacker.decodeFinite(table.ushort(3).toShort(), -1f, 1f, "$channel.w"),
		) else QuaternionSample(table.float(0), table.float(1), table.float(2), table.float(3, 1f))
		FP16BinaryPacker.requireNormalizedQuaternion(floatArrayOf(value.x, value.y, value.z, value.w), channel)
		return value
	}

	private fun vec(table: FbTable?, half: Boolean, limit: Float = FP16BinaryPacker.MAX_FINITE, channel: String = "vector"): Vector3Sample {
		if (table == null) return Vector3Sample(0f, 0f, 0f)
		val value = if (half) Vector3Sample(
			FP16BinaryPacker.decodeFinite(table.ushort(0).toShort(), -limit, limit, "$channel.x"),
			FP16BinaryPacker.decodeFinite(table.ushort(1).toShort(), -limit, limit, "$channel.y"),
			FP16BinaryPacker.decodeFinite(table.ushort(2).toShort(), -limit, limit, "$channel.z"),
		) else Vector3Sample(table.float(0), table.float(1), table.float(2))
		require(listOf(value.x, value.y, value.z).all { it.isFinite() && it in -limit..limit }) {
			"$channel must contain finite components in [-$limit, $limit]"
		}
		return value
	}

	private fun tracker(table: FbTable): TrackerFrameSample {
		val correction = table.table(16)
		val nativeChannels = List(table.vectorLength(17)) { index ->
			val native = table.vectorTable(17, index)
			val values = List(native.vectorLength(2)) { native.vectorFloat(2, it) }
			require(values.all(Float::isFinite)) { "native channel values must be finite" }
			NativeChannelSample(
				channelId = native.int(0),
				monotonicNs = native.long(1),
				values = values,
				integerValue = native.long(3).takeIf { native.hasField(3) },
				textValue = native.string(4),
				validity = enumValue(native.byte(5), "native.validity"),
				provenance = enumValue(native.byte(6), "native.provenance"),
			)
		}
		return TrackerFrameSample(
			sessionTrackerId = table.string(0) ?: "",
			rawOrientation = quat(table.table(1), true, "raw_orientation"),
			calibratedPreAiOrientation = quat(table.table(2), true, "calibrated_pre_ai_orientation"),
			finalOrientation = quat(table.table(3), true, "final_orientation"),
			rawAcceleration = vec(table.table(4), true, 128f, "raw_acceleration"),
			linearAcceleration = vec(table.table(5), true, 128f, "linear_acceleration"),
			angularVelocity = vec(table.table(6), true, 64f, "angular_velocity"),
			magneticVector = vec(table.table(7), true, 4096f, "magnetic_vector"),
			orientationValidity = enumValue(table.byte(8), "tracker.orientation_validity"),
			accelerationValidity = enumValue(table.byte(9), "tracker.acceleration_validity"),
			angularVelocityValidity = enumValue(table.byte(10), "tracker.angular_velocity_validity"),
			angularVelocityProvenance = enumValue(table.byte(11), "tracker.angular_velocity_provenance"),
			driftProvenance = enumValue(table.byte(12), "tracker.drift_provenance"),
			trackerStatus = table.string(13) ?: "UNKNOWN",
			sampleSequence = table.long(14),
			sampleAgeNs = table.long(15),
			correction = CorrectionTelemetry(
				prediction = quat(correction?.table(0), false),
				appliedCorrection = quat(correction?.table(1), false),
				applied = correction?.bool(2) ?: false,
				rejectionReason = correction?.string(3),
				modelHash = correction?.string(4),
				provider = correction?.string(5),
				slot = correction?.int(6, -1) ?: -1,
				historyValid = correction?.bool(7) ?: false,
				latencyMicros = correction?.let { value -> value.long(8).takeIf { value.hasField(8) } },
				provenance = enumValue(correction?.byte(9) ?: 0, "correction.provenance"),
			),
			nativeChannels = nativeChannels,
		)
	}

	private fun readFrames(batch: FbTable): List<SessionFrame> = List(batch.vectorLength(1)) { index ->
		val frame = batch.vectorTable(1, index)
		val hmd = frame.table(3)
		val trackers = List(frame.vectorLength(4)) { tracker(frame.vectorTable(4, it)) }
		val context = List(frame.vectorLength(5)) { tracker(frame.vectorTable(5, it)) }
		val activity = frame.table(6)
		SessionFrame(
			frameIndex = frame.long(0),
			monotonicNs = frame.long(1),
			deltaNs = frame.long(2),
			hmd = ReferenceFrameSample(
				orientation = quat(hmd?.table(0), false),
				position = vec(hmd?.table(1), false, 1000f, "hmd.position"),
				validity = enumValue(hmd?.byte(2) ?: 0, "hmd.validity"),
				sampleAgeNs = hmd?.long(3) ?: 0,
			),
			trackers = trackers,
			contextSamples = context,
			activity = ActivitySample(
				type = enumValue(activity?.byte(0) ?: 0, "activity.type"),
				confidence = activity?.float(1) ?: 0f,
				startFrame = activity?.long(2) ?: frame.long(0),
				endFrame = activity?.long(3) ?: frame.long(0),
				provenance = enumValue(activity?.byte(4) ?: 0, "activity.provenance"),
			),
		).also {
			require(it.activity.confidence.isFinite() && it.activity.confidence in 0f..1f) { "activity confidence must be in [0, 1]" }
			require(it.activity.startFrame <= it.activity.endFrame) { "activity frame interval is inverted" }
		}
	}
}
