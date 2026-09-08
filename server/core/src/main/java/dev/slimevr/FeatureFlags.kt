package dev.slimevr

data class FeatureFlags(
	var steam: Boolean = false,
	var skipCheckUdev: Boolean = true,
	/** Explicit startup opt-in; inference-ready evidence is checked independently. */
	@Volatile var aiActiveCorrection: Boolean = false,
)
