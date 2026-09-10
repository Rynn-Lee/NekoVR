package dev.slimevr.unit

import dev.slimevr.dataset.DatasetResetLabel
import dev.slimevr.dataset.ChannelValidity
import dev.slimevr.dataset.QuaternionSample
import dev.slimevr.dataset.generated.DatasetV1Bindings
import dev.slimevr.dataset.generated.DatasetV1Reader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.sin

class ResetLabelBindingsTests {
	private fun q(value: Float) = QuaternionSample(sin(value), 0f, 0f, cos(value))

	@Test
	fun testCompleteAdjustmentChainRoundTrip() {
		val label = DatasetResetLabel(
			eventIndex = 7,
			sessionTrackerId = "imu-1",
			correction = q(1f),
			diagnosticYawRadians = 0.5f,
			axisMask = 1,
			preStartFrame = 10,
			preEndFrame = 20,
			postStartFrame = 20,
			postEndFrame = 30,
			qualityFlags = 0,
			attachmentRotBefore = q(2f), attachmentRotAfter = q(3f),
			mountingRotBefore = q(4f), mountingRotAfter = q(5f),
			yawRotBefore = q(6f), yawRotAfter = q(7f),
			gyroFixBefore = q(8f), gyroFixAfter = q(9f),
			mountRotFixBefore = q(10f), mountRotFixAfter = q(11f),
			tposeDownFixBefore = q(12f), tposeDownFixAfter = q(13f),
			constraintFixBefore = q(14f), constraintFixAfter = q(15f),
			resetEpoch = 21,
			calibrationEpoch = 22,
			bodyRole = "CHEST",
			hmdSampleAgeBeforeNs = 23,
			hmdSampleAgeAfterNs = 24,
			adjustedOrientationBefore = q(16f), adjustedOrientationAfter = q(17f),
			rawValidityBefore = ChannelValidity.STALE, rawValidityAfter = ChannelValidity.VALID,
			calibratedPreAiValidityBefore = ChannelValidity.INVALID, calibratedPreAiValidityAfter = ChannelValidity.VALID,
			adjustedValidityBefore = ChannelValidity.STALE, adjustedValidityAfter = ChannelValidity.VALID,
			statusBefore = "STALE", statusAfter = "OK", sampleAgeBeforeNs = 25, sampleAgeAfterNs = 26,
			resetEpochBefore = 20, calibrationEpochBefore = 19,
		)
		val decoded = DatasetV1Reader.read(DatasetV1Bindings.events(emptyList(), listOf(label), 0)).resetLabels.single()
		assertEquals(label.attachmentRotBefore, decoded.attachmentRotBefore)
		assertEquals(label.mountingRotAfter, decoded.mountingRotAfter)
		assertEquals(label.yawRotBefore, decoded.yawRotBefore)
		assertEquals(label.gyroFixAfter, decoded.gyroFixAfter)
		assertEquals(label.mountRotFixBefore, decoded.mountRotFixBefore)
		assertEquals(label.tposeDownFixAfter, decoded.tposeDownFixAfter)
		assertEquals(label.constraintFixBefore, decoded.constraintFixBefore)
		assertEquals(22L, decoded.calibrationEpoch)
		assertEquals("CHEST", decoded.bodyRole)
		assertEquals(23L, decoded.hmdSampleAgeBeforeNs)
		assertEquals(24L, decoded.hmdSampleAgeAfterNs)
		assertEquals(q(16f), decoded.adjustedOrientationBefore)
		assertEquals(q(17f), decoded.adjustedOrientationAfter)
		assertEquals(ChannelValidity.STALE, decoded.rawValidityBefore)
		assertEquals(ChannelValidity.INVALID, decoded.calibratedPreAiValidityBefore)
		assertEquals(ChannelValidity.VALID, decoded.adjustedValidityAfter)
		assertEquals("STALE", decoded.statusBefore)
		assertEquals(26L, decoded.sampleAgeAfterNs)
		assertEquals(20L, decoded.resetEpochBefore)
		assertEquals(19L, decoded.calibrationEpochBefore)
	}
}
