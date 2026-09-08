package dev.slimevr.ai

import io.eiren.util.logging.LogManager
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class ProviderAttempt(
	val provider: ExecutionProviderType,
	val errorCode: ModelLoadErrorCode,
	val message: String,
)

data class ModelActivationResult(
	val activated: Boolean,
	val provider: ExecutionProviderType? = null,
	val modelId: String? = null,
	val modelSha256: String? = null,
	val failure: ModelLoadException? = null,
	val attempts: List<ProviderAttempt> = emptyList(),
)

data class ActiveModelStatus(
	val modelId: String,
	val modelVersion: String,
	val modelSha256: String,
	val provider: ExecutionProviderType,
	val runtimeFlavor: String,
	val runtimeVersion: String,
)

enum class AIModelLoadState {
	RUNTIME_UNAVAILABLE,
	UNLOADED,
	LOADING,
	ACTIVE,
	ERROR,
}

enum class AIRuntimeHealthState {
	DISABLED,
	UNAVAILABLE,
	HEALTHY,
	DEGRADED,
	WATCHDOG_TRIPPED,
}

data class AIRuntimeError(
	val code: String,
	val message: String,
)

data class TrackerRuntimeStatus(
	val trackerId: Int,
	val bodyRoleId: Int,
	val slot: Int,
	val epoch: Long?,
	val confidence: Float?,
	val correctionApplied: Boolean,
	val rejectionReason: String?,
)

data class AIRuntimeMetrics(
	val latency: InferenceLatencyPercentiles = InferenceLatencyPercentiles(),
	val queueWaitLatency: InferenceLatencyPercentiles = InferenceLatencyPercentiles(),
	val safetyGateLatency: InferenceLatencyPercentiles = InferenceLatencyPercentiles(),
	val queueDepth: Int = 0,
	val queueDrops: Long = 0L,
	val processedInferences: Long = 0L,
	val inferenceRateHz: Double = 0.0,
	val staleResults: Long = 0L,
	val inferenceErrors: Long = 0L,
	val outliers: Long = 0L,
	val confidenceMinimum: Float? = null,
	val confidenceMean: Float? = null,
	val confidenceMaximum: Float? = null,
)

data class AIRuntimeStatus(
	val health: AIRuntimeHealthState,
	val loadState: AIModelLoadState,
	val activeModel: ActiveModelStatus?,
	val metrics: AIRuntimeMetrics,
	val trackers: List<TrackerRuntimeStatus>,
	val lastError: AIRuntimeError?,
)

