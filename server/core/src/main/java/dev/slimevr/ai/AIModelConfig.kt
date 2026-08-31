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
	var activePreset: AIPreset = AIPreset.MAX_STABILITY
	var intensity: Float = 1.0f
	var smoothing: Float = 0.2f
	var selectedModelId: String = "nekovr-tcn-m"
	var autoDownloadUpdates: Boolean = true
	var activeProvider: ExecutionProviderType = ExecutionProviderType.CUDA
}
