package dev.slimevr.unit

import dev.slimevr.dataset.DatasetReadyBuild
import dev.slimevr.dataset.DatasetReadyEvidence
import dev.slimevr.dataset.DatasetReadyPilotEvidence
import dev.slimevr.dataset.DatasetReadyReport
import dev.slimevr.dataset.DatasetReadyReportStore
import dev.slimevr.dataset.DatasetReadyRuntimeIdentity
import dev.slimevr.dataset.DatasetReadyVerifier
import dev.slimevr.dataset.PilotSource
import dev.slimevr.dataset.REQUIRED_DATASET_READY_EVIDENCE
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

class DatasetReadyGateTests {
	@TempDir
	lateinit var temporaryDirectory: Path

	private val commit = "0123456789abcdef0123456789abcdef01234567"
	private val now: Instant = Instant.parse("2026-09-11T12:00:00Z")

	private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
		.digest(bytes).joinToString("") { "%02x".format(it) }

	private fun artifact(name: String, contents: String = name): Pair<String, String> {
		val path = temporaryDirectory.resolve(name)
		Files.writeString(path, contents)
		return path.toRealPath().toString() to sha256(Files.readAllBytes(path))
	}

	private fun fixture(): Triple<DatasetReadyReport, Path, DatasetReadyRuntimeIdentity> {
		val reportPath = temporaryDirectory.resolve("dataset-ready-report.json")
		Files.writeString(reportPath, "fixture")
		val evidence = REQUIRED_DATASET_READY_EVIDENCE.map { id ->
			val command = "verify-$id"
			val (path, hash) = artifact("$id.json")
			DatasetReadyEvidence(
				id = id,
				passed = true,
				detail = "test",
				commands = listOf(command),
				commandSha256 = listOf(sha256(command.toByteArray(StandardCharsets.UTF_8))),
				resultArtifact = path,
				resultSha256 = hash,
			)
		}
		val serverResult = artifact("server-pilots.json")
		val pilots = PilotSource.entries.mapIndexed { index, source ->
			val archive = artifact("${source.name.lowercase()}.nvrdata")
			val pythonResult = artifact("python-$index.json")
			DatasetReadyPilotEvidence(
				source = source,
				archive = archive.first,
				archiveSha256 = archive.second,
				serverResultArtifact = serverResult.first,
				serverResultSha256 = serverResult.second,
				pythonResultArtifact = pythonResult.first,
				pythonResultSha256 = pythonResult.second,
				serverValid = true,
				pythonValid = true,
				statisticsMatch = true,
				provenanceValid = true,
				rosterSize = 5 + index * 3,
				transports = if (index == 0) setOf("UDP") else setOf("HID", "NRF"),
			)
		}
		val report = DatasetReadyReport(
			verifier = DatasetReadyVerifier("nekovr-dataset-ready", "2"),
			reportPath = reportPath.toRealPath().toString(),
			generatedUtc = "2026-09-11T11:00:00Z",
			expiresUtc = "2026-09-12T11:00:00Z",
			build = DatasetReadyBuild(commit, false, "REQUIRE_CLEAN"),
			ready = true,
			evidence = evidence,
			pilots = pilots,
		)
		return Triple(report, reportPath, DatasetReadyRuntimeIdentity(commit, false, now = now))
	}

	private fun evaluate(report: DatasetReadyReport, path: Path, runtime: DatasetReadyRuntimeIdentity) =
		DatasetReadyReportStore.evaluate(report, path, runtime)

	@Test
	fun testCompleteHashBoundReportPasses() {
		val (report, path, runtime) = fixture()
		Files.writeString(path, Json.encodeToString(report))
		assertTrue(DatasetReadyReportStore.load(path, runtime).ready)
	}

	@Test
	fun testCopiedAndHandAuthoredReportsAreRejected() {
		val (report, path, runtime) = fixture()
		val copied = temporaryDirectory.resolve("copied-report.json")
		Files.copy(path, copied)
		assertFalse(evaluate(report, copied, runtime).ready)

		val handAuthored = temporaryDirectory.resolve("hand-authored.json")
		Files.writeString(handAuthored, """{"schemaVersion":2,"ready":true}""")
		assertFalse(DatasetReadyReportStore.load(handAuthored, runtime).ready)
	}

	@Test
	fun testStaleAndDuplicateEvidenceAreRejected() {
		val (report, path, runtime) = fixture()
		assertFalse(evaluate(report.copy(expiresUtc = "2026-09-11T11:30:00Z"), path, runtime).ready)
		assertFalse(evaluate(report.copy(evidence = report.evidence + report.evidence.first()), path, runtime).ready)
	}

	@Test
	fun testPostGenerationEvidenceAndPilotMutationAreRejected() {
		val (report, path, runtime) = fixture()
		Files.writeString(Path.of(report.evidence.first().resultArtifact), "mutated")
		assertFalse(evaluate(report, path, runtime).ready)

		val (pilotReport, pilotPath, pilotRuntime) = fixture()
		Files.writeString(Path.of(pilotReport.pilots.first().archive), "mutated")
		assertFalse(evaluate(pilotReport, pilotPath, pilotRuntime).ready)
	}

	@Test
	fun testBuildIdentityAndDirtyPolicyAreEnforced() {
		val (report, path, runtime) = fixture()
		assertFalse(evaluate(report, path, runtime.copy(commit = "UNKNOWN")).ready)
		assertFalse(evaluate(report, path, runtime.copy(commit = "abcdef0123456789abcdef0123456789abcdef01")).ready)
		assertFalse(evaluate(report.copy(build = report.build.copy(dirty = true)), path, runtime.copy(dirty = true)).ready)
		assertFalse(
			evaluate(
				report.copy(build = report.build.copy(dirty = true, dirtyTreePolicy = "ALLOW_DEVELOPMENT")),
				path,
				runtime.copy(dirty = true, allowDirtyDevelopment = false),
			).ready,
		)
	}

	@Test
	fun testMissingArtifactsAndPhysicalRealPilotAreRequired() {
		val (report, path, runtime) = fixture()
		Files.delete(Path.of(report.evidence.first().resultArtifact))
		assertFalse(evaluate(report, path, runtime).ready)

		val (simulatedReport, simulatedPath, simulatedRuntime) = fixture()
		val simulatedOnly = simulatedReport.copy(
			ready = false,
			blockingFindings = listOf("Missing physical real-pilot evidence"),
			pilots = simulatedReport.pilots.filter { it.source == PilotSource.SIMULATED },
		)
		val status = evaluate(simulatedOnly, simulatedPath, simulatedRuntime)
		assertFalse(status.ready)
		assertTrue(status.detail.contains("real pilot evidence"))
	}
}
