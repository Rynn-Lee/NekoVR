package dev.slimevr.dataset

import java.nio.ByteBuffer
import kotlin.math.abs

/** IEEE-754 binary16 conversion plus dataset-safe finite/range checks. */
object FP16BinaryPacker {
	const val MAX_FINITE: Float = 65_504f

	/** Raw round-to-nearest-even conversion. NaN and infinities are preserved. */
	fun toHalfPrecision(value: Float): Short {
		val bits = java.lang.Float.floatToRawIntBits(value)
		val sign = (bits ushr 16) and 0x8000
		val exponent = (bits ushr 23) and 0xff
		val mantissa = bits and 0x7fffff

		if (exponent == 0xff) {
			if (mantissa == 0) return (sign or 0x7c00).toShort()
			val payload = (mantissa ushr 13).coerceAtLeast(1)
			return (sign or 0x7c00 or payload).toShort()
		}

		var halfExponent = exponent - 127 + 15
		if (halfExponent >= 0x1f) return (sign or 0x7c00).toShort()
		if (halfExponent <= 0) {
			if (halfExponent < -10) return sign.toShort()
			val significand = mantissa or 0x800000
			val shift = 14 - halfExponent
			var halfMantissa = significand ushr shift
			val remainder = significand and ((1 shl shift) - 1)
			val halfway = 1 shl (shift - 1)
			if (remainder > halfway || (remainder == halfway && (halfMantissa and 1) != 0)) halfMantissa++
			return (sign or halfMantissa).toShort()
		}

		var halfMantissa = mantissa ushr 13
		val remainder = mantissa and 0x1fff
		if (remainder > 0x1000 || (remainder == 0x1000 && (halfMantissa and 1) != 0)) {
			halfMantissa++
			if (halfMantissa == 0x400) {
				halfMantissa = 0
				halfExponent++
				if (halfExponent >= 0x1f) return (sign or 0x7c00).toShort()
			}
		}
		return (sign or (halfExponent shl 10) or halfMantissa).toShort()
	}

	fun fromHalfPrecision(half: Short): Float {
		val bits = half.toInt() and 0xffff
		val sign = (bits and 0x8000) shl 16
		val exponent = (bits ushr 10) and 0x1f
		val mantissa = bits and 0x3ff
		val floatBits = when (exponent) {
			0 -> {
				if (mantissa == 0) {
					sign
				} else {
					var m = mantissa
					var e = -14
					while ((m and 0x400) == 0) {
						m = m shl 1
						e--
					}
					m = m and 0x3ff
					sign or ((e + 127) shl 23) or (m shl 13)
				}
			}
			0x1f -> sign or 0x7f800000 or (mantissa shl 13)
			else -> sign or ((exponent - 15 + 127) shl 23) or (mantissa shl 13)
		}
		return java.lang.Float.intBitsToFloat(floatBits)
	}

	fun encodeFinite(value: Float, minimum: Float, maximum: Float, channel: String): Short {
		require(value.isFinite()) { "$channel must be finite" }
		require(minimum.isFinite() && maximum.isFinite() && minimum <= maximum) { "Invalid range for $channel" }
		require(value in minimum..maximum && abs(value) <= MAX_FINITE) {
			"$channel value $value is outside [$minimum, $maximum]"
		}
		val encoded = toHalfPrecision(value)
		require(fromHalfPrecision(encoded).isFinite()) { "$channel overflowed binary16" }
		return encoded
	}

	fun decodeFinite(half: Short, minimum: Float, maximum: Float, channel: String): Float {
		val value = fromHalfPrecision(half)
		require(value.isFinite()) { "$channel contains non-finite binary16" }
		require(value in minimum..maximum) { "$channel decoded value $value is outside [$minimum, $maximum]" }
		return value
	}

	fun writeQuaternionFP16(buffer: ByteBuffer, qx: Float, qy: Float, qz: Float, qw: Float) {
		val normSquared = qx * qx + qy * qy + qz * qz + qw * qw
		require(normSquared.isFinite() && normSquared in 0.98f..1.02f) { "quaternion must be finite and normalized" }
		buffer.putShort(encodeFinite(qx, -1f, 1f, "quaternion.x"))
		buffer.putShort(encodeFinite(qy, -1f, 1f, "quaternion.y"))
		buffer.putShort(encodeFinite(qz, -1f, 1f, "quaternion.z"))
		buffer.putShort(encodeFinite(qw, -1f, 1f, "quaternion.w"))
	}

	fun writeVector3FP16(buffer: ByteBuffer, x: Float, y: Float, z: Float, minimum: Float = -MAX_FINITE, maximum: Float = MAX_FINITE) {
		buffer.putShort(encodeFinite(x, minimum, maximum, "vector.x"))
		buffer.putShort(encodeFinite(y, minimum, maximum, "vector.y"))
		buffer.putShort(encodeFinite(z, minimum, maximum, "vector.z"))
	}
}
