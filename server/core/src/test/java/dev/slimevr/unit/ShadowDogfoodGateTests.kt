package dev.slimevr.unit

import dev.slimevr.ai.REQUIRED_SHADOW_ACTIVITIES
import dev.slimevr.ai.ShadowDogfoodEvidenceKind
import dev.slimevr.ai.ShadowDogfoodGate
import dev.slimevr.ai.ShadowDogfoodReport
import dev.slimevr.ai.ShadowDogfoodSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShadowDogfoodGateTests {
	private fun report(kind: ShadowDogfoodEvidenceKind = ShadowDogfoodEvidenceKind.REAL_PLAYER) = ShadowDogfoodReport(
		generatedUtc = "2026-09-07T00:00:00Z", buildCommit = "fixture", modelSha256 = "a".repeat(64), passed = true,
		sessions = listOf(5, 6, 8, 10).map { layout ->
			ShadowDogfoodSession(
				"layout-$layout", kind, 600, layout, REQUIRED_SHADOW_ACTIVITIES, resetCount = 1,
				lateEpochRejections = 1, failOpenChecks = 1, failOpenFailures = 0,
				aiAppliedCorrections = 0, nonIdentityTrackingCorrections = 0,
				baselineTickP95Micros = 100, shadowTickP95Micros = 200,
				queueDrained = true, queueFinalDepth = 0, visibleRegressionReported = false,
			)
		},
	)

	@Test
	fun `real player cohorts pass only with reset safety performance and fail open evidence`() {
		assertTrue(ShadowDogfoodGate.evaluate(report()).passed)
		val failed = report().copy(sessions = report().sessions.mapIndexed { index, session ->
			if (index == 0) session.copy(nonIdentityTrackingCorrections = 1, failOpenFailures = 1) else session
		})
		assertEquals(setOf("FAIL_OPEN", "SHADOW_APPLIED"), ShadowDogfoodGate.evaluate(failed).findings.map { it.code }.toSet())
	}

	@Test
	fun `automated replay cannot claim dogfood completion`() {
		assertFalse(ShadowDogfoodGate.evaluate(report(ShadowDogfoodEvidenceKind.AUTOMATED_REPLAY)).passed)
	}
}
