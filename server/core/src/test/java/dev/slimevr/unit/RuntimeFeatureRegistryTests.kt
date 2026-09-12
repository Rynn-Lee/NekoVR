package dev.slimevr.unit

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.slimevr.ai.AIDriftEngine
import dev.slimevr.ai.ExecutionProviderType
import dev.slimevr.ai.LoadedModelSession
import dev.slimevr.ai.ModelArtifactValidator
import dev.slimevr.ai.ModelLoadErrorCode
import dev.slimevr.ai.OnnxRuntimeBackend
import dev.slimevr.ai.OnnxRuntimePackage
import dev.slimevr.ai.RuntimeFeatureMeasurement
import dev.slimevr.ai.RuntimeFeatureExtractor
import dev.slimevr.ai.RuntimeFeatureDescriptor
import dev.slimevr.ai.RuntimeFeatureRegistry
import dev.slimevr.ai.RuntimeFeatureRegistryCatalog
import dev.slimevr.ai.RuntimeFeatureSource
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import io.github.axisangles.ktmath.Quaternion
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeFeatureRegistryTests {
	@Test
	fun `versioned registry owns stable order units sources validity and missingness`() {
		val registry = RuntimeFeatureRegistry.V1
		assertEquals(1, registry.version)
		assertEquals(16, registry.features.size)
		assertEquals(registry.features.indices.toList(), registry.features.map { it.order })
		assertEquals("orientation_x", registry.features.first().id)
		assertEquals("unitless", registry.features.first().unit)
		assertEquals("tracker_orientation", registry.features.first().source.name.lowercase())
		assertTrue(registry.features.all { it.validity.name.lowercase() == "finite_when_source_valid" })
		assertTrue(registry.features.all { it.missingness.name.lowercase() == "zero_with_invalid_mask" })

		val extracted = registry.extract(
			mapOf(
				"orientation_x" to RuntimeFeatureMeasurement(0.25f, true),
				"orientation_y" to RuntimeFeatureMeasurement(Float.NaN, true),
				"temperature_c" to RuntimeFeatureMeasurement(31f, false),
			),
		)
		assertEquals(0.25f, extracted.values[0])
		assertTrue(extracted.validity[0])
		assertEquals(0f, extracted.values[1])
		assertFalse(extracted.validity[1])
		assertEquals(0f, extracted.values[7])
		assertFalse(extracted.validity[7])
		assertContentEquals(BooleanArray(16).also { it[0] = true }, extracted.validity)
		assertFailsWith<IllegalArgumentException> {
			registry.extract(mapOf("anonymous_feature" to RuntimeFeatureMeasurement(1f, true)))
		}
		val changedUnit = RuntimeFeatureRegistry(1, registry.features.map { if (it.id == "orientation_x") it.copy(unit = "rad") else it })
		val changedMeaning = RuntimeFeatureRegistry(1, registry.features.map { if (it.id == "orientation_x") it.copy(source = dev.slimevr.ai.RuntimeFeatureSource.TRACKER_POSITION) else it })
		assertTrue(changedUnit.sha256 != registry.sha256)
		assertTrue(changedMeaning.sha256 != registry.sha256)
		val unimplemented = RuntimeFeatureRegistry(
			2,
			listOf(RuntimeFeatureDescriptor("future_unavailable_source", 0, "unitless", RuntimeFeatureSource.TRACKER_STATUS)),
		)
		assertFailsWith<IllegalArgumentException> { RuntimeFeatureRegistryCatalog(listOf(unimplemented)) }
	}

	@Test
	fun `packaged probe schema matches the executable diagnostic registry`() {
		val (model, sidecar) = probeFiles()
		val metadata = ModelArtifactValidator.validate(model, sidecar)
		assertEquals(RuntimeFeatureRegistry.PROBE_V1.sha256, metadata.featureSchemaSha256)
	}

	@Test
	fun `authoritative extractor preserves available values and marks missing sources invalid`() {
		val tracker = Tracker(
			device = null, id = 7, name = "feature-source", trackerPosition = TrackerPosition.WAIST,
			hasRotation = true, hasPosition = false, hasAcceleration = false, allowReset = false, trackRotDirection = false,
		).apply {
			status = TrackerStatus.OK
			setRotation(Quaternion.IDENTITY)
			temperature = null
			dataTick()
		}
		val expectedOrientation = tracker.resetsHandler.getCalibratedPreAiRotation()
		val extracted = RuntimeFeatureExtractor(RuntimeFeatureRegistry.V1).extractTracker(tracker, 0.013f, tracker.lastDataMonotonicNs, false)
		assertEquals(16, extracted.features.size)
		assertContentEquals(
			floatArrayOf(expectedOrientation.x, expectedOrientation.y, expectedOrientation.z, expectedOrientation.w),
			extracted.features.copyOfRange(0, 4),
		)
		assertTrue(extracted.channelValidity.copyOfRange(0, 4).all { it })
		assertTrue(extracted.channelValidity.copyOfRange(4, 8).none { it })
		assertEquals(0.013f, extracted.features[8])
		assertTrue(extracted.channelValidity[8])
		assertEquals(0f, extracted.features[14])
		assertEquals(1f, extracted.features[15])
		assertTrue(extracted.channelValidity[14] && extracted.channelValidity[15])

		tracker.status = TrackerStatus.DISCONNECTED
		val unavailable = RuntimeFeatureExtractor(RuntimeFeatureRegistry.V1).extractTracker(tracker, 0.02f, tracker.lastDataMonotonicNs, false)
		assertTrue(unavailable.channelValidity.copyOfRange(0, 4).none { it })
		assertContentEquals(floatArrayOf(0f, 0f, 0f, 0f), unavailable.features.copyOfRange(0, 4))
	}

	@Test
	fun `same tensor width with unknown feature meaning is rejected before session creation`() {
		val (model, sidecar) = probeFiles()
		val changed = Files.createTempFile("nekovr-feature-schema-mismatch-", ".json")
		try {
			val root = ObjectMapper().readTree(sidecar.toFile()) as ObjectNode
			root.put("feature_schema_sha256", "a".repeat(64))
			ObjectMapper().writeValue(changed.toFile(), root)
			val backend = NoSessionBackend()
			AIDriftEngine(runtimeFactory = { backend }).use { engine ->
				val result = engine.loadModel(model, changed, ExecutionProviderType.CPU)
				assertFalse(result.activated)
				assertEquals(ModelLoadErrorCode.FEATURE_SCHEMA_MISMATCH, result.failure?.code)
				assertEquals(0, backend.sessionCreations)
			}
		} finally {
			Files.deleteIfExists(changed)
		}
	}

	private class NoSessionBackend : OnnxRuntimeBackend {
		override val runtimePackage = OnnxRuntimePackage("cpu", "fixture")
		override val availableProviders = setOf(ExecutionProviderType.CPU)
		var sessionCreations = 0
		override fun createSession(modelPath: Path, provider: ExecutionProviderType): LoadedModelSession {
			sessionCreations++
			error("Feature mismatch must be rejected before session creation")
		}
		override fun close() = Unit
	}

	private fun probeFiles(): Pair<Path, Path> {
		fun resource(name: String): Path = Path.of(requireNotNull(javaClass.getResource("/dev/slimevr/ai/probe/$name")).toURI())
		return resource("probe.onnx") to resource("probe.onnx.json")
	}
}
