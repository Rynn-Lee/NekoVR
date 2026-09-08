package dev.slimevr.config

import dev.slimevr.ai.AIModelConfig
import dev.slimevr.ai.ExecutionProviderType
import dev.slimevr.ai.LegacyDriftCompensationMode
import dev.slimevr.ai.TrackerSlotMapping

data class AITrackerMappingConfig(
	var trackerId: Int = 0,
	var bodyRoleId: Int = 0,
	var slot: Int = 0,
)

data class ValidatedAIDriftConfig(
	val config: AIModelConfig,
	val mappings: List<TrackerSlotMapping>,
	val valid: Boolean,
	val error: String? = null,
)

/** Versioned persisted AI settings. Runtime activation state is deliberately not persisted here. */
class AIDriftConfig {
	var schemaVersion: Int = CURRENT_SCHEMA_VERSION
	var enabled: Boolean = false
	var shadowMode: Boolean = false
	var requestedProvider: String = ExecutionProviderType.AUTO.name
	var contextFrames: Int = 60
	var confidenceThreshold: Float = 0.6f
	var maximumResultAgeMillis: Long = 100L
	var maximumCorrectionRadians: Float = 0.35f
	var maximumRateRadiansPerSecond: Float = 1.5f
	var maximumAccelerationRadiansPerSecondSquared: Float = 12f
	var smoothing: Float = 0.2f
	var staleDecaySeconds: Float = 0.25f
	var watchdogFailureThreshold: Int = 3
	var legacyMode: String = LegacyDriftCompensationMode.REPLACE.name
	var mappings: MutableList<AITrackerMappingConfig> = mutableListOf()

	fun validated(): ValidatedAIDriftConfig = try {
		require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported AI configuration schema version $schemaVersion" }
		val provider = ExecutionProviderType.entries.firstOrNull { it.name == requestedProvider }
			?: throw IllegalArgumentException("Unknown AI execution provider")
		val legacy = LegacyDriftCompensationMode.entries.firstOrNull { it.name == legacyMode }
			?: throw IllegalArgumentException("Unknown legacy drift compensation mode")
		require(contextFrames in 1..MAXIMUM_CONTEXT_FRAMES) { "AI context frame count is outside supported bounds" }
		require(confidenceThreshold.isFinite() && confidenceThreshold in 0f..1f) { "AI confidence threshold is invalid" }
		require(maximumResultAgeMillis in 1..MAXIMUM_RESULT_AGE_MILLIS) { "AI maximum result age is invalid" }
		require(maximumCorrectionRadians.isFinite() && maximumCorrectionRadians in Float.MIN_VALUE..MAXIMUM_CORRECTION_RADIANS) { "AI correction magnitude limit is invalid" }
		require(maximumRateRadiansPerSecond.isFinite() && maximumRateRadiansPerSecond in Float.MIN_VALUE..MAXIMUM_RATE_RADIANS_PER_SECOND) { "AI correction rate limit is invalid" }
		require(maximumAccelerationRadiansPerSecondSquared.isFinite() && maximumAccelerationRadiansPerSecondSquared in Float.MIN_VALUE..MAXIMUM_ACCELERATION_RADIANS_PER_SECOND_SQUARED) { "AI correction acceleration limit is invalid" }
		require(smoothing.isFinite() && smoothing in 0f..1f) { "AI smoothing is invalid" }
		require(staleDecaySeconds.isFinite() && staleDecaySeconds in Float.MIN_VALUE..MAXIMUM_STALE_DECAY_SECONDS) { "AI stale decay is invalid" }
		require(watchdogFailureThreshold in 1..MAXIMUM_WATCHDOG_FAILURES) { "AI watchdog threshold is invalid" }
		val runtimeMappings = mappings.map {
			require(it.trackerId >= 0 && it.bodyRoleId in 0..UShort.MAX_VALUE.toInt() && it.slot in 0..UShort.MAX_VALUE.toInt()) { "AI tracker mapping is outside supported bounds" }
			TrackerSlotMapping(it.trackerId, it.bodyRoleId, it.slot)
		}
		require(runtimeMappings.map { it.trackerId }.toSet().size == runtimeMappings.size) { "AI tracker IDs must be unique" }
		require(runtimeMappings.map { it.slot }.toSet().size == runtimeMappings.size) { "AI tracker slots must be unique" }
		ValidatedAIDriftConfig(
			config = AIModelConfig().also {
				it.enabled = enabled
				it.shadowMode = shadowMode
				it.requestedProvider = provider
				it.contextFrames = contextFrames
				it.confidenceThreshold = confidenceThreshold
				it.maximumResultAgeMillis = maximumResultAgeMillis
				it.maximumCorrectionRadians = maximumCorrectionRadians
				it.maximumAngularRateRadiansPerSecond = maximumRateRadiansPerSecond
				it.maximumAngularAccelerationRadiansPerSecondSquared = maximumAccelerationRadiansPerSecondSquared
				it.smoothing = smoothing
				it.staleDecaySeconds = staleDecaySeconds
				it.watchdogFailureThreshold = watchdogFailureThreshold
				it.legacyDriftCompensationMode = legacy
			},
			mappings = runtimeMappings,
			valid = true,
		)
	} catch (error: Exception) {
		// Invalid, unversioned, or prototype state must never opt AI into correction.
		ValidatedAIDriftConfig(AIModelConfig().apply { enabled = false }, emptyList(), false, error.message)
	}

