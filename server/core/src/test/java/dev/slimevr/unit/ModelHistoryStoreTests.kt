package dev.slimevr.unit

import com.fasterxml.jackson.databind.ObjectMapper
import dev.slimevr.ai.ManagedModelArtifact
import dev.slimevr.ai.ManagedModelStore
import dev.slimevr.ai.ModelArtifactKind
import dev.slimevr.ai.RemoteModelManager
import dev.slimevr.ai.TrackerSlotMapping
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelHistoryStoreTests {
	@Test
	fun `history persists pinned global and personal entries with compatible rollback target`() {
		val root = Files.createTempDirectory("nekovr-model-history")
		val sources = Files.createTempDirectory("nekovr-model-history-sources")
		try {
			val manager = RemoteModelManager(root.toFile())
			val global = importVariant(manager, sources, "global", personal = false)
			val personal = importVariant(manager, sources, "personal", personal = true)
			manager.history.recordActivation(global, null)
			manager.history.pin(global, true)
			manager.history.recordActivation(personal, global.metadata.modelSha256)

			val statuses = manager.history.statuses(
				personal.metadata.modelSha256,
				listOf(TrackerSlotMapping(7, 1, 0)),
				2,
			)
			assertEquals(2, statuses.size)
			assertTrue(statuses.first { it.entry.modelSha256 == global.metadata.modelSha256 }.entry.pinned)
			assertTrue(statuses.first { it.entry.modelSha256 == global.metadata.modelSha256 }.rollbackTarget)
			assertTrue(statuses.all { it.compatible && it.validated })
			assertEquals(ModelArtifactKind.PERSONAL, statuses.first { it.active }.entry.kind)
			assertEquals("player-1", statuses.first { it.active }.entry.profileId)

			val restored = RemoteModelManager(root.toFile()).history
			assertNotNull(restored.rollbackArtifact())
			assertEquals(2, restored.statuses(personal.metadata.modelSha256, listOf(TrackerSlotMapping(7, 1, 0)), 2).size)
		} finally {
			deleteTree(root)
			deleteTree(sources)
		}
	}

	@Test
	fun `history compatibility rejects unsupported layout without losing entry`() {
		val root = Files.createTempDirectory("nekovr-model-history-incompatible")
		val sources = Files.createTempDirectory("nekovr-model-history-incompatible-sources")
		try {
			val manager = RemoteModelManager(root.toFile())
			val artifact = importVariant(manager, sources, "layout", personal = false)
			manager.history.recordActivation(artifact, null)
			val status = manager.history.statuses(null, listOf(TrackerSlotMapping(7, 53, 0)), 2).single()
			assertTrue(status.validated)
			assertFalse(status.compatible)
			assertTrue(status.compatibilityError!!.contains("body role"))
		} finally {
			deleteTree(root)
			deleteTree(sources)
		}
	}

	private fun importVariant(manager: RemoteModelManager, sources: Path, suffix: String, personal: Boolean): ManagedModelArtifact {
		val modelSource = Path.of(requireNotNull(javaClass.getResource("/dev/slimevr/ai/probe/probe.onnx")).toURI())
		val sidecarSource = Path.of(requireNotNull(javaClass.getResource("/dev/slimevr/ai/probe/probe.onnx.json")).toURI())
		val bytes = Files.readAllBytes(modelSource) + suffix.toByteArray()
		val hash = ManagedModelStore.sha256(bytes)
		val model = sources.resolve("$suffix.onnx")
		val sidecar = sources.resolve("$suffix.onnx.json")
		Files.write(model, bytes)
		val json = ObjectMapper().readTree(sidecarSource.toFile()) as com.fasterxml.jackson.databind.node.ObjectNode
		json.put("model_id", "model-$suffix")
		json.put("model_sha256", hash)
		json.put("model_size_bytes", bytes.size)
		if (personal) {
			json.put("model_kind", "personal")
			json.put("profile_id", "player-1")
		}
		ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(sidecar.toFile(), json)
		return manager.importLocal(model, sidecar, hash)
	}

	private fun deleteTree(root: Path) {
		if (!Files.exists(root)) return
		Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
	}
}
