package dev.slimevr.dataset

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Packs IEEE 754 32-bit single-precision floats into 16-bit half-precision floats (FP16).
 * Cuts telemetry stream size in half while maintaining high accuracy.
 */
object FP16BinaryPacker {

	fun toHalfPrecision(fval: Float): Short {
		val fbits = java.lang.Float.floatToIntBits(fval)
		val sign = fbits ushr 16 and 0x8000
		var valBits = fbits and 0x7fffffff + 0x1000

		if (valBits >= 0x47800000) {
			return if (fbits and 0x7fffffff >= 0x47800000) {
				if (valBits < 0x7f800000) (sign or 0x7c00).toShort()
				else (sign or 0x7c00 or (fbits and 0x007fffff ushr 13)).toShort()
			} else (sign or 0x7bff).toShort()
		}
		if (valBits >= 0x38800000) {
			return (sign or (valBits - 0x38000000 ushr 13)).toShort()
		}
		if (valBits < 0x33000000) {
			return sign.toShort()
		}
		valBits = fbits and 0x7fffffff ushr 23
		return (sign or ((fbits and 0x7fffff or 0x800000) + (0x800000 ushr valBits - 102) ushr 126 - valBits)).toShort()
	}

	fun writeQuaternionFP16(buffer: ByteBuffer, qx: Float, qy: Float, qz: Float, qw: Float) {
		buffer.putShort(toHalfPrecision(qx))
		buffer.putShort(toHalfPrecision(qy))
		buffer.putShort(toHalfPrecision(qz))
		buffer.putShort(toHalfPrecision(qw))
	}

	fun writeVector3FP16(buffer: ByteBuffer, x: Float, y: Float, z: Float) {
		buffer.putShort(toHalfPrecision(x))
		buffer.putShort(toHalfPrecision(y))
		buffer.putShort(toHalfPrecision(z))
	}
}