class AIDriftEngine(
	val config: AIModelConfig = AIModelConfig(),
	private val modelsDir: File = File("models/ai"),
	private val runtimeFactory: () -> OnnxRuntimeBackend = { JavaOnnxRuntimeBackend() },
	private val activeCorrectionAuthorization: (String) -> ActiveCorrectionAuthorization = {
		ActiveCorrectionAuthorization(true, null)
	},
) : DriftCorrectionSource,
	AutoCloseable {

	val modelManager = RemoteModelManager(modelsDir)

	@Volatile
	private var runtime: OnnxRuntimeBackend? = null

	@Volatile
	private var activeModel: ActiveModel? = null
	private var configuredMappings: List<TrackerSlotMapping> = emptyList()
	private val latestLiveSamples = ConcurrentHashMap<Int, InferenceTrackerSample>()
	private val inferenceSequence = AtomicLong()
	private val safetyGate = CorrectionSafetyGate(config)
	private val staleResultCount = AtomicLong()
	private val outlierCount = AtomicLong()
	private val safetyGateLatencySamplesMicros = ArrayDeque<Long>(256)
	private val trackerRejections = ConcurrentHashMap<Int, String?>()
	private val trackerApplied = ConcurrentHashMap<Int, Boolean>()

	@Volatile
	private var watchdogTripped = false

	@Volatile
	private var loadState = AIModelLoadState.UNLOADED

	@Volatile
	var lastLoadError: ModelLoadException? = null
		private set

	val currentProvider: ExecutionProviderType?
		get() = activeModel?.provider

	val activeStatus: ActiveModelStatus?
		get() = activeModel?.let {
			ActiveModelStatus(
				it.metadata.modelId,
				it.metadata.modelVersion,
				it.metadata.modelSha256,
				it.provider,
				it.runtimePackage.flavor,
				it.runtimePackage.version,
			)
		}

	init {
		initializeEngine()
	}

	@Synchronized
	fun initializeEngine(): Boolean {
		if (runtime != null) return true
		return try {
			runtime = runtimeFactory()
			lastLoadError = null
			LogManager.info("[AI] Initialized packaged ONNX Runtime ${runtime!!.runtimePackage.version} (${runtime!!.runtimePackage.flavor})")
			true
		} catch (error: Throwable) {
			val failure = if (error is ModelLoadException) {
				error
			} else {
				ModelLoadException(
					ModelLoadErrorCode.RUNTIME_UNAVAILABLE,
					"Could not initialize ONNX Runtime: ${error.message}",
					error,
				)
			}
			lastLoadError = failure
			loadState = AIModelLoadState.RUNTIME_UNAVAILABLE
			LogManager.warning("[AI] ${failure.message}. AI correction remains disabled.")
			false
		}
	}

	fun loadModel(
		modelPath: Path,
		sidecarPath: Path,
		requestedProvider: ExecutionProviderType = ExecutionProviderType.AUTO,
		expectedFeatureSchemaSha256: String? = null,
	): ModelActivationResult {
		loadState = AIModelLoadState.LOADING
		val backend = runtime ?: return failed(ModelLoadException(ModelLoadErrorCode.RUNTIME_UNAVAILABLE, "ONNX Runtime is unavailable"))
		val metadata = try {
			ModelArtifactValidator.validate(modelPath, sidecarPath, expectedFeatureSchemaSha256)
		} catch (error: ModelLoadException) {
			return failed(error)
		}
		val providers = if (requestedProvider == ExecutionProviderType.AUTO) {
			listOf(ExecutionProviderType.TENSORRT, ExecutionProviderType.CUDA, ExecutionProviderType.DIRECTML, ExecutionProviderType.CPU)
		} else {
			listOf(requestedProvider)
		}
		val attempts = mutableListOf<ProviderAttempt>()
		for (provider in providers) {
			var candidate: LoadedModelSession? = null
			try {
				candidate = backend.createSession(modelPath, provider)
				ModelArtifactValidator.validateTensorContract(metadata, candidate.inputInfo, candidate.outputInfo)
				candidate.runProbe(metadata)
				candidate.runProbe(metadata)
				val worker = LatestValueInferenceWorker(candidate, metadata, synchronized(this) { configuredMappings })
				val replacement = ActiveModel(candidate, worker, metadata, provider, backend.runtimePackage)
				val previous = synchronized(this) {
					val old = activeModel
					activeModel = replacement
					lastLoadError = null
					config.activeProvider = provider
					loadState = AIModelLoadState.ACTIVE
					watchdogTripped = false
					old
				}
				candidate = null
				latestLiveSamples.clear()
				safetyGate.clear()
				trackerRejections.clear()
				trackerApplied.clear()
				previous?.close()
				LogManager.info("[AI] Activated ${metadata.modelId}@${metadata.modelVersion} on $provider after successful probe")
				return ModelActivationResult(true, provider, metadata.modelId, metadata.modelSha256, attempts = attempts)
			} catch (error: Throwable) {
				candidate?.close()
				val failure = if (error is ModelLoadException) {
					error
				} else {
					ModelLoadException(
						ModelLoadErrorCode.SESSION_CREATION_FAILED,
						"$provider activation failed: ${error.message}",
						error,
					)
				}
				attempts += ProviderAttempt(provider, failure.code, failure.message ?: failure.code.name)
				if (requestedProvider != ExecutionProviderType.AUTO) return failed(failure, attempts)
			}
		}
		return failed(
			ModelLoadException(ModelLoadErrorCode.PROVIDER_UNAVAILABLE, "No packaged execution provider successfully ran the model probe"),
			attempts,
		)
	}

	@Synchronized
	fun unloadModel() {
		val previous = activeModel
		activeModel = null
		latestLiveSamples.clear()
		safetyGate.clear()
		trackerRejections.clear()
		trackerApplied.clear()
		watchdogTripped = false
		loadState = if (runtime == null) AIModelLoadState.RUNTIME_UNAVAILABLE else AIModelLoadState.UNLOADED
		previous?.close()
	}

	@Synchronized
	fun configureMappings(mappings: List<TrackerSlotMapping>) {
		if (activeModel == null) {
			require(mappings.map { it.trackerId }.toSet().size == mappings.size) { "Tracker IDs must be unique" }
			require(mappings.map { it.slot }.toSet().size == mappings.size) { "Slots must be unique" }
		} else {
			activeModel!!.worker.configureMappings(mappings)
		}
		configuredMappings = mappings.toList()
		latestLiveSamples.clear()
		safetyGate.clear()
		trackerRejections.clear()
		trackerApplied.clear()
	}

	fun currentMappings(): List<TrackerSlotMapping> = activeModel?.worker?.mappings() ?: synchronized(this) { configuredMappings }

	fun submitInferenceSnapshot(snapshot: InferenceSnapshot): Boolean = activeModel?.worker?.submit(snapshot) ?: false

	fun latestInference(trackerId: Int, epoch: Long): TrackerInferenceOutput? = activeModel?.worker?.latest(trackerId, epoch)

	override fun legacyDriftCompensationMode(trackerId: Int): LegacyDriftCompensationMode {
		val active = activeModel ?: return LegacyDriftCompensationMode.COMPOSE
		val authorized = activeCorrectionAuthorization(active.metadata.modelSha256).allowed
		return if (config.enabled && !config.shadowMode && authorized && !watchdogTripped && active.worker.mappings().any { it.trackerId == trackerId }) {
			config.legacyDriftCompensationMode
		} else {
			LegacyDriftCompensationMode.COMPOSE
		}
	}

	fun applyPreset(preset: AIPreset) {
		config.activePreset = preset
		if (preset != AIPreset.CUSTOM) {
			config.intensity = preset.intensity
			config.smoothing = preset.smoothing
		}
	}

	fun correctRotation(
		trackerId: Int,
		rawRotation: Quaternion,
		acceleration: Vector3 = Vector3.NULL,
		hmdRotation: Quaternion? = null,
		deltaTimeSeconds: Float = 0.02f,
	): Quaternion = correctionFor(trackerId, rawRotation, acceleration).correction * rawRotation

	override fun correctionFor(
		trackerId: Int,
		preAiRotation: Quaternion,
		acceleration: Vector3,
		epoch: Long,
	): DriftCorrectionResult {
		val active = activeModel
		val rotationNormSquared = preAiRotation.w *
			preAiRotation.w +
			preAiRotation.x *
			preAiRotation.x +
			preAiRotation.y *
			preAiRotation.y +
			preAiRotation.z *
			preAiRotation.z
		val inputFinite = preAiRotation.w.isFinite() &&
			preAiRotation.x.isFinite() &&
			preAiRotation.y.isFinite() &&
			preAiRotation.z.isFinite() &&
			rotationNormSquared.isFinite() &&
			rotationNormSquared > 1e-8f &&
			acceleration.x.isFinite() &&
			acceleration.y.isFinite() &&
			acceleration.z.isFinite()
		if (config.enabled && active != null && inputFinite) {
			val mapping = active.worker.mappings().firstOrNull { it.trackerId == trackerId }
			if (mapping != null) {
				val canonical = floatArrayOf(
					preAiRotation.x,
					preAiRotation.y,
					preAiRotation.z,
					preAiRotation.w,
					acceleration.x,
					acceleration.y,
					acceleration.z,
				)
				val features = FloatArray(active.metadata.featureCount)
				val validity = BooleanArray(active.metadata.featureCount)
				for (index in 0 until minOf(canonical.size, features.size)) {
					features[index] = canonical[index]
					validity[index] = canonical[index].isFinite()
				}
				latestLiveSamples[trackerId] = InferenceTrackerSample(trackerId, epoch, features, validity)
				active.worker.submit(
					InferenceSnapshot(
						sequence = inferenceSequence.incrementAndGet(),
						monotonicNanos = System.nanoTime(),
						deltaTimeSeconds = 0.02f,
						samples = active.worker.mappings().mapNotNull { latestLiveSamples[it.trackerId] },
					),
				)
			}
		}
		val rejection = when {
			!config.enabled -> "DISABLED"
			runtime == null -> "RUNTIME_UNAVAILABLE"
			active == null -> "MODEL_NOT_ACTIVE"
			active.worker.mappings().none { it.trackerId == trackerId } -> "UNMAPPED_TRACKER"
			!inputFinite -> "INVALID_TRACKER_INPUT"
			else -> null
		}
		if (rejection != null) {
			safetyGate.reset(trackerId)
			trackerRejections[trackerId] = rejection
			trackerApplied[trackerId] = false
			return DriftCorrectionResult(rejectionReason = rejection, provider = active?.provider?.name, epoch = epoch)
		}
		checkNotNull(active)
		if (active.worker.consecutiveInferenceErrorCount >= config.watchdogFailureThreshold.coerceAtLeast(1)) {
			watchdogTripped = true
		}
		if (watchdogTripped) {
			safetyGate.reset(trackerId)
			trackerRejections[trackerId] = "WATCHDOG_TRIPPED"
			trackerApplied[trackerId] = false
			return DriftCorrectionResult(
				rejectionReason = "WATCHDOG_TRIPPED",
				modelHash = active.metadata.modelSha256,
				provider = active.provider.name,
				epoch = epoch,
			)
		}
		val latest = active.worker.latest(trackerId, epoch)
		val gateStartedNanos = System.nanoTime()
		val gate = if (latest == null) {
			safetyGate.decayToIdentity(trackerId, System.nanoTime(), "INFERENCE_WORKER_PENDING")
		} else {
			safetyGate.evaluate(latest, epoch, System.nanoTime())
		}
		recordSafetyGateLatency((System.nanoTime() - gateStartedNanos) / 1_000L)
		if (gate.stale) staleResultCount.incrementAndGet()
		if (gate.outlier) outlierCount.incrementAndGet()
		gate.rejectionReason?.let { trackerRejections[trackerId] = it } ?: trackerRejections.remove(trackerId)
		val shadowed = config.shadowMode && gate.applied
		val authorization = activeCorrectionAuthorization(active.metadata.modelSha256)
		val applied = gate.applied && !config.shadowMode && authorization.allowed
		trackerApplied[trackerId] = applied
		return DriftCorrectionResult(
			correction = if (applied) gate.correction else Quaternion.IDENTITY,
			prediction = gate.prediction,
			applied = applied,
			rejectionReason = when {
				shadowed -> "SHADOW_MODE"
				gate.rejectionReason != null -> gate.rejectionReason
				!authorization.allowed -> authorization.rejectionReason
				else -> null
			},
			modelHash = active.metadata.modelSha256,
			provider = active.provider.name,
			historyValid = latest != null,
			latencyMicros = latest?.latencyMicros,
			epoch = epoch,
		)
	}

	/** One-action, synchronous rollback. The next tracker read is identity even if inference is in flight. */
	@Synchronized
	fun rollbackToIdentity() {
		config.enabled = false
		config.shadowMode = false
		latestLiveSamples.clear()
		safetyGate.clear()
		trackerRejections.keys.forEach { trackerRejections[it] = "ROLLED_BACK_TO_IDENTITY" }
		trackerApplied.keys.forEach { trackerApplied[it] = false }
	}

	override fun resetHistory(trackerId: Int, epoch: Long) {
		latestLiveSamples.remove(trackerId)
		safetyGate.reset(trackerId)
		trackerRejections[trackerId] = "RESET"
		trackerApplied[trackerId] = false
		activeModel?.worker?.resetHistory(trackerId, epoch)
	}

	fun runtimeStatus(): AIRuntimeStatus {
		val active = activeModel
		val worker = active?.worker
		val outputs = worker?.latestOutputs().orEmpty()
		val confidences = outputs.values.map { it.confidence }.filter(Float::isFinite)
		val authorization = active?.takeIf { config.enabled && !config.shadowMode }
			?.let { activeCorrectionAuthorization(it.metadata.modelSha256) }
		val lastError = when {
			worker?.lastError != null -> AIRuntimeError("INFERENCE_ERROR", worker.lastError!!)
			lastLoadError != null -> AIRuntimeError(lastLoadError!!.code.name, lastLoadError!!.message ?: lastLoadError!!.code.name)
			authorization?.allowed == false -> AIRuntimeError(authorization.rejectionReason ?: "ACTIVE_CORRECTION_GATED", "Active correction is gated")
			else -> null
		}
		val health = when {
			watchdogTripped -> AIRuntimeHealthState.WATCHDOG_TRIPPED
			runtime == null -> AIRuntimeHealthState.UNAVAILABLE
			!config.enabled || active == null -> AIRuntimeHealthState.DISABLED
			authorization?.allowed == false -> AIRuntimeHealthState.DEGRADED
			(worker?.consecutiveInferenceErrorCount ?: 0L) > 0L || lastError != null -> AIRuntimeHealthState.DEGRADED
			else -> AIRuntimeHealthState.HEALTHY
		}
		return AIRuntimeStatus(
			health = health,
			loadState = loadState,
			activeModel = activeStatus,
			metrics = AIRuntimeMetrics(
				latency = worker?.latencyPercentiles() ?: InferenceLatencyPercentiles(),
				queueWaitLatency = worker?.queueWaitLatencyPercentiles() ?: InferenceLatencyPercentiles(),
				safetyGateLatency = safetyGateLatencyPercentiles(),
				queueDepth = worker?.queueDepth ?: 0,
				queueDrops = worker?.droppedSnapshotCount ?: 0L,
				processedInferences = worker?.processedSnapshotCount ?: 0L,
				inferenceRateHz = worker?.inferenceRateHz ?: 0.0,
				staleResults = staleResultCount.get(),
				inferenceErrors = worker?.inferenceErrorCount ?: 0L,
				outliers = outlierCount.get(),
				confidenceMinimum = confidences.minOrNull(),
				confidenceMean = confidences.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
				confidenceMaximum = confidences.maxOrNull(),
			),
			trackers = active?.worker?.mappings().orEmpty().map { mapping ->
				val output = outputs[mapping.trackerId]
				TrackerRuntimeStatus(
					trackerId = mapping.trackerId,
					bodyRoleId = mapping.bodyRoleId,
					slot = mapping.slot,
					epoch = output?.epoch,
					confidence = output?.confidence,
					correctionApplied = trackerApplied[mapping.trackerId] == true,
					rejectionReason = trackerRejections[mapping.trackerId],
				)
			},
			lastError = lastError,
		)
	}

	private fun recordSafetyGateLatency(value: Long) = synchronized(safetyGateLatencySamplesMicros) {
		if (safetyGateLatencySamplesMicros.size == 256) safetyGateLatencySamplesMicros.removeFirst()
		safetyGateLatencySamplesMicros.addLast(value)
	}

	private fun safetyGateLatencyPercentiles(): InferenceLatencyPercentiles = synchronized(safetyGateLatencySamplesMicros) {
		if (safetyGateLatencySamplesMicros.isEmpty()) return@synchronized InferenceLatencyPercentiles()
		val sorted = safetyGateLatencySamplesMicros.sorted()
		fun percentile(value: Double): Long = sorted[((sorted.lastIndex * value).toInt()).coerceIn(0, sorted.lastIndex)]
		InferenceLatencyPercentiles(percentile(0.50), percentile(0.95), percentile(0.99))
	}

	private fun failed(error: ModelLoadException, attempts: List<ProviderAttempt> = emptyList()): ModelActivationResult {
		lastLoadError = error
		loadState = if (activeModel == null) AIModelLoadState.ERROR else AIModelLoadState.ACTIVE
		LogManager.warning("[AI] Model activation failed (${error.code}): ${error.message}")
		return ModelActivationResult(false, failure = error, attempts = attempts)
	}

	@Synchronized
	override fun close() {
		val previous = activeModel
		activeModel = null
		latestLiveSamples.clear()
		safetyGate.clear()
		trackerRejections.clear()
		trackerApplied.clear()
		watchdogTripped = false
		loadState = AIModelLoadState.RUNTIME_UNAVAILABLE
		previous?.close()
		runtime?.close()
		runtime = null
	}

	private data class ActiveModel(
		val session: LoadedModelSession,
		val worker: LatestValueInferenceWorker,
		val metadata: ModelArtifactMetadata,
		val provider: ExecutionProviderType,
		val runtimePackage: OnnxRuntimePackage,
	) : AutoCloseable {
		override fun close() {
			worker.close()
			session.close()
		}
	}
}
