package dev.slimevr.dataset

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * SHA-256 over the exact uncompressed record framing before the Footer record.
 * Each contribution is the little-endian uint32 payload length followed by the
 * original FlatBuffer payload bytes. The Footer itself is never contributed.
 */
class DatasetTelemetryChecksum {
	private val digest = MessageDigest.getInstance("SHA-256")
	private var finished = false

	fun updateRecord(payload: ByteArray) {
		check(!finished) { "dataset telemetry checksum is already finalized" }
		require(payload.size <= UInt.MAX_VALUE.toLong()) { "record is too large for uint32 framing" }
		digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size).array())
		digest.update(payload)
	}

	fun digestHex(): String {
		check(!finished) { "dataset telemetry checksum is already finalized" }
		finished = true
		return digest.digest().joinToString("") { "%02x".format(it) }
	}

	companion object {
		fun computeHex(recordsBeforeFooter: Iterable<ByteArray>): String = DatasetTelemetryChecksum().run {
			recordsBeforeFooter.forEach(::updateRecord)
			digestHex()
		}
	}
}
