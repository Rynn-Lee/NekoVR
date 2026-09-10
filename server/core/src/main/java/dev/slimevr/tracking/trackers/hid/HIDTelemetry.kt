package dev.slimevr.tracking.trackers.hid

import dev.slimevr.tracking.trackers.Tracker
import io.github.axisangles.ktmath.Vector3

/** Shared HID/nRF capability contract used by dongle implementations. */
data class HIDTelemetryCapabilities(val channelIds: Set<Int>)

data class HIDTelemetrySample(
	val sequence: Long? = null,
	val deviceTimestamp: Long? = null,
	val rawGyro: Vector3? = null,
	val packetGaps: Long? = null,
	val packetReordered: Long? = null,
	val packetDuplicates: Long? = null,
	val packetCorrupt: Long? = null,
	val packetsReceived: Int? = null,
	val packetsLost: Int? = null,
	val packetLoss: Float? = null,
	val charging: Boolean? = null,
	val uptimeMs: Long? = null,
	val sleepState: String? = null,
)

fun Tracker.negotiateHIDTelemetry(capabilities: HIDTelemetryCapabilities) {
	telemetryCapabilities += capabilities.channelIds
}

fun Tracker.applyHIDTelemetry(sample: HIDTelemetrySample) {
	sample.sequence?.let { sampleSequence = it }
	sample.deviceTimestamp?.let { deviceTimestamp = it }
	sample.rawGyro?.let { rawAngularVelocity = it }
	sample.packetGaps?.let { packetGaps = it }
	sample.packetReordered?.let { packetReordered = it }
	sample.packetDuplicates?.let { packetDuplicates = it }
	sample.packetCorrupt?.let { packetCorrupt = it }
	sample.packetsReceived?.let { packetsReceived = it }
	sample.packetsLost?.let { packetsLost = it }
	sample.packetLoss?.let { packetLoss = it }
	sample.charging?.let { charging = it }
	sample.uptimeMs?.let { deviceUptimeMs = it }
	sample.sleepState?.let { sleepState = it }
}
