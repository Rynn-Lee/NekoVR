package dev.slimevr.unit

import dev.slimevr.ai.AIModelConfig
import dev.slimevr.ai.ExecutionProviderType
import dev.slimevr.ai.LegacyDriftCompensationMode
import dev.slimevr.ai.TrackerSlotMapping
import dev.slimevr.config.AIDriftConfig
import dev.slimevr.config.AITrackerMappingConfig
import dev.slimevr.config.ConfigManager
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AIDriftConfigTests {
	@Test
	fun `versioned AI settings round trip through server configuration`() {
		val path = temporaryConfigPath()
		try {
			val manager = ConfigManager(path.toString()).also { it.loadConfig() }
			val runtime = AIModelConfig().apply {
				enabled = true
				shadowMode = true
				requestedProvider = ExecutionProviderType.DIRECTML
				contextFrames = 96
				confidenceThreshold = 0.72f
				maximumResultAgeMillis = 250L
				maximumCorrectionRadians = 0.25f
				maximumAngularRateRadiansPerSecond = 1.25f
				maximumAngularAccelerationRadiansPerSecondSquared = 9f
				smoothing = 0.35f
				staleDecaySeconds = 0.4f
				watchdogFailureThreshold = 5
				legacyDriftCompensationMode = LegacyDriftCompensationMode.COMPOSE
			}
			val mappings = listOf(TrackerSlotMapping(10, 1, 0), TrackerSlotMapping(20, 2, 1))
			manager.vrConfig.aiDrift.capture(runtime, mappings)
			manager.saveConfig()

			val yaml = path.readText()
			assertTrue(yaml.contains("schemaVersion: 1"))
			assertTrue(yaml.contains("modelVersion: \"16\""))
			val loaded = ConfigManager(path.toString()).also { it.loadConfig() }.vrConfig.aiDrift.validated()
			assertTrue(loaded.valid, loaded.error)
			assertTrue(loaded.config.enabled)
			assertTrue(loaded.config.shadowMode)
			assertEquals(ExecutionProviderType.DIRECTML, loaded.config.requestedProvider)
			assertEquals(96, loaded.config.contextFrames)
			assertEquals(0.72f, loaded.config.confidenceThreshold)
			assertEquals(LegacyDriftCompensationMode.COMPOSE, loaded.config.legacyDriftCompensationMode)
			assertEquals(mappings, loaded.mappings)
		} finally {
			deleteConfigFiles(path)
		}
	}

	@Test
	fun `prototype version migration discards enabled and mapping state`() {
		val path = temporaryConfigPath()
		try {
			path.writeText(
				"""
				---
				aiDrift:
				  enabled: true
				  requestedProvider: "CUDA"
				  contextFrames: 512
				  mappings:
				    - trackerId: 10
				      bodyRoleId: 1
				      slot: 0
				modelVersion: "15"
				""".trimIndent(),
			)
			val persisted = ConfigManager(path.toString()).also { it.loadConfig() }.vrConfig.aiDrift
			val loaded = persisted.validated()
			assertTrue(loaded.valid, loaded.error)
			assertFalse(loaded.config.enabled)
			assertEquals(ExecutionProviderType.AUTO, loaded.config.requestedProvider)
			assertTrue(loaded.mappings.isEmpty())
			assertEquals(AIDriftConfig.CURRENT_SCHEMA_VERSION, persisted.schemaVersion)
		} finally {
			deleteConfigFiles(path)
		}
	}

	@Test
	fun `unsupported or invalid current AI configuration fails closed as one unit`() {
		val unsupported = AIDriftConfig().apply {
			schemaVersion = 999
			enabled = true
			requestedProvider = "CUDA"
			mappings += AITrackerMappingConfig(10, 1, 0)
		}.validated()
		assertFalse(unsupported.valid)
		assertFalse(unsupported.config.enabled)
		assertEquals(ExecutionProviderType.AUTO, unsupported.config.requestedProvider)
		assertTrue(unsupported.mappings.isEmpty())

		val duplicateMapping = AIDriftConfig().apply {
			enabled = true
			mappings += AITrackerMappingConfig(10, 1, 0)
			mappings += AITrackerMappingConfig(10, 2, 1)
		}.validated()
		assertFalse(duplicateMapping.valid)
		assertFalse(duplicateMapping.config.enabled)
		assertTrue(duplicateMapping.mappings.isEmpty())

		val unsafeLimit = AIDriftConfig().apply {
			enabled = true
			maximumCorrectionRadians = Float.MAX_VALUE
		}.validated()
		assertFalse(unsafeLimit.valid)
		assertFalse(unsafeLimit.config.enabled)
	}

	private fun temporaryConfigPath(): Path {
		val directory = java.nio.file.Files.createTempDirectory("nekovr-ai-config")
		return directory.resolve("vrconfig.yml")
	}

	private fun deleteConfigFiles(path: Path) {
		val directory = path.parent
		if (!java.nio.file.Files.exists(directory)) return
		java.nio.file.Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(java.nio.file.Files::deleteIfExists) }
	}
}
