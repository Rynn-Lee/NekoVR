package dev.slimevr.ai

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class ModelHistoryEntry(
	val modelSha256: String,
	val modelId: String,
	val modelVersion: String,
	val displayName: String,
	val kind: ModelArtifactKind,
	val profileId: String?,
	val lastUsedEpochMillis: Long,
	val pinned: Boolean,
)

data class ModelHistoryStatus(
	val entry: ModelHistoryEntry,
	val validated: Boolean,
	val compatible: Boolean,
	val compatibilityError: String?,
	val active: Boolean,
	val rollbackTarget: Boolean,
)

/** Server-owned, hash-addressed recent/pinned history. Browser filenames are never persisted. */
class ModelHistoryStore(private val manager: RemoteModelManager) {
	private val mapper = ObjectMapper()
	private val historyPath = manager.store.root.resolve("model-history-v1.json")
	private val entries = linkedMapOf<String, ModelHistoryEntry>()
	private var rollbackHash: String? = null
	var version: Long = 0
		private set

	init {
		load()
	}

	@Synchronized
	fun recordActivation(artifact: ManagedModelArtifact, previousHash: String?) {
		val metadata = artifact.metadata
		val existing = entries[metadata.modelSha256]
		entries[metadata.modelSha256] = ModelHistoryEntry(
			modelSha256 = metadata.modelSha256,
			modelId = metadata.modelId,
			modelVersion = metadata.modelVersion,
			displayName = manager.catalogName(metadata.modelSha256) ?: metadata.modelId,
			kind = metadata.kind,
			profileId = metadata.profileId,
			lastUsedEpochMillis = System.currentTimeMillis(),
			pinned = existing?.pinned == true,
		)
		rollbackHash = previousHash?.takeIf { it != metadata.modelSha256 && manager.resolve(it) != null }
		trim()
		persist()
	}

	@Synchronized
	fun pin(artifact: ManagedModelArtifact, pinned: Boolean) {
		val metadata = artifact.metadata
		val existing = entries[metadata.modelSha256]
		entries[metadata.modelSha256] = ModelHistoryEntry(
			metadata.modelSha256,
			metadata.modelId,
			metadata.modelVersion,
			manager.catalogName(metadata.modelSha256) ?: metadata.modelId,
			metadata.kind,
			metadata.profileId,
			existing?.lastUsedEpochMillis ?: 0L,
			pinned,
		)
		trim()
		persist()
	}

	@Synchronized
	fun rollbackArtifact(): ManagedModelArtifact? = rollbackHash?.let(manager::resolve)

	fun compatibilityError(artifact: ManagedModelArtifact, mappings: List<TrackerSlotMapping>, contextFrames: Int): String? =
		artifact.metadata.compatibilityError(mappings, contextFrames)

	@Synchronized
	fun statuses(activeHash: String?, mappings: List<TrackerSlotMapping>, contextFrames: Int): List<ModelHistoryStatus> =
		entries.values
			.sortedWith(compareByDescending<ModelHistoryEntry> { it.pinned }.thenByDescending { it.lastUsedEpochMillis })
			.map { entry ->
				val artifact = runCatching { manager.resolve(entry.modelSha256) }.getOrNull()
				val error = if (artifact == null) "Managed model is missing or failed validation" else artifact.metadata.compatibilityError(mappings, contextFrames)
				ModelHistoryStatus(entry, artifact != null, artifact != null && error == null, error, entry.modelSha256 == activeHash, entry.modelSha256 == rollbackHash)
			}

	private fun ModelArtifactMetadata.compatibilityError(mappings: List<TrackerSlotMapping>, contextFrames: Int): String? = when {
		contextFrames !in minimumContext..maximumContext -> "Configured context is outside model bounds ($minimumContext..$maximumContext)"
		mappings.size !in minimumSlots..maximumSlots -> "Tracker mapping count is outside model bounds ($minimumSlots..$maximumSlots)"
		mappings.any { it.slot !in 0 until maximumSlots } -> "A tracker mapping uses an unsupported model slot"
		supportedRoles.isNotEmpty() && mappings.any { it.bodyRoleId !in supportedRoles } -> "A mapped body role is unsupported by this model"
		else -> null
	}

	private fun trim() {
		entries.values.filterNot { it.pinned }.sortedByDescending { it.lastUsedEpochMillis }.drop(MAX_RECENT).forEach { entries.remove(it.modelSha256) }
	}

	private fun load() {
		if (!Files.isRegularFile(historyPath)) return
		runCatching {
			val root = mapper.readTree(historyPath.toFile())
			if (root.path("format").asText() != FORMAT || root.path("schema_version").asInt() != 1) return@runCatching
			rollbackHash = root.path("rollback_model_sha256").asText("").takeIf(String::isNotBlank)
			root.path("entries").filter { it.isObject }.forEach { node ->
				val hash = node.path("model_sha256").asText("").lowercase()
				if (!HASH.matches(hash)) return@forEach
				entries[hash] = ModelHistoryEntry(
					hash,
					node.path("model_id").asText(""),
					node.path("model_version").asText(""),
					node.path("display_name").asText(""),
					runCatching { ModelArtifactKind.valueOf(node.path("kind").asText("GLOBAL")) }.getOrDefault(ModelArtifactKind.GLOBAL),
					node.path("profile_id").asText("").takeIf(String::isNotBlank),
					node.path("last_used_epoch_millis").asLong(0),
					node.path("pinned").asBoolean(false),
				)
			}
			version = root.path("version").asLong(0)
		}
	}

	private fun persist() {
		version++
		val root = mapper.createObjectNode().apply {
			put("format", FORMAT); put("schema_version", 1); put("version", version)
			put("rollback_model_sha256", rollbackHash.orEmpty())
			set<com.fasterxml.jackson.databind.JsonNode>("entries", mapper.valueToTree(entries.values.map { entry ->
				mapOf(
					"model_sha256" to entry.modelSha256, "model_id" to entry.modelId, "model_version" to entry.modelVersion,
					"display_name" to entry.displayName, "kind" to entry.kind.name, "profile_id" to entry.profileId.orEmpty(),
					"last_used_epoch_millis" to entry.lastUsedEpochMillis, "pinned" to entry.pinned,
				)
			}))
		}
		val temporary = historyPath.resolveSibling("${historyPath.fileName}.tmp")
		Files.write(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root))
		try {
			Files.move(temporary, historyPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
		} catch (_: AtomicMoveNotSupportedException) {
			Files.move(temporary, historyPath, StandardCopyOption.REPLACE_EXISTING)
		}
	}

	companion object {
		private const val FORMAT = "nekovr-model-history-v1"
		private const val MAX_RECENT = 20
		private val HASH = Regex("^[0-9a-f]{64}$")
	}
}
