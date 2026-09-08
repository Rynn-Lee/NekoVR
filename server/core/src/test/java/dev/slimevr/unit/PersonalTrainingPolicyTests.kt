package dev.slimevr.unit

import dev.slimevr.ai.personal.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PersonalTrainingPolicyTests {
	@Test fun `presets scale validated work to usable data and hardware`() {
		val coverage = PersonalCoverageSummary(120.0, 1_000, 20, 90.0)
		val hardware = PersonalHardwareProbe(16, 16_384, false)
		val plans = PersonalTrainingPreset.entries.map { PersonalTrainingPlans.derive(it, coverage, hardware) }
		assertTrue(plans.zipWithNext().all { (left, right) -> left.epochs < right.epochs && left.windowBudget < right.windowBudget && left.patience < right.patience })
		assertEquals(8, plans[1].cpuThreads)
		assertEquals("CPU", plans[1].provider)
		assertEquals(1, PersonalTrainingPlans.derive(PersonalTrainingPreset.QUICK, coverage.copy(usableWindows = 1), hardware).windowBudget)
	}

	@Test fun `resource governor protects active tracking and hard budgets`() {
		val budget = PersonalResourceBudget(4, 4096, 70, 8192, 8192, 32f)
		val normal = PersonalResourceUsage(4, 2048, 50, 4096, 100, 10f, false)
		assertEquals(ResourceAction.RUN, PersonalResourceGovernor.decide(budget, normal))
		assertEquals(ResourceAction.THROTTLE, PersonalResourceGovernor.decide(budget, normal.copy(gpuUtilizationPercent = 90)))
		assertEquals(ResourceAction.CHECKPOINT_AND_PAUSE, PersonalResourceGovernor.decide(budget, normal.copy(activeVr = true)))
		assertEquals(ResourceAction.CHECKPOINT_AND_PAUSE, PersonalResourceGovernor.decide(budget, normal.copy(ramMiB = 9000)))
	}
}
