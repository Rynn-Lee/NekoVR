package dev.slimevr.ai

import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerStatus
import java.security.MessageDigest

enum class RuntimeFeatureSource {
	TRACKER_ORIENTATION,
	TRACKER_POSITION,
	TRACKER_TEMPERATURE,
	SAMPLE_TIMING,
	TRACKER_STATUS,
	CHANNEL_VALIDITY,
	CHANNEL_PROVENANCE,
	RESET_STATE,
	DATA_DOMAIN,
	DIAGNOSTIC_FIXTURE,
}

enum class RuntimeFeatureValidity {
	FINITE_WHEN_SOURCE_VALID,
}

enum class RuntimeFeatureMissingness {
	ZERO_WITH_INVALID_MASK,
}

data class RuntimeFeatureDescriptor(
	val id: String,
	val order: Int,
	val unit: String,
	val source: RuntimeFeatureSource,
	val validity: RuntimeFeatureValidity = RuntimeFeatureValidity.FINITE_WHEN_SOURCE_VALID,
	val missingness: RuntimeFeatureMissingness = RuntimeFeatureMissingness.ZERO_WITH_INVALID_MASK,
)

data class RuntimeFeatureMeasurement(val value: Float, val valid: Boolean)

data class ExtractedRuntimeFeatures(val values: FloatArray, val validity: BooleanArray)

/**
 * Executable, versioned identity of the scalar feature axis accepted by the server.
 * The SHA-256 covers order and every semantic descriptor, not only tensor width.
 */
