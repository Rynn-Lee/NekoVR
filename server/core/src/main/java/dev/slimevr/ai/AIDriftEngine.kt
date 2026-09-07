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

class AIDriftEngine(
	val config: AIModelConfig = AIModelConfig(),
	private val modelsDir: File = File("models/ai"),
	private val runtimeFactory: () -> OnnxRuntimeBackend = { JavaOnnxRuntimeBackend() },
) : DriftCorrectionSource, AutoCloseable {

	val modelManager = RemoteModelManager(modelsDir)
	@Volatile
	private var runtime: OnnxRuntimeBackend? = null
	@Volatile
	private var activeModel: ActiveModel? = null
	private var configuredMappings: List<TrackerSlotMapping> = emptyList()
	private val latestLiveSamples = ConcurrentHashMap<Int, InferenceTrackerSample>()
	private val inferenceSequence = AtomicLong()
	@Volatile
	var lastLoadError: ModelLoadException? = null
		private set

	val currentProvider: ExecutionProviderType?
		get() = activeModel?.provider

	val activeStatus: ActiveModelStatus?
		get() = activeModel?.let {
			ActiveModelStatus(
				it.metadata.modelId, it.metadata.modelVersion, it.metadata.modelSha256, it.provider,
				it.runtimePackage.flavor, it.runtimePackage.version,
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
			val failure = if (error is ModelLoadException) error else ModelLoadException(
				ModelLoadErrorCode.RUNTIME_UNAVAILABLE,
				"Could not initialize ONNX Runtime: ${error.message}",
				error,
			)
			lastLoadError = failure
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
					old
				}
				candidate = null
				latestLiveSamples.clear()
				previous?.close()
				LogManager.info("[AI] Activated ${metadata.modelId}@${metadata.modelVersion} on $provider after successful probe")
				return ModelActivationResult(true, provider, metadata.modelId, metadata.modelSha256, attempts = attempts)
			} catch (error: Throwable) {
				candidate?.close()
				val failure = if (error is ModelLoadException) error else ModelLoadException(
					ModelLoadErrorCode.SESSION_CREATION_FAILED,
					"$provider activation failed: ${error.message}",
					error,
				)
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
	}

	fun currentMappings(): List<TrackerSlotMapping> = activeModel?.worker?.mappings() ?: synchronized(this) { configuredMappings }

	fun submitInferenceSnapshot(snapshot: InferenceSnapshot): Boolean = activeModel?.worker?.submit(snapshot) ?: false

	fun latestInference(trackerId: Int, epoch: Long): TrackerInferenceOutput? = activeModel?.worker?.latest(trackerId, epoch)

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
	): Quaternion = rawRotation * correctionFor(trackerId, rawRotation, acceleration).correction

	override fun correctionFor(
		trackerId: Int,
		preAiRotation: Quaternion,
		acceleration: Vector3,
		epoch: Long,
	): DriftCorrectionResult {
		val active = activeModel
		if (config.enabled && active != null) {
			val mapping = active.worker.mappings().firstOrNull { it.trackerId == trackerId }
			if (mapping != null) {
				val canonical = floatArrayOf(
					preAiRotation.x, preAiRotation.y, preAiRotation.z, preAiRotation.w,
					acceleration.x, acceleration.y, acceleration.z,
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
						sequence = inferenceSequence.incrementAndGet(), monotonicNanos = System.nanoTime(),
						deltaTimeSeconds = 0.02f, samples = active.worker.mappings().mapNotNull { latestLiveSamples[it.trackerId] },
					),
				)
			}
		}
		val latest = active?.worker?.latest(trackerId, epoch)
		val rejection = when {
			!config.enabled -> "DISABLED"
			runtime == null -> "RUNTIME_UNAVAILABLE"
			active == null -> "MODEL_NOT_ACTIVE"
			active.worker.mappings().none { it.trackerId == trackerId } -> "UNMAPPED_TRACKER"
			latest == null -> "INFERENCE_WORKER_PENDING"
			else -> "SAFETY_GATING_PENDING"
		}
		return DriftCorrectionResult(
			rejectionReason = rejection,
			provider = active?.provider?.name,
			historyValid = latest != null,
			latencyMicros = latest?.latencyMicros,
			epoch = epoch,
		)
	}

	override fun resetHistory(trackerId: Int, epoch: Long) {
		latestLiveSamples.remove(trackerId)
		activeModel?.worker?.resetHistory(trackerId, epoch)
	}

	private fun failed(error: ModelLoadException, attempts: List<ProviderAttempt> = emptyList()): ModelActivationResult {
		lastLoadError = error
		LogManager.warning("[AI] Model activation failed (${error.code}): ${error.message}")
		return ModelActivationResult(false, failure = error, attempts = attempts)
	}

	@Synchronized
	override fun close() {
		val previous = activeModel
		activeModel = null
		latestLiveSamples.clear()
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
