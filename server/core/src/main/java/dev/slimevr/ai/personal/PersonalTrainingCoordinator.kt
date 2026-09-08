package dev.slimevr.ai.personal

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

enum class PersonalTrainingStage { IDLE, VALIDATING, INDEXING, PREPROCESSING, SPLITTING, TRAINING, EVALUATING, EXPORTING, PARITY, FINAL_VALIDATION, COMPLETED, FAILED }

data class PersonalJobRuntime(
	val job: PersonalJobMetadata,
	val version: Long = 1,
	val stage: PersonalTrainingStage = PersonalTrainingStage.IDLE,
	val progress: Float = 0f,
	val etaSeconds: Long = 0,
	val currentMetric: Double = Double.NaN,
	val bestMetric: Double = Double.NaN,
	val resourceUsage: PersonalResourceUsage? = null,
	val recoverable: Boolean = false,
	val error: String? = null,
)

interface PersonalTrainerWorker {
	fun start(job: PersonalJobMetadata, onProgress: (PersonalTrainingStage, Float, Long, Double, Double) -> Unit)
	fun pause(jobId: String)
	fun throttle(jobId: String)
	fun cancel(jobId: String)
}

class PersonalTrainingCoordinator(
	private val store: PersonalTrainingStore,
	private val worker: PersonalTrainerWorker,
	private val executor: Executor = Executors.newSingleThreadExecutor { Thread(it, "personal-trainer-controller").apply { isDaemon = true } },
) {
	private val jobs = ConcurrentHashMap<String, PersonalJobRuntime>()

	init { restore() }

	fun status(jobId: String): PersonalJobRuntime? = jobs[jobId]
	fun allStatuses(): List<PersonalJobRuntime> = jobs.values.sortedBy { it.job.createdUtc }

	@Synchronized fun register(job: PersonalJobMetadata): PersonalJobRuntime {
		require(!jobs.containsKey(job.jobId)) { "Job already exists" }
		store.saveJob(job)
		return PersonalJobRuntime(job).also { jobs[job.jobId] = it }
	}

	@Synchronized fun start(jobId: String, expectedVersion: Long): PersonalJobRuntime {
		val current = requireStatus(jobId, expectedVersion)
		require(current.job.state == PersonalJobState.CREATED || current.job.state == PersonalJobState.PAUSED || current.recoverable) { "Job cannot start" }
		val job = current.job.copy(state = PersonalJobState.RUNNING, updatedUtc = Instant.now().toString())
		store.saveJob(job)
		val next = current.copy(job = job, version = current.version + 1, stage = PersonalTrainingStage.VALIDATING, recoverable = false, error = null)
		jobs[jobId] = next
		executor.execute {
			runCatching { worker.start(job) { stage, progress, eta, metric, best -> updateProgress(jobId, stage, progress, eta, metric, best) } }
				.onSuccess { workerCompleted(jobId) }
				.onFailure { workerFailed(jobId, it) }
		}
		return next
	}

	@Synchronized fun pause(jobId: String, expectedVersion: Long): PersonalJobRuntime = mutate(jobId, expectedVersion) { current ->
		require(current.job.state == PersonalJobState.RUNNING) { "Only a running job can be paused" }
		worker.pause(jobId)
		val job = current.job.copy(state = PersonalJobState.PAUSED, updatedUtc = Instant.now().toString())
		store.saveJob(job); current.copy(job = job, version = current.version + 1, recoverable = job.activeCheckpointId != null)
	}

	@Synchronized fun cancel(jobId: String, expectedVersion: Long): PersonalJobRuntime = mutate(jobId, expectedVersion) { current ->
		require(current.job.state !in setOf(PersonalJobState.CANCELLED, PersonalJobState.COMPLETED)) { "Job is already terminal" }
		worker.cancel(jobId)
		val job = current.job.copy(state = PersonalJobState.CANCELLED, updatedUtc = Instant.now().toString())
		store.saveJob(job); current.copy(job = job, version = current.version + 1, recoverable = false)
	}

	@Synchronized fun applyResources(jobId: String, expectedVersion: Long, budget: PersonalResourceBudget, usage: PersonalResourceUsage): PersonalJobRuntime =
		when (PersonalResourceGovernor.decide(budget, usage)) {
			ResourceAction.RUN -> recordUsage(jobId, expectedVersion, usage)
			ResourceAction.THROTTLE -> recordUsage(jobId, expectedVersion, usage).also { worker.throttle(jobId) }
			ResourceAction.CHECKPOINT_AND_PAUSE -> pause(jobId, expectedVersion).let { paused -> paused.copy(resourceUsage = usage).also { jobs[jobId] = it } }
		}

	/** Periodic server hook: active tracking can never be starved by a background trainer. */
	@Synchronized fun enforceActiveVr(activeVr: Boolean) {
		jobs.values.filter { it.job.state == PersonalJobState.RUNNING }.forEach { current ->
			val budget = current.job.resourceBudget ?: return@forEach
			val usage = (current.resourceUsage ?: PersonalResourceUsage(0, 0, 0, 0, 0, 0f, activeVr)).copy(activeVr = activeVr)
			when (PersonalResourceGovernor.decide(budget, usage)) {
				ResourceAction.RUN -> Unit
				ResourceAction.THROTTLE -> worker.throttle(current.job.jobId)
				ResourceAction.CHECKPOINT_AND_PAUSE -> runCatching { pause(current.job.jobId, current.version) }
			}
		}
	}

	@Synchronized private fun recordUsage(jobId: String, expectedVersion: Long, usage: PersonalResourceUsage): PersonalJobRuntime {
		val current = requireStatus(jobId, expectedVersion)
		return current.copy(version = current.version + 1, resourceUsage = usage).also { jobs[jobId] = it }
	}

	@Synchronized private fun updateProgress(jobId: String, stage: PersonalTrainingStage, progress: Float, eta: Long, metric: Double, best: Double) {
		val current = jobs[jobId] ?: return
		if (current.job.state != PersonalJobState.RUNNING) return
		jobs[jobId] = current.copy(version = current.version + 1, stage = stage, progress = progress.coerceIn(0f, 1f), etaSeconds = eta.coerceAtLeast(0), currentMetric = metric, bestMetric = best)
	}

	@Synchronized private fun workerFailed(jobId: String, error: Throwable) {
		val current = jobs[jobId] ?: return
		val recoverable = current.job.activeCheckpointId?.let { checkpointId ->
			runCatching { store.loadCheckpoint(current.job.profileId, jobId, checkpointId) }.getOrNull()?.resumable == true
		} == true
		val job = current.job.copy(state = if (recoverable) PersonalJobState.PAUSED else PersonalJobState.FAILED, updatedUtc = Instant.now().toString())
		store.saveJob(job)
		jobs[jobId] = current.copy(job = job, version = current.version + 1, stage = PersonalTrainingStage.FAILED, recoverable = recoverable, error = error.message ?: error.javaClass.simpleName)
	}

	@Synchronized private fun workerCompleted(jobId: String) {
		val current = jobs[jobId] ?: return
		if (current.job.state != PersonalJobState.RUNNING) return
		val job = current.job.copy(state = PersonalJobState.COMPLETED, updatedUtc = Instant.now().toString())
		store.saveJob(job)
		jobs[jobId] = current.copy(job = job, version = current.version + 1, stage = PersonalTrainingStage.COMPLETED, progress = 1f, etaSeconds = 0)
	}

	private fun restore() {
		store.listProfiles().forEach { profile -> store.listJobs(profile.profileId).forEach { job ->
			val interrupted = job.state == PersonalJobState.RUNNING
			val validCheckpoint = job.activeCheckpointId?.let { checkpointId ->
				runCatching { store.loadCheckpoint(job.profileId, job.jobId, checkpointId) }.getOrNull()?.resumable == true
			} == true
			val restored = if (interrupted) job.copy(state = if (validCheckpoint) PersonalJobState.PAUSED else PersonalJobState.FAILED, updatedUtc = Instant.now().toString()) else job
			if (interrupted) store.saveJob(restored)
			jobs[restored.jobId] = PersonalJobRuntime(restored, recoverable = validCheckpoint && restored.state == PersonalJobState.PAUSED, error = if (interrupted) "Trainer stopped during previous application run" else null)
		} }
	}

	private fun requireStatus(jobId: String, version: Long) = jobs[jobId]?.also { require(it.version == version) { "Job version conflict" } } ?: error("Job not found")
	private inline fun mutate(jobId: String, version: Long, block: (PersonalJobRuntime) -> PersonalJobRuntime): PersonalJobRuntime = block(requireStatus(jobId, version)).also { jobs[jobId] = it }
}
