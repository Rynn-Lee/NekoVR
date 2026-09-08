package dev.slimevr.unit

import dev.slimevr.ai.ActiveCorrectionAuthorization
import dev.slimevr.ai.InferenceReadyEvidence
import dev.slimevr.ai.InferenceReadyEvidenceManifest
import dev.slimevr.ai.InferenceReadyEvidenceSource
import dev.slimevr.ai.InferenceReadyReport
import dev.slimevr.ai.InferenceReadyReportCommand
import dev.slimevr.ai.InferenceReadyReportStore
import dev.slimevr.ai.InferenceReadyStatus
import dev.slimevr.ai.REQUIRED_INFERENCE_READY_EVIDENCE
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InferenceReadyGateTests {
	private val modelHash = "a".repeat(64)

	private fun passingReport() = InferenceReadyReport(
		generatedUtc = "2026-09-07T00:00:00Z",
		buildCommit = "fixture",
		modelSha256 = modelHash,
		ready = true,
		evidence = REQUIRED_INFERENCE_READY_EVIDENCE.map { id ->
			InferenceReadyEvidence(id, true, "fixture", "$id.json", "b".repeat(64))
		},
	)

	@Test
	fun `report requires every inference evidence category`() {
		assertTrue(InferenceReadyReportStore.evaluate(passingReport()).ready)
		val withoutShadow = passingReport().copy(evidence = passingReport().evidence.filterNot { it.id == "shadow-dogfood" })
		assertFalse(InferenceReadyReportStore.evaluate(withoutShadow).ready)
		val failedSafety = passingReport().copy(
			evidence = passingReport().evidence.map { if (it.id == "safety") it.copy(passed = false) else it },
		)
		assertFalse(InferenceReadyReportStore.evaluate(failedSafety).ready)
	}

	@Test
	fun `active authorization requires opt in ready report and exact model hash`() {
		val ready = InferenceReadyStatus(true, "fixture", passingReport())
		assertEquals("ACTIVE_CORRECTION_OPT_IN_DISABLED", ActiveCorrectionAuthorization.evaluate(false, ready, modelHash).rejectionReason)
		assertEquals("INFERENCE_READY_GATE_PENDING", ActiveCorrectionAuthorization.evaluate(true, ready.copy(ready = false), modelHash).rejectionReason)
		assertEquals("INFERENCE_READY_MODEL_MISMATCH", ActiveCorrectionAuthorization.evaluate(true, ready, "c".repeat(64)).rejectionReason)
		assertTrue(ActiveCorrectionAuthorization.evaluate(true, ready, modelHash).allowed)
	}

	@Test
	fun `report command hash pins and aggregates source reports`() {
		val directory = Files.createTempDirectory("nekovr-inference-ready")
		try {
			val sources = REQUIRED_INFERENCE_READY_EVIDENCE.map { id ->
				val path = directory.resolve("$id.json")
				Files.writeString(path, """{"passed":true,"modelSha256":"$modelHash"}""")
				InferenceReadyEvidenceSource(id, path.fileName.toString(), InferenceReadyReportStore.sha256(path), "fixture")
			}
			val manifest = directory.resolve("manifest.json")
			Files.writeString(
				manifest,
				Json { prettyPrint = true; encodeDefaults = true }.encodeToString(
					InferenceReadyEvidenceManifest(buildCommit = "fixture", modelSha256 = modelHash, evidence = sources),
				),
			)
			val output = directory.resolve("report.json")
			InferenceReadyReportCommand.main(arrayOf(manifest.toString(), output.toString()))
			assertTrue(InferenceReadyReportStore.load(output).ready)
		} finally {
			Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
		}
	}
}
