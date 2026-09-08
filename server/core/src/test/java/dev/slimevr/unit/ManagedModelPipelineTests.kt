package dev.slimevr.unit

import dev.slimevr.ai.ManagedModelErrorCode
import dev.slimevr.ai.ManagedModelException
import dev.slimevr.ai.ManagedModelStore
import dev.slimevr.ai.ModelCatalogErrorCode
import dev.slimevr.ai.ModelCatalogException
import dev.slimevr.ai.RemoteContentFetcher
import dev.slimevr.ai.RemoteModelManager
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManagedModelPipelineTests {
	@Test
	fun `local import is copied atomically into hash-addressed managed storage`() {
		val root = Files.createTempDirectory("nekovr-managed-models")
		val sources = Files.createTempDirectory("nekovr-model-source")
		try {
			val (modelBytes, sidecarBytes) = probeBytes()
			val model = sources.resolve("incoming.onnx").apply { writeBytes(modelBytes) }
			val sidecar = sources.resolve("incoming.json").apply { writeBytes(sidecarBytes) }
			val hash = ManagedModelStore.sha256(modelBytes)
			val store = ManagedModelStore(root)

			val imported = store.importModel(model, sidecar, hash.uppercase())
			model.deleteIfExists()
			sidecar.deleteIfExists()

			assertEquals(hash, imported.metadata.modelSha256)
			assertEquals(hash, imported.modelPath.parent.fileName.toString())
			assertEquals(modelBytes.toList(), store.resolve(hash)?.modelPath?.readBytes()?.toList())
			assertTrue(Files.list(root).use { paths -> paths.noneMatch { it.fileName.toString().startsWith(".import-") } })
		} finally {
			deleteTree(sources)
			deleteTree(root)
		}
	}

	@Test
	fun `failed integrity validation never exposes a partial managed model`() {
		val root = Files.createTempDirectory("nekovr-managed-models")
		try {
			val (modelBytes, sidecarBytes) = probeBytes()
			val store = ManagedModelStore(root)
			val error = assertFailsWith<ManagedModelException> {
				store.importBytes(modelBytes, sidecarBytes, "0".repeat(64))
			}
			assertEquals(ManagedModelErrorCode.MODEL_INTEGRITY, error.code)
			assertTrue(store.list().isEmpty())
			assertTrue(Files.list(root).use { it.findAny().isEmpty })
		} finally {
			deleteTree(root)
		}
	}

	@Test
	fun `catalog refresh preserves the last verified catalog and has no fictional fallback`() {
		val root = Files.createTempDirectory("nekovr-catalog")
		try {
			val (modelBytes, sidecarBytes) = probeBytes()
			val catalog = catalog(modelBytes, sidecarBytes)
			var response = catalog.toByteArray()
			val manager = RemoteModelManager(root.toFile(), setOf("models.example.test"), RemoteContentFetcher { _, _ -> response })

			val valid = manager.refreshRemoteCatalog("https://models.example.test/catalog.json")
			assertTrue(valid.successful)
			assertEquals(1, manager.catalogStatus().size)
			assertFalse(manager.catalogStatus().single().downloaded)

			response = "{not-json".toByteArray()
			val invalid = manager.refreshRemoteCatalog("https://models.example.test/catalog.json")
			assertFalse(invalid.successful)
			assertEquals(ModelCatalogErrorCode.CATALOG_INVALID, invalid.error?.code)
			assertEquals(1, manager.catalogStatus().size)

			val emptyManager = RemoteModelManager(root.resolve("empty").toFile(), setOf("models.example.test"), RemoteContentFetcher { _, _ -> response })
			assertFalse(emptyManager.refreshRemoteCatalog("https://models.example.test/catalog.json").successful)
			assertTrue(emptyManager.catalogStatus().isEmpty())
		} finally {
			deleteTree(root)
		}
	}

	@Test
	fun `verified download state reflects only artifacts present in managed storage`() {
		val root = Files.createTempDirectory("nekovr-download")
		try {
			val (modelBytes, sidecarBytes) = probeBytes()
			val responses = mapOf(
				"https://models.example.test/catalog.json" to catalog(modelBytes, sidecarBytes).toByteArray(),
				"https://models.example.test/probe.onnx" to modelBytes,
				"https://models.example.test/probe.onnx.json" to sidecarBytes,
			)
			val manager = RemoteModelManager(root.toFile(), setOf("models.example.test"), RemoteContentFetcher { uri, _ -> assertNotNull(responses[uri.toString()]) })
			manager.refreshRemoteCatalog("https://models.example.test/catalog.json")
			val hash = ManagedModelStore.sha256(modelBytes)

			assertFalse(manager.catalogStatus().single().downloaded)
			val artifact = manager.download(hash)
			assertEquals(hash, artifact.metadata.modelSha256)
			assertTrue(manager.catalogStatus().single().downloaded)
			assertNotNull(manager.resolve(hash))
		} finally {
			deleteTree(root)
		}
	}

	@Test
	fun `allowlist and downloaded hashes are enforced with typed errors`() {
		val root = Files.createTempDirectory("nekovr-download-reject")
		try {
			val (modelBytes, sidecarBytes) = probeBytes()
			val catalogBytes = catalog(modelBytes, sidecarBytes).toByteArray()
			val manager = RemoteModelManager(root.toFile(), setOf("models.example.test"), RemoteContentFetcher { uri: URI, _ ->
				when (uri.path) {
					"/catalog.json" -> catalogBytes
					"/probe.onnx" -> modelBytes + byteArrayOf(1)
					else -> sidecarBytes
				}
			})
			val rejected = manager.refreshRemoteCatalog("http://models.example.test/catalog.json")
			assertEquals(ModelCatalogErrorCode.URL_REJECTED, rejected.error?.code)
			assertTrue(manager.refreshRemoteCatalog("https://models.example.test/catalog.json").successful)

			val error = assertFailsWith<ModelCatalogException> { manager.download(ManagedModelStore.sha256(modelBytes)) }
			assertEquals(ModelCatalogErrorCode.MODEL_INTEGRITY, error.code)
			assertNull(manager.resolve(ManagedModelStore.sha256(modelBytes)))
		} finally {
			deleteTree(root)
		}
	}

	private fun probeBytes(): Pair<ByteArray, ByteArray> {
		fun resource(name: String): ByteArray = requireNotNull(javaClass.getResource("/dev/slimevr/ai/probe/$name")).readBytes()
		return resource("probe.onnx") to resource("probe.onnx.json")
	}

	private fun catalog(model: ByteArray, sidecar: ByteArray): String = """
		{
		  "format": "nekovr-model-catalog-v1",
		  "schema_version": 1,
		  "models": [{
		    "model_id": "nekovr-provider-probe",
		    "model_version": "1.0.0",
		    "name": "Provider probe",
		    "description": "Test model",
		    "size_tier": "S",
		    "model_url": "https://models.example.test/probe.onnx",
		    "metadata_url": "https://models.example.test/probe.onnx.json",
		    "model_size_bytes": ${model.size},
		    "model_sha256": "${ManagedModelStore.sha256(model)}",
		    "metadata_size_bytes": ${sidecar.size},
		    "metadata_sha256": "${ManagedModelStore.sha256(sidecar)}"
		  }]
		}
	""".trimIndent()

	private fun deleteTree(root: Path) {
		if (!Files.exists(root)) return
		Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
	}
}