	@Synchronized
	fun capture(config: AIModelConfig, runtimeMappings: List<TrackerSlotMapping>) {
		val candidate = AIDriftConfig().also {
			it.enabled = config.enabled
			it.shadowMode = config.shadowMode
			it.requestedProvider = config.requestedProvider.name
			it.contextFrames = config.contextFrames
			it.confidenceThreshold = config.confidenceThreshold
			it.maximumResultAgeMillis = config.maximumResultAgeMillis
			it.maximumCorrectionRadians = config.maximumCorrectionRadians
			it.maximumRateRadiansPerSecond = config.maximumAngularRateRadiansPerSecond
			it.maximumAccelerationRadiansPerSecondSquared = config.maximumAngularAccelerationRadiansPerSecondSquared
			it.smoothing = config.smoothing
			it.staleDecaySeconds = config.staleDecaySeconds
			it.watchdogFailureThreshold = config.watchdogFailureThreshold
			it.legacyMode = config.legacyDriftCompensationMode.name
			it.mappings = runtimeMappings.map { mapping -> AITrackerMappingConfig(mapping.trackerId, mapping.bodyRoleId, mapping.slot) }.toMutableList()
		}
		require(candidate.validated().valid) { "Refusing to persist invalid AI configuration" }
		schemaVersion = candidate.schemaVersion
		enabled = candidate.enabled
		shadowMode = candidate.shadowMode
		requestedProvider = candidate.requestedProvider
		contextFrames = candidate.contextFrames
		confidenceThreshold = candidate.confidenceThreshold
		maximumResultAgeMillis = candidate.maximumResultAgeMillis
		maximumCorrectionRadians = candidate.maximumCorrectionRadians
		maximumRateRadiansPerSecond = candidate.maximumRateRadiansPerSecond
		maximumAccelerationRadiansPerSecondSquared = candidate.maximumAccelerationRadiansPerSecondSquared
		smoothing = candidate.smoothing
		staleDecaySeconds = candidate.staleDecaySeconds
		watchdogFailureThreshold = candidate.watchdogFailureThreshold
		legacyMode = candidate.legacyMode
		mappings = candidate.mappings
	}

	companion object {
		const val CURRENT_SCHEMA_VERSION = 1
		const val MAXIMUM_CONTEXT_FRAMES = 4096
		const val MAXIMUM_CORRECTION_RADIANS = 3.1415927f
		const val MAXIMUM_RATE_RADIANS_PER_SECOND = 100f
		const val MAXIMUM_ACCELERATION_RADIANS_PER_SECOND_SQUARED = 1_000f
		const val MAXIMUM_STALE_DECAY_SECONDS = 60f
		private const val MAXIMUM_RESULT_AGE_MILLIS = 60_000L
		private const val MAXIMUM_WATCHDOG_FAILURES = 1_000
	}
}
