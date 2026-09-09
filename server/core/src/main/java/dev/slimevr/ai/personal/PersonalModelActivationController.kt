package dev.slimevr.ai.personal

import dev.slimevr.ai.AIDriftEngine
import dev.slimevr.ai.ExecutionProviderType
import dev.slimevr.ai.ModelArtifactKind
import dev.slimevr.ai.RemoteModelManager
import dev.slimevr.ai.TrackerSlotMapping

data class PersonalActivationArtifact(
	val modelSha256: String,
	val kind: ModelArtifactKind,
	val profileId: String?,
)

interface PersonalActivationBackend {
	val activeModelSha256: String?
	val activeProvider: ExecutionProviderType?
	fun currentMappings(): List<TrackerSlotMapping>
	fun resolve(modelSha256: String): PersonalActivationArtifact?
	fun compatibilityError(modelSha256: String, mappings: List<TrackerSlotMapping>): String?
	fun load(modelSha256: String, provider: ExecutionProviderType)
	fun unload()
	fun setCorrectionMode(enabled: Boolean, shadow: Boolean)
	fun resetInferenceHistory()
	fun recordActivation(modelSha256: String, previousModelSha256: String?)
}

class EnginePersonalActivationBackend(
	private val engine: AIDriftEngine,
	private val manager: RemoteModelManager = engine.modelManager,
) : PersonalActivationBackend {
	override val activeModelSha256: String?
		get() = engine.activeStatus?.modelSha256
	override val activeProvider: ExecutionProviderType?
		get() = engine.currentProvider
	override fun currentMappings(): List<TrackerSlotMapping> = engine.currentMappings()
	override fun resolve(modelSha256: String): PersonalActivationArtifact? = manager.resolve(modelSha256)?.metadata?.let {
		PersonalActivationArtifact(it.modelSha256, it.kind, it.profileId)
	}
	override fun compatibilityError(modelSha256: String, mappings: List<TrackerSlotMapping>): String? {
		val artifact = manager.resolve(modelSha256) ?: return "Managed model was not found"
		return manager.history.compatibilityError(artifact, mappings, engine.config.contextFrames)
	}
	override fun load(modelSha256: String, provider: ExecutionProviderType) {
		val artifact = manager.resolve(modelSha256) ?: error("Managed model was not found")
		val result = engine.loadModel(artifact.modelPath, artifact.sidecarPath, provider)
		if (!result.activated) throw result.failure ?: IllegalStateException("Personal model activation failed")
	}
	override fun unload() = engine.unloadModel()
	override fun setCorrectionMode(enabled: Boolean, shadow: Boolean) {
		engine.config.enabled = enabled
		engine.config.shadowMode = shadow
	}
	override fun resetInferenceHistory() = engine.configureMappings(engine.currentMappings())
	override fun recordActivation(modelSha256: String, previousModelSha256: String?) {
		val artifact = manager.resolve(modelSha256) ?: error("Managed model was not found")
		manager.history.recordActivation(artifact, previousModelSha256)
	}
}

data class PersonalActivationStatus(
	val personalModelSha256: String?,
	val baseModelSha256: String?,
	val previousModelSha256: String?,
	val shadow: Boolean,
)

/** Transactional personal-model activation with explicit shadow promotion and topology fallback. */
class PersonalModelActivationController(private val backend: PersonalActivationBackend) {
	private var status = PersonalActivationStatus(null, null, null, false)
	private var previousProvider: ExecutionProviderType? = null
	private var personalProvider: ExecutionProviderType? = null

	@Synchronized
	fun status(): PersonalActivationStatus = status

	@Synchronized
	fun activate(metadata: PersonalModelMetadata, provider: ExecutionProviderType, shadow: Boolean): PersonalActivationStatus {
		val candidate = backend.resolve(metadata.modelSha256) ?: error("Validated personal model was not imported")
		require(candidate.kind == ModelArtifactKind.PERSONAL && candidate.profileId == metadata.profileId) {
			"Personal model ownership does not match its validated metadata"
		}
		backend.compatibilityError(candidate.modelSha256, backend.currentMappings())?.let { throw IllegalArgumentException(it) }
		val previousHash = backend.activeModelSha256
		val previousExecutionProvider = backend.activeProvider
		try {
			backend.load(candidate.modelSha256, provider)
			backend.resetInferenceHistory()
			backend.setCorrectionMode(enabled = true, shadow = shadow)
			backend.recordActivation(candidate.modelSha256, previousHash)
		} catch (error: Throwable) {
			restore(previousHash, previousExecutionProvider)
			throw error
		}
		previousProvider = previousExecutionProvider
		personalProvider = provider
		status = PersonalActivationStatus(candidate.modelSha256, metadata.baseModelSha256, previousHash, shadow)
		return status
	}

	@Synchronized
	fun finishShadow(passed: Boolean): PersonalActivationStatus {
		require(status.personalModelSha256 != null && status.shadow) { "No personal shadow activation is pending" }
		if (!passed) return rollback()
		backend.resetInferenceHistory()
		backend.setCorrectionMode(enabled = true, shadow = false)
		status = status.copy(shadow = false)
		return status
	}

	@Synchronized
	fun topologyChanged(mappings: List<TrackerSlotMapping>): PersonalActivationStatus {
		val personalHash = status.personalModelSha256 ?: return status
		if (backend.compatibilityError(personalHash, mappings) == null) return status
		return activateFallback(mappings)
	}

	@Synchronized
	fun rollback(): PersonalActivationStatus {
		val currentHash = backend.activeModelSha256
		val mappings = backend.currentMappings()
		val previousHash = status.previousModelSha256
		val targetHash = previousHash?.takeIf { backend.resolve(it) != null && backend.compatibilityError(it, mappings) == null }
			?: status.baseModelSha256?.takeIf { backend.resolve(it) != null && backend.compatibilityError(it, mappings) == null }
		if (targetHash == null) {
			backend.unload()
			backend.setCorrectionMode(enabled = false, shadow = false)
		} else {
			val provider = if (targetHash == previousHash) previousProvider else personalProvider ?: ExecutionProviderType.AUTO
			backend.load(targetHash, provider ?: ExecutionProviderType.AUTO)
			backend.resetInferenceHistory()
			backend.setCorrectionMode(enabled = true, shadow = false)
			backend.recordActivation(targetHash, currentHash)
		}
		status = PersonalActivationStatus(null, null, null, false)
		return status
	}

	private fun activateFallback(mappings: List<TrackerSlotMapping>): PersonalActivationStatus {
		val currentHash = backend.activeModelSha256
		val baseHash = status.baseModelSha256
		val fallback = baseHash?.takeIf { backend.resolve(it) != null && backend.compatibilityError(it, mappings) == null }
		if (fallback == null) {
			backend.unload()
			backend.setCorrectionMode(enabled = false, shadow = false)
		} else {
			backend.load(fallback, personalProvider ?: ExecutionProviderType.AUTO)
			backend.resetInferenceHistory()
			backend.setCorrectionMode(enabled = true, shadow = false)
			backend.recordActivation(fallback, currentHash)
		}
		status = PersonalActivationStatus(null, null, null, false)
		return status
	}

	private fun restore(modelSha256: String?, provider: ExecutionProviderType?) {
		if (modelSha256 != null) {
			runCatching { backend.load(modelSha256, provider ?: ExecutionProviderType.AUTO) }
		} else {
			backend.unload()
		}
		backend.resetInferenceHistory()
		backend.setCorrectionMode(enabled = modelSha256 != null, shadow = false)
	}
}
