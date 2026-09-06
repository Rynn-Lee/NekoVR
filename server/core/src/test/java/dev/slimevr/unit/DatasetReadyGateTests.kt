package dev.slimevr.unit

import dev.slimevr.dataset.DatasetReadyEvidence
import dev.slimevr.dataset.DatasetReadyPilotEvidence
import dev.slimevr.dataset.DatasetReadyReport
import dev.slimevr.dataset.DatasetReadyReportStore
import dev.slimevr.dataset.PilotSource
import dev.slimevr.dataset.REQUIRED_DATASET_READY_EVIDENCE
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DatasetReadyGateTests {
	private fun passingReport() = DatasetReadyReport(
		generatedUtc = "2026-09-06T00:00:00Z",
		buildCommit = "test-commit",
		ready = true,
		evidence = REQUIRED_DATASET_READY_EVIDENCE.map { DatasetReadyEvidence(it, true, "test") },
		pilots = PilotSource.entries.mapIndexed { index, source ->
			DatasetReadyPilotEvidence(
				source,
				"${source.name.lowercase()}.nvrdata",
				true,
				true,
				true,
				true,
				rosterSize = 5 + index,
				transports = if (index == 0) setOf("UDP") else setOf("HID"),
			)
		},
	)

	@Test
	fun testCompleteReportPasses() {
		assertTrue(DatasetReadyReportStore.evaluate(passingReport()).ready)
	}

	@Test
	fun testDeclaredReadyCannotBypassMissingEvidence() {
		val report = passingReport().copy(evidence = passingReport().evidence.dropLast(1))
		assertFalse(DatasetReadyReportStore.evaluate(report).ready)
	}

	@Test
	fun testBothPilotSourcesAndCrossLanguageMatchAreRequired() {
		val oneSource = passingReport().copy(pilots = passingReport().pilots.filter { it.source == PilotSource.SIMULATED })
		assertFalse(DatasetReadyReportStore.evaluate(oneSource).ready)
		val mismatch = passingReport().copy(
			pilots = passingReport().pilots.map { if (it.source == PilotSource.REAL) it.copy(statisticsMatch = false) else it },
		)
		assertFalse(DatasetReadyReportStore.evaluate(mismatch).ready)
	}
}
