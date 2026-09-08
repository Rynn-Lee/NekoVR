package dev.slimevr.ai

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

enum class AIPreset(val id: String, val intensity: Float, val smoothing: Float) {
	MAX_STABILITY("max_stability", 1.0f, 0.2f),
	DANCE_MOCAP("dance_mocap", 0.75f, 0.05f),
	CHILL_SEATED("chill_seated", 0.85f, 0.4f),
	CUSTOM("custom", 0.8f, 0.15f),
	;

	companion object {
		fun fromId(id: String): AIPreset = entries.firstOrNull { it.id.equals(id, true) } ?: MAX_STABILITY
	}
}

enum class LegacyDriftCompensationMode {
	REPLACE,
	COMPOSE,
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AIModelInfo(
	val id: String,
	val name: String,
	val size: String = "M",
	val description: String = "",
	val url: String = "",
	val localPath: String? = null,
	val supportedChipsets: List<String> = listOf("BNO085", "BMI160", "LSM6DSR", "ICM42688"),
	val inferenceTimeMs: Float = 0.4f,
	val isDownloaded: Boolean = true,
)

class AIModelConfig {
	var enabled: Boolean = false
	/** Runs the complete inference and safety path while always returning identity to tracking. */
	var shadowMode: Boolean = false
	var activePreset: AIPreset = AIPreset.MAX_STABILITY
	var intensity: Float = 1.0f
	var smoothing: Float = 0.2f
	var selectedModelId: String = "nekovr-tcn-m"
	var autoDownloadUpdates: Boolean = true
	var activeProvider: ExecutionProviderType = ExecutionProviderType.AUTO
	var requestedProvider: ExecutionProviderType = ExecutionProviderType.AUTO
	var contextFrames: Int = 60
	var confidenceThreshold: Float = 0.6f
	var maximumResultAgeMillis: Long = 100L
	var maximumCorrectionRadians: Float = 0.35f
	var maximumAngularRateRadiansPerSecond: Float = 1.5f
	var maximumAngularAccelerationRadiansPerSecondSquared: Float = 12f
	var staleDecaySeconds: Float = 0.25f
	var watchdogFailureThreshold: Int = 3
	var legacyDriftCompensationMode: LegacyDriftCompensationMode = LegacyDriftCompensationMode.REPLACE
}
