package dev.slimevr.unit

import dev.slimevr.dataset.ChannelProvenance
import dev.slimevr.dataset.ChannelValidity
import dev.slimevr.dataset.DatasetConformanceFixture
import dev.slimevr.dataset.generated.DatasetV1Bindings
import dev.slimevr.dataset.generated.DatasetV1Reader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt

class DatasetConformanceFixtureTests {
	@Test
	fun testAllV1RecordsAndOptionalFieldsRoundTrip() {
		val records = DatasetConformanceFixture.records().map(DatasetV1Reader::read)
		assertEquals(listOf(0, 1, 2, 3, 4), records.map { it.type })
		assertEquals(listOf(0L, 1L, 2L, 3L, 4L), records.map { it.sequence })
		val header = records[0].header!!
		assertEquals(DatasetConformanceFixture.SESSION_ID, header.sessionId)
		assertEquals(45, header.channels.size)
		assertEquals("raw_orientation", header.channels.first().name)
		assertEquals(setOf(ChannelProvenance.MEASURED, ChannelProvenance.FIRMWARE_REPORTED), header.channels.first().allowedProvenance)
		val roster = records[1].roster!!
		assertEquals(3, roster.revision)
		assertEquals(listOf("tracker-1", "controller-1", "hmd-1"), roster.trackers.map { it.sessionTrackerId })
		val frame = records[2].frames.single()
		val tracker = frame.trackers.single()
		assertEquals(ChannelValidity.STALE, tracker.accelerationValidity)
		assertEquals(ChannelProvenance.LEGACY_ESTIMATE, tracker.driftProvenance)
		assertEquals(listOf(8, 9, 21), tracker.nativeChannels.map { it.channelId })
		assertEquals(42L, tracker.nativeChannels[1].integerValue)
		assertEquals("CALIBRATED", tracker.nativeChannels[2].textValue)
		assertEquals("model-sha256", tracker.correction.modelHash)
		assertEquals(321L, tracker.correction.latencyMicros)
		assertEquals(ChannelProvenance.USER_ANNOTATED, frame.activity.provenance)
		assertEquals("controller-1", frame.contextSamples.single().sessionTrackerId)
		assertEquals(1.75f, frame.hmd.position.y)
		val eventRecord = records[3]
		assertEquals(listOf("WAIST", "LEGS"), eventRecord.events.single().affectedBodyParts)
		assertEquals(6L, eventRecord.resetLabels.single().calibrationEpoch)
		val footer = records[4].footer!!
		assertTrue(footer.complete)
		assertEquals(7, footer.counters.queueHighWatermark)
		assertEquals(6L, footer.counters.packetCorrupt)
		assertEquals(64, footer.telemetrySha256.length)
	}

	@Test
	fun testCanonicalNonCommutingResetComposition() {
		val label = DatasetV1Reader.read(DatasetConformanceFixture.records()[3]).resetLabels.single()
		val composed = normalize(multiply(label.rawOrientationAfter, inverse(label.rawOrientationBefore)))
		val correction = label.correction
		assertQuaternionClose(correction, composed)
		val reverse = normalize(multiply(inverse(label.rawOrientationBefore), label.rawOrientationAfter))
		assertTrue(abs(correction.z - reverse.z) > 0.9f, "fixture must distinguish multiplication order")
	}

	private fun multiply(a: dev.slimevr.dataset.QuaternionSample, b: dev.slimevr.dataset.QuaternionSample) =
		dev.slimevr.dataset.QuaternionSample(
			a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
			a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
			a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
			a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z,
		)

	private fun inverse(q: dev.slimevr.dataset.QuaternionSample) =
		dev.slimevr.dataset.QuaternionSample(-q.x, -q.y, -q.z, q.w)

	private fun normalize(q: dev.slimevr.dataset.QuaternionSample): dev.slimevr.dataset.QuaternionSample {
		val norm = sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w)
		return dev.slimevr.dataset.QuaternionSample(q.x / norm, q.y / norm, q.z / norm, q.w / norm)
	}

	private fun assertQuaternionClose(expected: dev.slimevr.dataset.QuaternionSample, actual: dev.slimevr.dataset.QuaternionSample) {
		assertTrue(listOf(expected.x - actual.x, expected.y - actual.y, expected.z - actual.z, expected.w - actual.w).all { abs(it) < 1e-5f })
	}
}
