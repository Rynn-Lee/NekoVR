package dev.slimevr.tracking.trackers

/** Canonical dataset channel IDs and production transport capability mappings. */
object NativeTelemetryChannels {
	const val RAW_ORIENTATION = 1
	const val CALIBRATED_ORIENTATION = 2
	const val FINAL_ORIENTATION = 3
	const val RAW_ACCELERATION = 4
	const val LINEAR_ACCELERATION = 5
	const val ANGULAR_VELOCITY = 6
	const val MAGNETIC_VECTOR = 7
	const val TEMPERATURE = 8
	const val PACKET_SEQUENCE = 9
	const val PACKET_LOSS = 10
	const val RSSI = 11
	const val PING = 12
	const val BATTERY = 13
	const val CHARGING_STATE = 14
	const val DEVICE_TIMESTAMP = 15
	const val RAW_GYRO = 16
	const val SAMPLE_AGE = 19
	const val FIRMWARE_FEATURES = 20
	const val MAGNETOMETER_STATE = 21
	const val CALIBRATION_QUALITY = 22
	const val FUSION_STATE = 23
	const val PACKETS_RECEIVED = 24
	const val PACKETS_LOST = 25
	const val PACKET_GAPS = 26
	const val PACKETS_REORDERED = 27
	const val PACKETS_DUPLICATE = 28
	const val PACKETS_CORRUPT = 29
	const val BATTERY_VOLTAGE = 30
	const val POWER_MODE = 31
	const val DEVICE_UPTIME = 32
	const val RESET_REASON = 33
	const val OBSERVED_SAMPLE_RATE = 34
	const val INTER_ARRIVAL_JITTER = 35
	const val CONTROLLER_POSE = 36
	const val SKELETON_POSE = 37
	const val FLOOR_HEIGHT = 38
	const val ACTIVITY = 39
	const val BODY_ROLE = 44
	const val TRACKER_STATUS = 45
	const val CONFIGURED_SAMPLE_RATE = 46
	const val SLEEP_STATE = 47

	const val UDP_SEQUENCE = 1L shl 0
	const val UDP_DEVICE_TIMESTAMP = 1L shl 1
	const val UDP_RAW_GYRO = 1L shl 2
	const val UDP_MAGNETIC = 1L shl 3
	const val UDP_UPTIME = 1L shl 4
	const val UDP_PACKET_COUNTERS = 1L shl 5
	const val UDP_CALIBRATION_QUALITY = 1L shl 6
	const val UDP_CHARGING = 1L shl 7
	const val UDP_POWER_MODE = 1L shl 8
	const val UDP_RESET_REASON = 1L shl 9
	const val UDP_CONFIGURED_SAMPLE_RATE = 1L shl 10
	const val UDP_SLEEP_STATE = 1L shl 11

	private val udpMaskChannels = linkedMapOf(
		UDP_SEQUENCE to setOf(PACKET_SEQUENCE),
		UDP_DEVICE_TIMESTAMP to setOf(DEVICE_TIMESTAMP),
		UDP_RAW_GYRO to setOf(RAW_GYRO),
		UDP_MAGNETIC to setOf(MAGNETIC_VECTOR),
		UDP_UPTIME to setOf(DEVICE_UPTIME),
		UDP_PACKET_COUNTERS to setOf(PACKET_GAPS, PACKETS_REORDERED, PACKETS_DUPLICATE, PACKETS_CORRUPT),
		UDP_CALIBRATION_QUALITY to setOf(CALIBRATION_QUALITY),
		UDP_CHARGING to setOf(CHARGING_STATE),
		UDP_POWER_MODE to setOf(POWER_MODE),
		UDP_RESET_REASON to setOf(RESET_REASON),
		UDP_CONFIGURED_SAMPLE_RATE to setOf(CONFIGURED_SAMPLE_RATE),
		UDP_SLEEP_STATE to setOf(SLEEP_STATE),
	)

	fun forUdpMask(mask: Long): Set<Int> = udpMaskChannels
		.filterKeys { mask and it != 0L }
		.values
		.flatten()
		.toCollection(linkedSetOf())

	fun forHidPacket(packetType: Int): Set<Int> = when (packetType) {
		0 -> setOf(BATTERY, BATTERY_VOLTAGE, TEMPERATURE, RSSI)
		1, 2, 7 -> setOf(RAW_ORIENTATION, RAW_ACCELERATION, LINEAR_ACCELERATION, ANGULAR_VELOCITY, OBSERVED_SAMPLE_RATE, INTER_ARRIVAL_JITTER)
		3 -> setOf(PACKETS_RECEIVED, PACKETS_LOST, PACKET_LOSS, RSSI, SLEEP_STATE)
		4 -> setOf(RAW_ORIENTATION, MAGNETIC_VECTOR, ANGULAR_VELOCITY, OBSERVED_SAMPLE_RATE, INTER_ARRIVAL_JITTER)
		5 -> setOf(DEVICE_UPTIME)
		else -> emptySet()
	}
}
