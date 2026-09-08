package dev.slimevr.ai.personal

import kotlinx.serialization.Serializable

const val PERSONAL_METADATA_SCHEMA_VERSION: Int = 1

@Serializable
data class PersonalProvenance(
	val applicationCommit: String,
	val trainerVersion: String? = null,
	val inferenceRuntimeVersion: String? = null,
	val sourceHashes: Map<String, String> = emptyMap(),
)

@Serializable
data class PersonalTrackerLayout(
	val trackerCount: Int,
	val bodyRoleIds: List<Int>,
)

@Serializable
data class PersonalCompatibility(
	val bodyRoleIds: Set<Int>,
	val layouts: List<PersonalTrackerLayout>,
	val sensorFamilies: Set<String>,
)

@Serializable
data class PersonalArtifactReference(
	val relativePath: String,
	val sha256: String,
	val sizeBytes: Long,
)

@Serializable
data class PersonalProfileMetadata(
	val format: String = "nekovr-personal-profile-v1",
	val schemaVersion: Int = PERSONAL_METADATA_SCHEMA_VERSION,
	val profileId: String,
	val localPseudonym: String,
	val createdUtc: String,
	val updatedUtc: String,
	val bodyProportionsMeters: Map<String, Float>,
	val compatibility: PersonalCompatibility,
	val provenance: PersonalProvenance,
)

@Serializable
data class PersonalModelMetadata(
	val format: String = "nekovr-personal-model-v1",
	val schemaVersion: Int = PERSONAL_METADATA_SCHEMA_VERSION,
	val modelSha256: String,
	val profileId: String,
	val baseModelSha256: String,
	val featureSchemaSha256: String,
	val artifact: PersonalArtifactReference,
	val sidecar: PersonalArtifactReference,
	val compatibility: PersonalCompatibility,
	val selectedSessionHashes: Set<String>,
	val splitManifestSha256: String,
	val checkpointSha256: String,
	val metricsSha256: String,
	val createdUtc: String,
	val provenance: PersonalProvenance,
)

@Serializable
enum class PersonalJobState { CREATED, VALIDATING, RUNNING, PAUSED, CANCELLING, CANCELLED, FAILED, COMPLETED }

@Serializable
data class PersonalJobMetadata(
	val format: String = "nekovr-personal-job-v1",
	val schemaVersion: Int = PERSONAL_METADATA_SCHEMA_VERSION,
	val jobId: String,
	val profileId: String,
	val baseModelSha256: String,
	val featureSchemaSha256: String,
	val selectedSessionHashes: Set<String>,
	val settingsSha256: String,
	val preset: PersonalTrainingPreset = PersonalTrainingPreset.BALANCED,
	val provider: String = "AUTO",
	val resourceBudget: PersonalResourceBudget? = null,
	val coverage: PersonalCoverageSummary? = null,
	val state: PersonalJobState,
	val createdUtc: String,
	val updatedUtc: String,
	val activeCheckpointId: String? = null,
	val cacheIds: Set<String> = emptySet(),
	val provenance: PersonalProvenance,
)

@Serializable
data class PersonalCheckpointMetadata(
	val format: String = "nekovr-personal-checkpoint-v1",
	val schemaVersion: Int = PERSONAL_METADATA_SCHEMA_VERSION,
	val checkpointId: String,
	val jobId: String,
	val profileId: String,
	val baseModelSha256: String,
	val epoch: Int,
	val globalStep: Long,
	val artifact: PersonalArtifactReference,
	val optimizerStateSha256: String,
	val createdUtc: String,
	val resumable: Boolean,
	val provenance: PersonalProvenance,
)

@Serializable
data class PersonalCacheMetadata(
	val format: String = "nekovr-personal-cache-v1",
	val schemaVersion: Int = PERSONAL_METADATA_SCHEMA_VERSION,
	val cacheId: String,
	val profileId: String,
	val featureSchemaSha256: String,
	val sourceSessionHashes: Set<String>,
	val preprocessingConfigSha256: String,
	val artifact: PersonalArtifactReference,
	val createdUtc: String,
	val lastVerifiedUtc: String,
	val provenance: PersonalProvenance,
)
