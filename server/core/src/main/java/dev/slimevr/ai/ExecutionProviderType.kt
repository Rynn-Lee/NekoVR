package dev.slimevr.ai

enum class ExecutionProviderType(val displayName: String, val isGpu: Boolean, val warningNotice: String? = null) {
	AUTO("Automatic", false, null),
	TENSORRT("NVIDIA TensorRT", true, null),
	CUDA("NVIDIA CUDA", true, null),
	DIRECTML(
		"DirectML (DirectX 12)",
		true,
		"Режим совместимости DirectML активен. Для карт Nvidia рекомендуется драйвер CUDA.",
	),
	CPU("CPU (Резервный режим)", false, "Аппаратное ускорение GPU недоступно. Работает на процессоре."),
}