class RuntimeFeatureRegistry(
	val version: Int,
	features: List<RuntimeFeatureDescriptor>,
) {
	val features: List<RuntimeFeatureDescriptor> = features.sortedBy(RuntimeFeatureDescriptor::order)
	val canonicalSchema: String
	val sha256: String

	init {
		require(version > 0) { "Feature registry version must be positive" }
		require(this.features.isNotEmpty()) { "Feature registry must not be empty" }
		require(this.features.map { it.id }.toSet().size == this.features.size) { "Feature IDs must be unique" }
		require(this.features.map { it.order } == this.features.indices.toList()) { "Feature order must be contiguous and zero-based" }
		require(this.features.all { it.id.matches(Regex("[a-z][a-z0-9_]*")) && it.unit.isNotBlank() }) {
			"Feature IDs and units must be explicit"
		}
		canonicalSchema = buildString {
			append("{\"features\":[")
			this@RuntimeFeatureRegistry.features.forEachIndexed { index, feature ->
				if (index > 0) append(',')
				append("{\"id\":\"").append(feature.id)
				append("\",\"missingness\":\"").append(feature.missingness.name.lowercase())
				append("\",\"order\":").append(feature.order)
				append(",\"source\":\"").append(feature.source.name.lowercase())
				append("\",\"unit\":\"").append(feature.unit)
				append("\",\"validity\":\"").append(feature.validity.name.lowercase()).append("\"}")
			}
			append("],\"version\":").append(version).append('}')
		}
		sha256 = MessageDigest.getInstance("SHA-256").digest(canonicalSchema.toByteArray(Charsets.UTF_8))
			.joinToString("") { "%02x".format(it) }
	}

	fun extract(measurements: Map<String, RuntimeFeatureMeasurement>): ExtractedRuntimeFeatures {
		val unknown = measurements.keys - features.mapTo(mutableSetOf(), RuntimeFeatureDescriptor::id)
		require(unknown.isEmpty()) { "Unknown runtime feature IDs: ${unknown.sorted().joinToString()}" }
		val values = FloatArray(features.size)
		val validity = BooleanArray(features.size)
		features.forEach { feature ->
			val measurement = measurements[feature.id]
			if (measurement != null && measurement.valid && measurement.value.isFinite()) {
				values[feature.order] = measurement.value
				validity[feature.order] = true
			}
		}
		return ExtractedRuntimeFeatures(values, validity)
	}

	fun validate(metadata: ModelArtifactMetadata) {
		if (metadata.featureSchemaSha256 != sha256 || metadata.featureCount != features.size) {
			throw ModelLoadException(
				ModelLoadErrorCode.FEATURE_SCHEMA_MISMATCH,
				"Model feature schema ${metadata.featureSchemaSha256} does not match executable registry v$version ($sha256)",
			)
		}
	}

	companion object {
		private fun descriptor(id: String, order: Int, unit: String, source: RuntimeFeatureSource) =
			RuntimeFeatureDescriptor(id, order, unit, source)

		@JvmField
		val V1 = RuntimeFeatureRegistry(
			1,
			listOf(
				descriptor("orientation_x", 0, "unitless", RuntimeFeatureSource.TRACKER_ORIENTATION),
				descriptor("orientation_y", 1, "unitless", RuntimeFeatureSource.TRACKER_ORIENTATION),
				descriptor("orientation_z", 2, "unitless", RuntimeFeatureSource.TRACKER_ORIENTATION),
				descriptor("orientation_w", 3, "unitless", RuntimeFeatureSource.TRACKER_ORIENTATION),
				descriptor("position_x_m", 4, "m", RuntimeFeatureSource.TRACKER_POSITION),
				descriptor("position_y_m", 5, "m", RuntimeFeatureSource.TRACKER_POSITION),
				descriptor("position_z_m", 6, "m", RuntimeFeatureSource.TRACKER_POSITION),
				descriptor("temperature_c", 7, "degC", RuntimeFeatureSource.TRACKER_TEMPERATURE),
				descriptor("delta_s", 8, "s", RuntimeFeatureSource.SAMPLE_TIMING),
				descriptor("stale", 9, "boolean", RuntimeFeatureSource.TRACKER_STATUS),
				descriptor("orientation_validity", 10, "boolean", RuntimeFeatureSource.CHANNEL_VALIDITY),
				descriptor("angular_velocity_provenance", 11, "enum", RuntimeFeatureSource.CHANNEL_PROVENANCE),
				descriptor("drift_provenance", 12, "enum", RuntimeFeatureSource.CHANNEL_PROVENANCE),
				descriptor("reset_context", 13, "boolean", RuntimeFeatureSource.RESET_STATE),
				descriptor("synthetic_domain", 14, "boolean", RuntimeFeatureSource.DATA_DOMAIN),
				descriptor("real_domain", 15, "boolean", RuntimeFeatureSource.DATA_DOMAIN),
			),
		)

		@JvmField
		val PROBE_V1 = RuntimeFeatureRegistry(
			1,
			listOf(
				descriptor("probe_orientation", 0, "unitless", RuntimeFeatureSource.DIAGNOSTIC_FIXTURE),
				descriptor("probe_acceleration", 1, "m/s2", RuntimeFeatureSource.DIAGNOSTIC_FIXTURE),
			),
		)
	}
}

