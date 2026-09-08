package dev.slimevr.ai.personal

import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.UUID
import kotlin.io.path.exists

/** Local, profile-scoped metadata storage. Raw sessions and artifacts are referenced by hash, never copied into metadata. */
class PersonalTrainingStore(root: Path) {
	val root: Path = root.toAbsolutePath().normalize()
	private val profilesRoot = this.root.resolve("profiles")
	private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false }

	init {
		ensureDirectory(this.root)
		ensureDirectory(profilesRoot)
	}

	@Synchronized
	fun createProfile(
		localPseudonym: String,
		bodyProportionsMeters: Map<String, Float>,
		compatibility: PersonalCompatibility,
		provenance: PersonalProvenance,
		now: Instant = Instant.now(),
	): PersonalProfileMetadata {
		val profile = PersonalProfileMetadata(
			profileId = "profile-${UUID.randomUUID().toString().replace("-", "")}",
			localPseudonym = localPseudonym,
			createdUtc = now.toString(),
			updatedUtc = now.toString(),
			bodyProportionsMeters = bodyProportionsMeters,
			compatibility = compatibility,
			provenance = provenance,
		)
		saveProfile(profile)
		return profile
	}

	@Synchronized
	fun saveProfile(profile: PersonalProfileMetadata) {
		validate(profile)
		val directory = profileDirectory(profile.profileId)
		ensureDirectory(directory)
		write(directory.resolve("profile.json"), PersonalProfileMetadata.serializer(), profile)
	}

	fun loadProfile(profileId: String): PersonalProfileMetadata =
		read(profileFile(profileId), PersonalProfileMetadata.serializer()).also {
			validate(it)
			require(it.profileId == profileId) { "Personal profile ownership does not match its storage path" }
		}

	fun listProfiles(): List<PersonalProfileMetadata> {
		if (!profilesRoot.exists()) return emptyList()
		return Files.list(profilesRoot).use { paths ->
			paths.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
				.map { directory -> directory to directory.resolve("profile.json") }
				.filter { (_, metadata) -> Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS) }
				.map { (directory, metadata) ->
					read(metadata, PersonalProfileMetadata.serializer()).also {
						validate(it)
						require(it.profileId == directory.fileName.toString()) {
							"Personal profile ownership does not match its storage path"
						}
					}
				}
				.sorted(compareBy(PersonalProfileMetadata::localPseudonym, PersonalProfileMetadata::profileId))
				.toList()
		}
	}

	@Synchronized
	fun saveModel(metadata: PersonalModelMetadata) {
		requireProfile(metadata.profileId)
		validate(metadata)
		val directory = scopedDirectory(metadata.profileId, "models")
		write(directory.resolve("${metadata.modelSha256}.json"), PersonalModelMetadata.serializer(), metadata)
	}

	fun loadModel(profileId: String, modelSha256: String): PersonalModelMetadata =
		read(scopedFile(profileId, "models", hashFile(modelSha256)), PersonalModelMetadata.serializer()).also {
			validate(it)
			require(it.profileId == profileId && it.modelSha256 == modelSha256) { "Personal model ownership does not match its storage path" }
		}

	@Synchronized
	fun saveJob(metadata: PersonalJobMetadata) {
		requireProfile(metadata.profileId)
		validate(metadata)
		val directory = scopedDirectory(metadata.profileId, "jobs")
		write(directory.resolve("${safeId(metadata.jobId, "jobId")}.json"), PersonalJobMetadata.serializer(), metadata)
	}

	fun loadJob(profileId: String, jobId: String): PersonalJobMetadata =
		read(scopedFile(profileId, "jobs", "${safeId(jobId, "jobId")}.json"), PersonalJobMetadata.serializer()).also {
			validate(it)
			require(it.profileId == profileId && it.jobId == jobId) { "Personal job ownership does not match its storage path" }
		}

	fun listJobs(profileId: String): List<PersonalJobMetadata> {
		requireProfile(profileId)
		val directory = scopedDirectory(profileId, "jobs")
		return Files.list(directory).use { paths ->
			paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.map {
				read(it, PersonalJobMetadata.serializer()).also { job ->
					validate(job); require(job.profileId == profileId) { "Personal job ownership does not match its storage path" }
				}
			}.sorted(compareBy(PersonalJobMetadata::createdUtc, PersonalJobMetadata::jobId)).toList()
		}
	}

	@Synchronized
	fun saveCheckpoint(metadata: PersonalCheckpointMetadata) {
		requireProfile(metadata.profileId)
		validate(metadata)
		val job = loadJob(metadata.profileId, metadata.jobId)
		require(job.baseModelSha256 == metadata.baseModelSha256) { "Checkpoint base model differs from its job" }
		val directory = scopedDirectory(metadata.profileId, "checkpoints").resolve(safeId(metadata.jobId, "jobId"))
		ensureDirectory(directory)
		write(directory.resolve("${safeId(metadata.checkpointId, "checkpointId")}.json"), PersonalCheckpointMetadata.serializer(), metadata)
	}

	fun loadCheckpoint(profileId: String, jobId: String, checkpointId: String): PersonalCheckpointMetadata = read(
		scopedFile(profileId, "checkpoints", "${safeId(jobId, "jobId")}/${safeId(checkpointId, "checkpointId")}.json"),
		PersonalCheckpointMetadata.serializer(),
	).also {
		validate(it)
		require(it.profileId == profileId && it.jobId == jobId && it.checkpointId == checkpointId) {
			"Checkpoint ownership does not match its storage path"
		}
	}

	@Synchronized
	fun saveCache(metadata: PersonalCacheMetadata) {
		requireProfile(metadata.profileId)
		validate(metadata)
		val directory = scopedDirectory(metadata.profileId, "cache")
		write(directory.resolve("${safeId(metadata.cacheId, "cacheId")}.json"), PersonalCacheMetadata.serializer(), metadata)
	}

	fun loadCache(profileId: String, cacheId: String): PersonalCacheMetadata =
		read(scopedFile(profileId, "cache", "${safeId(cacheId, "cacheId")}.json"), PersonalCacheMetadata.serializer()).also {
			validate(it)
			require(it.profileId == profileId && it.cacheId == cacheId) { "Personal cache ownership does not match its storage path" }
		}

	private fun validate(profile: PersonalProfileMetadata) {
		require(profile.format == "nekovr-personal-profile-v1" && profile.schemaVersion == PERSONAL_METADATA_SCHEMA_VERSION) { "Unsupported personal profile schema" }
		safeId(profile.profileId, "profileId")
		require(profile.localPseudonym.isNotBlank() && profile.localPseudonym.length <= 80 && profile.localPseudonym.none(Char::isISOControl)) { "Local pseudonym is invalid" }
		require(profile.bodyProportionsMeters.size <= 64) { "Too many body proportions" }
		profile.bodyProportionsMeters.forEach { (name, value) ->
			require(FIELD.matches(name) && value.isFinite() && value in 0.01f..3.0f) { "Body proportion is invalid" }
		}
		instant(profile.createdUtc); instant(profile.updatedUtc)
		validate(profile.compatibility); validate(profile.provenance)
	}

	private fun validate(metadata: PersonalModelMetadata) {
		require(metadata.format == "nekovr-personal-model-v1" && metadata.schemaVersion == PERSONAL_METADATA_SCHEMA_VERSION) { "Unsupported personal model schema" }
		safeId(metadata.profileId, "profileId")
		hash(metadata.modelSha256); hash(metadata.baseModelSha256); hash(metadata.featureSchemaSha256)
		hash(metadata.splitManifestSha256); hash(metadata.checkpointSha256); hash(metadata.metricsSha256)
		hashes(metadata.selectedSessionHashes); validate(metadata.artifact); validate(metadata.sidecar)
		validate(metadata.compatibility); validate(metadata.provenance); instant(metadata.createdUtc)
		require(metadata.artifact.sha256 == metadata.modelSha256) { "Model artifact hash does not match model metadata" }
	}

	private fun validate(metadata: PersonalJobMetadata) {
		require(metadata.format == "nekovr-personal-job-v1" && metadata.schemaVersion == PERSONAL_METADATA_SCHEMA_VERSION) { "Unsupported personal job schema" }
		safeId(metadata.profileId, "profileId"); safeId(metadata.jobId, "jobId")
		hash(metadata.baseModelSha256); hash(metadata.featureSchemaSha256); hash(metadata.settingsSha256)
		hashes(metadata.selectedSessionHashes); metadata.activeCheckpointId?.let { safeId(it, "activeCheckpointId") }
		metadata.cacheIds.forEach { safeId(it, "cacheId") }
		instant(metadata.createdUtc); instant(metadata.updatedUtc); validate(metadata.provenance)
	}

	private fun validate(metadata: PersonalCheckpointMetadata) {
		require(metadata.format == "nekovr-personal-checkpoint-v1" && metadata.schemaVersion == PERSONAL_METADATA_SCHEMA_VERSION) { "Unsupported checkpoint schema" }
		safeId(metadata.profileId, "profileId"); safeId(metadata.jobId, "jobId"); safeId(metadata.checkpointId, "checkpointId")
		hash(metadata.baseModelSha256); hash(metadata.optimizerStateSha256)
		require(metadata.epoch >= 0 && metadata.globalStep >= 0) { "Checkpoint progress is invalid" }
		validate(metadata.artifact); instant(metadata.createdUtc); validate(metadata.provenance)
	}

	private fun validate(metadata: PersonalCacheMetadata) {
		require(metadata.format == "nekovr-personal-cache-v1" && metadata.schemaVersion == PERSONAL_METADATA_SCHEMA_VERSION) { "Unsupported cache schema" }
		safeId(metadata.profileId, "profileId"); safeId(metadata.cacheId, "cacheId")
		hash(metadata.featureSchemaSha256); hash(metadata.preprocessingConfigSha256); hashes(metadata.sourceSessionHashes)
		validate(metadata.artifact); instant(metadata.createdUtc); instant(metadata.lastVerifiedUtc); validate(metadata.provenance)
	}

	private fun validate(compatibility: PersonalCompatibility) {
		require(compatibility.bodyRoleIds.all { it in 1..UShort.MAX_VALUE.toInt() }) { "Compatible role is invalid" }
		require(compatibility.sensorFamilies.size <= 64 && compatibility.sensorFamilies.all(SENSOR_FAMILY::matches)) { "Sensor family is invalid" }
		val signatures = compatibility.layouts.map { layout ->
			require(layout.trackerCount in 1..64 && layout.bodyRoleIds.size == layout.trackerCount) { "Layout tracker count is invalid" }
			require(layout.bodyRoleIds.distinct().size == layout.bodyRoleIds.size && layout.bodyRoleIds == layout.bodyRoleIds.sorted()) { "Layout roles must be unique and canonical" }
			require(layout.bodyRoleIds.all { it in compatibility.bodyRoleIds }) { "Layout role is outside profile compatibility" }
			layout.bodyRoleIds
		}
		require(signatures.distinct().size == signatures.size) { "Duplicate compatible layout" }
	}

	private fun validate(provenance: PersonalProvenance) {
		require(provenance.applicationCommit.isNotBlank() && provenance.applicationCommit.length <= 128) { "Application commit is invalid" }
		provenance.trainerVersion?.let { require(it.isNotBlank() && it.length <= 128) { "Trainer version is invalid" } }
		provenance.inferenceRuntimeVersion?.let { require(it.isNotBlank() && it.length <= 128) { "Runtime version is invalid" } }
		require(provenance.sourceHashes.size <= 128 && provenance.sourceHashes.keys.all(FIELD::matches)) { "Provenance source name is invalid" }
		provenance.sourceHashes.values.forEach(::hash)
	}

	private fun validate(reference: PersonalArtifactReference) {
		require(reference.sizeBytes >= 0) { "Artifact size is invalid" }
		hash(reference.sha256)
		val path = Path.of(reference.relativePath)
		require(!path.isAbsolute && path.nameCount > 0 && path.none { it.toString() == ".." }) { "Artifact path must be relative and contained" }
	}

	private fun requireProfile(profileId: String) {
		require(Files.isRegularFile(profileFile(profileId), LinkOption.NOFOLLOW_LINKS)) { "Personal profile does not exist" }
	}

	private fun profileFile(profileId: String) = profileDirectory(profileId).resolve("profile.json")

	private fun profileDirectory(profileId: String): Path = contained(profilesRoot.resolve(safeId(profileId, "profileId")))

	private fun scopedDirectory(profileId: String, kind: String): Path {
		val directory = contained(profileDirectory(profileId).resolve(kind))
		ensureDirectory(directory)
		return directory
	}

	private fun scopedFile(profileId: String, kind: String, relative: String): Path =
		contained(profileDirectory(profileId).resolve(kind).resolve(relative))

	private fun contained(path: Path): Path = path.toAbsolutePath().normalize().also {
		require(it.startsWith(root)) { "Path escapes personal training root" }
	}

	private fun ensureDirectory(path: Path) {
		val target = contained(path)
		var cursor: Path? = target
		while (cursor != null && cursor.startsWith(root)) {
			require(!Files.isSymbolicLink(cursor)) { "Symbolic links are not allowed in personal training storage" }
			if (cursor == root) break
			cursor = cursor.parent
		}
		Files.createDirectories(target)
		runCatching { Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwx------")) }
	}

	private fun <T> write(path: Path, serializer: KSerializer<T>, value: T) {
		val target = contained(path)
		ensureDirectory(checkNotNull(target.parent))
		require(!Files.isSymbolicLink(target)) { "Metadata target cannot be a symbolic link" }
		val temporary = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.tmp")
		try {
			Files.writeString(temporary, json.encodeToString(serializer, value) + "\n")
			runCatching { Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------")) }
			try {
				Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
			} catch (_: AtomicMoveNotSupportedException) {
				Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
			}
		} finally {
			Files.deleteIfExists(temporary)
		}
	}

	private fun <T> read(path: Path, serializer: KSerializer<T>): T {
		val target = contained(path)
		require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) { "Metadata file does not exist" }
		return json.decodeFromString(serializer, Files.readString(target))
	}

	private fun safeId(value: String, field: String): String = value.also { require(ID.matches(it)) { "$field is invalid" } }
	private fun hash(value: String) = require(HASH.matches(value)) { "SHA-256 is invalid" }
	private fun hashes(values: Set<String>) = values.forEach(::hash)
	private fun hashFile(value: String) = "${value.also(::hash)}.json"
	private fun instant(value: String) = Instant.parse(value)

	companion object {
		private val ID = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
		private val FIELD = Regex("^[a-z][a-z0-9_.-]{0,63}$")
		private val SENSOR_FAMILY = Regex("^[A-Z0-9][A-Z0-9_.-]{0,63}$")
		private val HASH = Regex("^[0-9a-f]{64}$")
	}
}
