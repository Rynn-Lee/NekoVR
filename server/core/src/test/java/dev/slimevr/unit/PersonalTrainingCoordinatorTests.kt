package dev.slimevr.unit

import dev.slimevr.ai.personal.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

class PersonalTrainingCoordinatorTests {
	@TempDir lateinit var directory: Path

	@Test fun `restart restores interrupted job from checkpoint as paused`() {
		val store = storeWithProfile()
		store.saveJob(job(PersonalJobState.RUNNING, "checkpoint-1"))
		store.saveCheckpoint(PersonalCheckpointMetadata(checkpointId = "checkpoint-1", jobId = "job-1", profileId = "profile-1", baseModelSha256 = "a".repeat(64), epoch = 2, globalStep = 10, artifact = PersonalArtifactReference("checkpoint.bin", "e".repeat(64), 10), optimizerStateSha256 = "f".repeat(64), createdUtc = Instant.now().toString(), resumable = true, provenance = PersonalProvenance("test")))
		val restored = PersonalTrainingCoordinator(store, worker(), ExecutorDirect).status("job-1")!!
		assertEquals(PersonalJobState.PAUSED, restored.job.state)
		assertTrue(restored.recoverable)
		assertEquals("checkpoint-1", restored.job.activeCheckpointId)
	}

	@Test fun `worker crash is isolated and persisted without touching correction`() {
		val store = storeWithProfile()
		var correctionTicks = 0
		val coordinator = PersonalTrainingCoordinator(store, worker { throw IllegalStateException("worker crashed") }, ExecutorDirect)
		coordinator.register(job(PersonalJobState.CREATED))
		coordinator.start("job-1", 1)
		correctionTicks++
		val failed = coordinator.status("job-1")!!
		assertEquals(PersonalJobState.FAILED, failed.job.state)
		assertEquals(1, correctionTicks)
		assertTrue(failed.error!!.contains("worker crashed"))
		assertEquals(PersonalJobState.FAILED, store.loadJob("profile-1", "job-1").state)
	}

	@Test fun `active VR automatically pauses a budgeted running job`() {
		val store = storeWithProfile()
		var pauses = 0
		val worker = object : PersonalTrainerWorker {
			override fun start(job: PersonalJobMetadata, onProgress: (PersonalTrainingStage, Float, Long, Double, Double) -> Unit) = Unit
			override fun pause(jobId: String) { pauses++ }
			override fun throttle(jobId: String) = Unit
			override fun cancel(jobId: String) = Unit
		}
		val coordinator = PersonalTrainingCoordinator(store, worker, java.util.concurrent.Executor { })
		coordinator.register(job(PersonalJobState.CREATED).copy(resourceBudget = PersonalResourceBudget(4, 4096, 70, 8192, 8192, 32f)))
		coordinator.start("job-1", 1)
		coordinator.enforceActiveVr(true)
		assertEquals(1, pauses)
		assertEquals(PersonalJobState.PAUSED, coordinator.status("job-1")!!.job.state)
	}

	private fun storeWithProfile() = PersonalTrainingStore(directory).also { store ->
		val now = Instant.now().toString()
		store.saveProfile(PersonalProfileMetadata(profileId = "profile-1", localPseudonym = "local", createdUtc = now, updatedUtc = now, bodyProportionsMeters = emptyMap(), compatibility = PersonalCompatibility(emptySet(), emptyList(), emptySet()), provenance = PersonalProvenance("test")))
	}
	private fun job(state: PersonalJobState, checkpoint: String? = null): PersonalJobMetadata {
		val now = Instant.now().toString()
		return PersonalJobMetadata(jobId = "job-1", profileId = "profile-1", baseModelSha256 = "a".repeat(64), featureSchemaSha256 = "b".repeat(64), selectedSessionHashes = setOf("c".repeat(64)), settingsSha256 = "d".repeat(64), state = state, createdUtc = now, updatedUtc = now, activeCheckpointId = checkpoint, provenance = PersonalProvenance("test"))
	}
	private fun worker(start: (() -> Unit)? = null) = object : PersonalTrainerWorker {
		override fun start(job: PersonalJobMetadata, onProgress: (PersonalTrainingStage, Float, Long, Double, Double) -> Unit) { start?.invoke() }
		override fun pause(jobId: String) = Unit
		override fun throttle(jobId: String) = Unit
		override fun cancel(jobId: String) = Unit
	}
	private object ExecutorDirect : java.util.concurrent.Executor { override fun execute(command: Runnable) = command.run() }
}
