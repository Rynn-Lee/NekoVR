package dev.slimevr.unit

import dev.slimevr.ai.personal.PersonalArtifactReference
import dev.slimevr.ai.personal.PersonalCacheMetadata
import dev.slimevr.ai.personal.PersonalCheckpointMetadata
import dev.slimevr.ai.personal.PersonalCompatibility
import dev.slimevr.ai.personal.PersonalJobMetadata
import dev.slimevr.ai.personal.PersonalJobState
import dev.slimevr.ai.personal.PersonalModelMetadata
import dev.slimevr.ai.personal.PersonalProvenance
import dev.slimevr.ai.personal.PersonalTrackerLayout
import dev.slimevr.ai.personal.PersonalTrainingStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Comparator
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersonalTrainingStoreTests {
	private val timestamp = Instant.parse("2026-09-07T00:00:00Z")
	private val baseHash = "a".repeat(64)
	private val featureHash = "b".repeat(64)
	private val sessionHash = "c".repeat(64)
	private val artifactHash = "d".repeat(64)
	private val sidecarHash = "e".repeat(64)
	private val compatibility = PersonalCompatibility(
		bodyRoleIds = setOf(1, 2, 3, 4, 5, 6),
		layouts = listOf(PersonalTrackerLayout(5, listOf(1, 2, 3, 4, 5)), PersonalTrackerLayout(6, listOf(1, 2, 3, 4, 5, 6))),
		sensorFamilies = setOf("BNO085", "BMI160"),
	)
	private val provenance = PersonalProvenance(
		applicationCommit = "test-commit", trainerVersion = "trainer-v1", inferenceRuntimeVersion = "ort-v1",
		sourceHashes = mapOf("base_artifacts" to baseHash),
	)

	@Test
	fun `profiles and artifacts round trip in isolated profile namespaces`() = withStore { root, store ->
		val first = store.createProfile("Local player one", mapOf("height" to 1.72f), compatibility, provenance, timestamp)
		val second = store.createProfile("Local player two", emptyMap(), PersonalCompatibility(emptySet(), emptyList(), emptySet()), provenance, timestamp)
		assertEquals(listOf("Local player one", "Local player two"), store.listProfiles().map { it.localPseudonym })

		val job = PersonalJobMetadata(
			jobId = "job-1", profileId = first.profileId, baseModelSha256 = baseHash, featureSchemaSha256 = featureHash,
			selectedSessionHashes = setOf(sessionHash), settingsSha256 = "f".repeat(64), state = PersonalJobState.PAUSED,
			createdUtc = timestamp.toString(), updatedUtc = timestamp.toString(), activeCheckpointId = "checkpoint-1",
			cacheIds = setOf("cache-1"), provenance = provenance,
		)
		store.saveJob(job)
		val checkpoint = PersonalCheckpointMetadata(
			checkpointId = "checkpoint-1", jobId = job.jobId, profileId = first.profileId, baseModelSha256 = baseHash,
			epoch = 2, globalStep = 120, artifact = reference("checkpoints/job-1/checkpoint.bin", artifactHash),
			optimizerStateSha256 = "1".repeat(64), createdUtc = timestamp.toString(), resumable = true, provenance = provenance,
		)
		store.saveCheckpoint(checkpoint)
		val cache = PersonalCacheMetadata(
			cacheId = "cache-1", profileId = first.profileId, featureSchemaSha256 = featureHash,
			sourceSessionHashes = setOf(sessionHash), preprocessingConfigSha256 = "2".repeat(64),
			artifact = reference("cache/cache-1.bin", "3".repeat(64)), createdUtc = timestamp.toString(),
			lastVerifiedUtc = timestamp.toString(), provenance = provenance,
		)
		store.saveCache(cache)
		val model = PersonalModelMetadata(
			modelSha256 = artifactHash, profileId = first.profileId, baseModelSha256 = baseHash,
			featureSchemaSha256 = featureHash, artifact = reference("models/personal.onnx", artifactHash),
			sidecar = reference("models/personal.onnx.json", sidecarHash), compatibility = compatibility,
			selectedSessionHashes = setOf(sessionHash), splitManifestSha256 = "4".repeat(64),
			checkpointSha256 = artifactHash, metricsSha256 = "5".repeat(64), createdUtc = timestamp.toString(), provenance = provenance,
		)
		store.saveModel(model)

		assertEquals(job, store.loadJob(first.profileId, job.jobId))
		assertEquals(checkpoint, store.loadCheckpoint(first.profileId, job.jobId, checkpoint.checkpointId))
		assertEquals(cache, store.loadCache(first.profileId, cache.cacheId))
		assertEquals(model, store.loadModel(first.profileId, model.modelSha256))
		assertFailsWith<IllegalArgumentException> { store.loadJob(second.profileId, job.jobId) }
		assertFalse(Files.walk(root).use { paths -> paths.anyMatch { it.fileName.toString().endsWith(".tmp") } })
	}

	@Test
	fun `storage rejects traversal invalid hashes nonfinite body data and unknown versions`() = withStore { root, store ->
		assertFailsWith<IllegalArgumentException> {
			store.createProfile("Player", mapOf("height" to Float.NaN), compatibility, provenance, timestamp)
		}
		val profile = store.createProfile("Player", emptyMap(), compatibility, provenance, timestamp)
		val invalidJob = PersonalJobMetadata(
			jobId = "../escape", profileId = profile.profileId, baseModelSha256 = baseHash, featureSchemaSha256 = featureHash,
			selectedSessionHashes = setOf(sessionHash), settingsSha256 = "f".repeat(64), state = PersonalJobState.CREATED,
			createdUtc = timestamp.toString(), updatedUtc = timestamp.toString(), provenance = provenance,
		)
		assertFailsWith<IllegalArgumentException> { store.saveJob(invalidJob) }
		assertFailsWith<IllegalArgumentException> { reference("../outside.bin", artifactHash).let {
			store.saveCache(
				PersonalCacheMetadata(
					cacheId = "cache", profileId = profile.profileId, featureSchemaSha256 = featureHash,
					sourceSessionHashes = setOf(sessionHash), preprocessingConfigSha256 = "2".repeat(64), artifact = it,
					createdUtc = timestamp.toString(), lastVerifiedUtc = timestamp.toString(), provenance = provenance,
				),
			)
		} }

		val profilePath = root.resolve("profiles").resolve(profile.profileId).resolve("profile.json")
		profilePath.writeText(profilePath.readText().replace("\"schemaVersion\": 1", "\"schemaVersion\": 999"))
		assertFailsWith<IllegalArgumentException> { store.loadProfile(profile.profileId) }
	}

	@Test
	fun `serialized metadata contains pseudonymous aggregates but no raw identity fields`() = withStore { root, store ->
		val profile = store.createProfile("Offline alias", mapOf("height" to 1.8f), compatibility, provenance, timestamp)
		val json = root.resolve("profiles").resolve(profile.profileId).resolve("profile.json").readText()
		assertTrue(json.contains("Offline alias"))
		assertTrue(json.contains("sensorFamilies"))
		listOf("accountId", "email", "ipAddress", "macAddress", "serialNumber", "hardwareId").forEach {
			assertFalse(json.contains(it, ignoreCase = true))
		}
	}

	private fun reference(path: String, hash: String) = PersonalArtifactReference(path, hash, 128)

	private fun withStore(block: (Path, PersonalTrainingStore) -> Unit) {
		val root = Files.createTempDirectory("nekovr-personal-store")
		try {
			block(root, PersonalTrainingStore(root))
		} finally {
			Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
		}
	}
}
