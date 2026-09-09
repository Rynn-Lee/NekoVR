package dev.slimevr.unit

import dev.slimevr.ai.ExecutionProviderType
import dev.slimevr.ai.ModelArtifactKind
import dev.slimevr.ai.TrackerSlotMapping
import dev.slimevr.ai.personal.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersonalModelActivationControllerTests {
	private val personalHash = "a".repeat(64)
	private val baseHash = "b".repeat(64)
	private val oldHash = "c".repeat(64)

	@Test
	fun `shadow activation resets history and failed shadow rolls back in one action`() {
		val backend = FakeBackend(oldHash).apply {
			add(oldHash, ModelArtifactKind.GLOBAL)
			add(baseHash, ModelArtifactKind.GLOBAL)
			add(personalHash, ModelArtifactKind.PERSONAL, "profile-1")
		}
		val controller = PersonalModelActivationController(backend)
		val shadow = controller.activate(metadata(), ExecutionProviderType.CPU, shadow = true)
		assertEquals(personalHash, backend.activeModelSha256)
		assertTrue(shadow.shadow)
		assertEquals(true to true, backend.mode)
		assertEquals(1, backend.historyResets)

		controller.finishShadow(passed = false)
		assertEquals(oldHash, backend.activeModelSha256)
		assertEquals(true to false, backend.mode)
		assertEquals(2, backend.historyResets)
	}

	@Test
	fun `activation failure restores previous model transactionally`() {
		val backend = FakeBackend(oldHash).apply {
			add(oldHash, ModelArtifactKind.GLOBAL)
			add(baseHash, ModelArtifactKind.GLOBAL)
			add(personalHash, ModelArtifactKind.PERSONAL, "profile-1")
			failRecord = true
		}
		val controller = PersonalModelActivationController(backend)
		assertFailsWith<IllegalStateException> { controller.activate(metadata(), ExecutionProviderType.CPU, shadow = false) }
		assertEquals(oldHash, backend.activeModelSha256)
		assertEquals(true to false, backend.mode)
	}

	@Test
	fun `incompatible topology falls back to base or disables correction`() {
		val backend = FakeBackend(oldHash).apply {
			add(oldHash, ModelArtifactKind.GLOBAL)
			add(baseHash, ModelArtifactKind.GLOBAL)
			add(personalHash, ModelArtifactKind.PERSONAL, "profile-1")
		}
		val controller = PersonalModelActivationController(backend)
		controller.activate(metadata(), ExecutionProviderType.CPU, shadow = false)
		backend.incompatible += personalHash
		controller.topologyChanged(listOf(TrackerSlotMapping(1, 1, 0)))
		assertEquals(baseHash, backend.activeModelSha256)

		val noBase = FakeBackend(oldHash).apply {
			add(oldHash, ModelArtifactKind.GLOBAL)
			add(baseHash, ModelArtifactKind.GLOBAL)
			add(personalHash, ModelArtifactKind.PERSONAL, "profile-1")
		}
		val second = PersonalModelActivationController(noBase)
		second.activate(metadata(), ExecutionProviderType.CPU, shadow = false)
		noBase.incompatible += personalHash
		noBase.incompatible += baseHash
		second.topologyChanged(listOf(TrackerSlotMapping(1, 1, 0)))
		assertEquals(null, noBase.activeModelSha256)
		assertFalse(noBase.mode.first)
	}

	private fun metadata() = PersonalModelMetadata(
		modelSha256 = personalHash, profileId = "profile-1", baseModelSha256 = baseHash,
		featureSchemaSha256 = "d".repeat(64), artifact = PersonalArtifactReference("personal.onnx", personalHash, 1),
		sidecar = PersonalArtifactReference("personal.onnx.json", "e".repeat(64), 1),
		compatibility = PersonalCompatibility(setOf(1), listOf(PersonalTrackerLayout(1, listOf(1))), setOf("BNO085")),
		selectedSessionHashes = setOf("f".repeat(64)), splitManifestSha256 = "1".repeat(64),
		checkpointSha256 = "2".repeat(64), metricsSha256 = "3".repeat(64), createdUtc = "2026-09-08T00:00:00Z",
		provenance = PersonalProvenance("test"),
	)

	private class FakeBackend(initial: String?) : PersonalActivationBackend {
		private val artifacts = mutableMapOf<String, PersonalActivationArtifact>()
		override var activeModelSha256: String? = initial
			private set
		override var activeProvider: ExecutionProviderType? = ExecutionProviderType.CPU
			private set
		var mode = true to false
		var historyResets = 0
		var failRecord = false
		val incompatible = mutableSetOf<String>()

		fun add(hash: String, kind: ModelArtifactKind, profile: String? = null) { artifacts[hash] = PersonalActivationArtifact(hash, kind, profile) }
		override fun currentMappings() = listOf(TrackerSlotMapping(1, 1, 0))
		override fun resolve(modelSha256: String) = artifacts[modelSha256]
		override fun compatibilityError(modelSha256: String, mappings: List<TrackerSlotMapping>) = if (modelSha256 in incompatible) "unsupported topology" else null
		override fun load(modelSha256: String, provider: ExecutionProviderType) {
			check(modelSha256 in artifacts) { "missing model" }
			activeModelSha256 = modelSha256
			activeProvider = provider
		}
		override fun unload() { activeModelSha256 = null; activeProvider = null }
		override fun setCorrectionMode(enabled: Boolean, shadow: Boolean) { mode = enabled to shadow }
		override fun resetInferenceHistory() { historyResets++ }
		override fun recordActivation(modelSha256: String, previousModelSha256: String?) { check(!failRecord) { "history write failed" } }
	}
}
