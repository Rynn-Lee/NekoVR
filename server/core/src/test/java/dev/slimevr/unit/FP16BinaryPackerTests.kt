package dev.slimevr.unit

import com.fasterxml.jackson.databind.ObjectMapper
import dev.slimevr.dataset.FP16BinaryPacker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs

class FP16BinaryPackerTests {

	@Test
	fun testAllBinary16PatternsPreserveClassAndFiniteEncoding() {
		for (bits in 0..0xffff) {
			val half = bits.toShort()
			val decoded = FP16BinaryPacker.fromHalfPrecision(half)
			val exponent = (bits ushr 10) and 0x1f
			val mantissa = bits and 0x3ff
			val encoded = FP16BinaryPacker.toHalfPrecision(decoded).toInt() and 0xffff
			when {
				exponent == 0x1f && mantissa != 0 -> {
					assertTrue(decoded.isNaN(), "0x${bits.toString(16)} must decode as NaN")
					assertEquals(0x1f, (encoded ushr 10) and 0x1f)
					assertTrue(encoded and 0x3ff != 0, "NaN payload must remain non-zero")
					assertEquals(bits and 0x8000, encoded and 0x8000, "NaN sign policy must be stable")
				}

				exponent == 0x1f -> {
					assertTrue(decoded.isInfinite())
					assertEquals(bits, encoded)
				}

				else -> assertEquals(bits, encoded, "finite pattern 0x${bits.toString(16)} must round-trip")
			}
		}
	}

	@Test
	fun testRoundToNearestEvenBoundariesAndSafetyChecks() {
		assertEquals(0x0000, FP16BinaryPacker.toHalfPrecision(0f).toInt() and 0xffff)
		assertEquals(0x8000, FP16BinaryPacker.toHalfPrecision(-0.0f).toInt() and 0xffff)
		assertEquals(0x0001, FP16BinaryPacker.toHalfPrecision(Math.scalb(1.0f, -24)).toInt() and 0xffff)
		assertEquals(0x3c00, FP16BinaryPacker.toHalfPrecision(1.00048828125f).toInt() and 0xffff)
		assertEquals(0x3c02, FP16BinaryPacker.toHalfPrecision(1.00146484375f).toInt() and 0xffff)
		assertEquals(0x7bff, FP16BinaryPacker.toHalfPrecision(FP16BinaryPacker.MAX_FINITE).toInt() and 0xffff)
		assertEquals(0x7c00, FP16BinaryPacker.toHalfPrecision(70_000f).toInt() and 0xffff)
		assertTrue(FP16BinaryPacker.fromHalfPrecision(0x7c00.toShort()).isInfinite())
		assertTrue(FP16BinaryPacker.fromHalfPrecision(0x7e00.toShort()).isNaN())

		assertThrows(IllegalArgumentException::class.java) { FP16BinaryPacker.encodeFinite(Float.NaN, -1f, 1f, "test") }
		assertThrows(IllegalArgumentException::class.java) { FP16BinaryPacker.encodeFinite(Float.POSITIVE_INFINITY, -1f, 1f, "test") }
		assertThrows(IllegalArgumentException::class.java) { FP16BinaryPacker.encodeFinite(1.01f, -1f, 1f, "test") }
		assertThrows(IllegalArgumentException::class.java) { FP16BinaryPacker.decodeFinite(0x7c00.toShort(), -1f, 1f, "test") }
		assertThrows(IllegalArgumentException::class.java) {
			FP16BinaryPacker.requireNormalizedQuaternion(floatArrayOf(0f, 0f, 0f, 0.5f), "test quaternion")
		}

		val representative = listOf(-128f, -64f, -1f, -0.1f, 0f, 0.1f, 1f, 64f, 128f)
		for (value in representative) {
			val decoded = FP16BinaryPacker.fromHalfPrecision(FP16BinaryPacker.toHalfPrecision(value))
			val tolerance = maxOf(abs(value) / 1024f, Math.scalb(1.0f, -24))
			assertTrue(abs(decoded - value) <= tolerance, "$value exceeded binary16 tolerance $tolerance")
		}
	}

	@Test
	fun testSharedNegativeNumericalFixturesAreRejected() {
		val fixture = findRepoRoot().resolve("dataset/fixtures/numerical-negative-v1.json")
		val root = ObjectMapper().readTree(fixture.toFile())
		assertEquals(1, root["schemaVersion"].asInt())
		for (case in root["fp16Cases"]) {
			assertThrows(IllegalArgumentException::class.java) {
				FP16BinaryPacker.decodeFinite(
					case["bits"].asInt().toShort(),
					case["minimum"].floatValue(),
					case["maximum"].floatValue(),
					case["name"].asText(),
				)
			}
		}
		for (case in root["quaternionCases"]) {
			val values = case["values"].map { it.floatValue() }.toFloatArray()
			assertThrows(IllegalArgumentException::class.java) {
				FP16BinaryPacker.requireNormalizedQuaternion(values, case["name"].asText())
			}
		}
	}

	private fun findRepoRoot(): Path {
		var candidate = Path.of("").toAbsolutePath().normalize()
		while (true) {
			if (Files.isRegularFile(candidate.resolve("dataset/fixtures/numerical-negative-v1.json"))) return candidate
			candidate = candidate.parent ?: error("Unable to find repository root from ${Path.of("").toAbsolutePath()}")
		}
	}
}