class RuntimeFeatureExtractor(private val registry: RuntimeFeatureRegistry) {
	init {
		requireExecutable(registry)
	}
	fun extractTracker(
		tracker: Tracker,
		deltaTimeSeconds: Float,
		nowMonotonicNanos: Long,
		resetContext: Boolean,
	): InferenceTrackerSample {
		require(deltaTimeSeconds.isFinite() && deltaTimeSeconds >= 0f) { "Sampling delta must be finite and non-negative" }
		val orientation = tracker.resetsHandler.getCalibratedPreAiRotation()
		val orientationValid = tracker.status.sendData && orientation.w.isFinite() && orientation.x.isFinite() &&
			orientation.y.isFinite() && orientation.z.isFinite() && orientation.lenSq() in 0.98f..1.02f
		val position = tracker.position
		val positionValid = tracker.hasPosition && tracker.status.sendData &&
			position.x.isFinite() && position.y.isFinite() && position.z.isFinite()
		val acceleration = tracker.getAcceleration()
		val accelerationValid = tracker.hasAcceleration && tracker.status.sendData &&
			acceleration.x.isFinite() && acceleration.y.isFinite() && acceleration.z.isFinite()
		val temperature = tracker.temperature
		val temperatureValid = tracker.status.sendData && temperature?.isFinite() == true
		val angularVelocityValid = tracker.status.sendData && tracker.rawAngularVelocity?.let {
			it.x.isFinite() && it.y.isFinite() && it.z.isFinite()
		} == true
		val stale = tracker.status == TrackerStatus.TIMED_OUT ||
			(nowMonotonicNanos - tracker.lastDataMonotonicNs).coerceAtLeast(0L) > 100_000_000L
		fun value(value: Float, valid: Boolean = true) = RuntimeFeatureMeasurement(value, valid)
		val measurements = mapOf(
			"orientation_x" to value(orientation.x, orientationValid),
			"orientation_y" to value(orientation.y, orientationValid),
			"orientation_z" to value(orientation.z, orientationValid),
			"orientation_w" to value(orientation.w, orientationValid),
			"position_x_m" to value(position.x, positionValid),
			"position_y_m" to value(position.y, positionValid),
			"position_z_m" to value(position.z, positionValid),
			"temperature_c" to value(temperature ?: 0f, temperatureValid),
			"delta_s" to value(deltaTimeSeconds),
			"stale" to value(if (stale) 1f else 0f),
			"orientation_validity" to value(if (orientationValid) 1f else 0f),
			"angular_velocity_provenance" to value(if (angularVelocityValid) 2f else 0f, angularVelocityValid),
			"drift_provenance" to value(if (orientationValid) 4f else 0f, orientationValid),
			"reset_context" to value(if (resetContext) 1f else 0f),
			"synthetic_domain" to value(0f),
			"real_domain" to value(1f),
			"probe_orientation" to value(orientation.x, orientationValid),
			"probe_acceleration" to value(acceleration.x, accelerationValid),
		)
		val selected = measurements.filterKeys { id -> registry.features.any { it.id == id } }
		check(selected.size == registry.features.size) { "Executable extractor does not implement registry v${registry.version}" }
		val extracted = registry.extract(selected)
		return InferenceTrackerSample(
			tracker.id,
			tracker.resetsHandler.resetEpoch,
			extracted.values,
			extracted.validity,
		)
	}

	companion object {
		private val executableIds = setOf(
			"orientation_x", "orientation_y", "orientation_z", "orientation_w",
			"position_x_m", "position_y_m", "position_z_m", "temperature_c", "delta_s", "stale",
			"orientation_validity", "angular_velocity_provenance", "drift_provenance", "reset_context",
			"synthetic_domain", "real_domain", "probe_orientation", "probe_acceleration",
		)

		fun requireExecutable(registry: RuntimeFeatureRegistry) {
			val missing = registry.features.map(RuntimeFeatureDescriptor::id).filterNot(executableIds::contains)
			require(missing.isEmpty()) { "Registry contains features without authoritative extractors: ${missing.sorted().joinToString()}" }
		}
	}
}

class RuntimeFeatureRegistryCatalog(registries: Collection<RuntimeFeatureRegistry>) {
	init {
		registries.forEach(RuntimeFeatureExtractor::requireExecutable)
	}
	private val byHash = registries.associateBy(RuntimeFeatureRegistry::sha256).also {
		require(it.size == registries.size) { "Feature registry hashes must be unique" }
	}

	fun requireFor(metadata: ModelArtifactMetadata): RuntimeFeatureRegistry {
		val registry = byHash[metadata.featureSchemaSha256] ?: throw ModelLoadException(
			ModelLoadErrorCode.FEATURE_SCHEMA_MISMATCH,
			"Model feature schema ${metadata.featureSchemaSha256} is not owned by this server",
		)
		registry.validate(metadata)
		return registry
	}

	companion object {
		@JvmField
		val DEFAULT = RuntimeFeatureRegistryCatalog(listOf(RuntimeFeatureRegistry.V1, RuntimeFeatureRegistry.PROBE_V1))
	}
}
