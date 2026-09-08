package dev.slimevr.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

enum class ModelCatalogErrorCode {
	CATALOG_INVALID,
	URL_REJECTED,
	DOWNLOAD_FAILED,
	MODEL_NOT_FOUND,
	MODEL_INTEGRITY,
}

class ModelCatalogException(val code: ModelCatalogErrorCode, message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class ModelCatalogEntry(
	val modelId: String,
	val modelVersion: String,
	val name: String,
	val description: String,
	val sizeTier: String,
	val modelUri: URI,
	val sidecarUri: URI,
	val modelSizeBytes: Long,
	val modelSha256: String,
	val sidecarSizeBytes: Long,
	val sidecarSha256: String,
)

data class ModelCatalogSnapshot(val schemaVersion: Int, val models: List<ModelCatalogEntry>)

data class ModelCatalogStatus(
	val entry: ModelCatalogEntry,
	val downloaded: Boolean,
	val compatible: Boolean,
	val compatibilityError: String? = null,
)

data class ModelCatalogResult(val catalog: ModelCatalogSnapshot? = null, val error: ModelCatalogException? = null) {
	val successful: Boolean
		get() = catalog != null && error == null
}

enum class ModelDownloadStage {
	DOWNLOADING_MODEL,
	DOWNLOADING_METADATA,
	VERIFYING,
	IMPORTING,
	COMPLETED,
}

data class ModelDownloadProgress(val stage: ModelDownloadStage, val bytesCompleted: Long, val bytesTotal: Long, val modelSha256: String)

fun interface RemoteContentFetcher {
	fun fetch(uri: URI, maximumBytes: Long): ByteArray
}

class HttpRemoteContentFetcher : RemoteContentFetcher {
	private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()

	override fun fetch(uri: URI, maximumBytes: Long): ByteArray {
		val request = HttpRequest.newBuilder().uri(uri).timeout(Duration.ofSeconds(30)).GET().build()
		val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
		if (response.statusCode() != 200) throw ModelCatalogException(ModelCatalogErrorCode.DOWNLOAD_FAILED, "HTTP ${response.statusCode()} for $uri")
		if (maximumBytes !in 1 until Int.MAX_VALUE.toLong()) throw ModelCatalogException(ModelCatalogErrorCode.DOWNLOAD_FAILED, "Response size limit is invalid")
		return response.body().use { body ->
			body.readNBytes(maximumBytes.toInt() + 1).takeIf { it.size.toLong() <= maximumBytes }
				?: throw ModelCatalogException(ModelCatalogErrorCode.DOWNLOAD_FAILED, "Response exceeds the declared size limit")
		}
	}
}

class RemoteModelManager(
	modelsDir: java.io.File,
	private val allowedHosts: Set<String> = setOf("raw.githubusercontent.com"),
	private val fetcher: RemoteContentFetcher = HttpRemoteContentFetcher(),
) {
	val store = ManagedModelStore(modelsDir.toPath())
	val history: ModelHistoryStore by lazy { ModelHistoryStore(this) }
	private val mapper = ObjectMapper()
	private val activeCatalog = AtomicReference<ModelCatalogSnapshot?>(null)

	fun refreshRemoteCatalog(catalogUrl: String = DEFAULT_CATALOG_URL): ModelCatalogResult = try {
		val uri = checkedHttpsUri(catalogUrl)
		val parsed = parseCatalog(fetcher.fetch(uri, MAX_CATALOG_BYTES).toString(Charsets.UTF_8))
		activeCatalog.set(parsed)
		ModelCatalogResult(catalog = parsed)
	} catch (error: ModelCatalogException) {
		ModelCatalogResult(catalog = activeCatalog.get(), error = error)
	} catch (error: Exception) {
		ModelCatalogResult(
			catalog = activeCatalog.get(),
			error = ModelCatalogException(ModelCatalogErrorCode.DOWNLOAD_FAILED, "Unable to refresh model catalog: ${error.message}", error),
		)
	}

	fun parseCatalog(json: String): ModelCatalogSnapshot {
		val root = try {
			mapper.readTree(json)
		} catch (error: Exception) {
			throw ModelCatalogException(ModelCatalogErrorCode.CATALOG_INVALID, "Unable to parse model catalog", error)
		}
		if (root.path("format").asText() != CATALOG_FORMAT || root.path("schema_version").asInt(-1) != CATALOG_SCHEMA_VERSION) {
			throw ModelCatalogException(ModelCatalogErrorCode.CATALOG_INVALID, "Unsupported model catalog format")
		}
		val modelsNode = root.path("models")
		if (!modelsNode.isArray) throw ModelCatalogException(ModelCatalogErrorCode.CATALOG_INVALID, "Catalog models must be an array")
		val models = modelsNode.map(::parseEntry)
		if (models.map { it.modelId }.toSet().size != models.size || models.map { it.modelSha256 }.toSet().size != models.size) {
			throw ModelCatalogException(ModelCatalogErrorCode.CATALOG_INVALID, "Catalog model IDs and hashes must be unique")
		}
		return ModelCatalogSnapshot(CATALOG_SCHEMA_VERSION, models)
	}

	fun catalogStatus(): List<ModelCatalogStatus> = activeCatalog.get()?.models.orEmpty().map { entry ->
		val local = runCatching { store.resolve(entry.modelSha256) }.getOrNull()
		ModelCatalogStatus(
			entry = entry,
			downloaded = local != null,
			compatible = local == null || local.metadata.modelId == entry.modelId,
			compatibilityError = local?.takeIf { it.metadata.modelId != entry.modelId }?.let { "Local sidecar model ID differs from catalog" },
		)
	}

	fun catalogVersion(): Int = activeCatalog.get()?.schemaVersion ?: 0

	fun catalogName(modelSha256: String): String? = activeCatalog.get()?.models?.firstOrNull { it.modelSha256 == modelSha256 }?.name

	fun importLocal(modelPath: Path, sidecarPath: Path, expectedModelSha256: String? = null): ManagedModelArtifact =
		store.importModel(modelPath, sidecarPath, expectedModelSha256)

	fun resolve(modelSha256: String): ManagedModelArtifact? = store.resolve(modelSha256)

	fun download(modelSha256: String, progress: (ModelDownloadProgress) -> Unit = {}): ManagedModelArtifact {
		val normalized = modelSha256.lowercase()
		val entry = activeCatalog.get()?.models?.firstOrNull { it.modelSha256 == normalized }
			?: throw ModelCatalogException(ModelCatalogErrorCode.MODEL_NOT_FOUND, "Model hash is not present in the verified catalog")
		store.resolve(normalized)?.let { return it }
		val total = entry.modelSizeBytes + entry.sidecarSizeBytes
		progress(ModelDownloadProgress(ModelDownloadStage.DOWNLOADING_MODEL, 0L, total, normalized))
		val modelBytes = fetchVerified(entry.modelUri, entry.modelSizeBytes, entry.modelSha256)
		progress(ModelDownloadProgress(ModelDownloadStage.DOWNLOADING_METADATA, entry.modelSizeBytes, total, normalized))
		val sidecarBytes = fetchVerified(entry.sidecarUri, entry.sidecarSizeBytes, entry.sidecarSha256)
		progress(ModelDownloadProgress(ModelDownloadStage.VERIFYING, total, total, normalized))
		progress(ModelDownloadProgress(ModelDownloadStage.IMPORTING, total, total, normalized))
		val artifact = try {
			store.importBytes(modelBytes, sidecarBytes, normalized)
		} catch (error: ManagedModelException) {
			throw ModelCatalogException(ModelCatalogErrorCode.MODEL_INTEGRITY, error.message ?: "Downloaded model failed validation", error)
		}
		progress(ModelDownloadProgress(ModelDownloadStage.COMPLETED, total, total, normalized))
		return artifact
	}

	private fun parseEntry(node: JsonNode): ModelCatalogEntry {
		fun text(name: String): String = node.path(name).takeIf(JsonNode::isTextual)?.asText()?.takeIf(String::isNotBlank)
			?: throw ModelCatalogException(ModelCatalogErrorCode.CATALOG_INVALID, "Catalog model is missing $name")
		fun size(name: String, maximum: Long): Long = node.path(name).takeIf(JsonNode::isIntegralNumber)?.asLong()?.takeIf { it in 1..maximum }
			?: throw ModelCatalogException(ModelCatalogErrorCode.CATALOG_INVALID, "Catalog $name is outside limits")
		fun hash(name: String): String = text(name).lowercase().takeIf(HASH_REGEX::matches)
			?: throw ModelCatalogException(ModelCatalogErrorCode.CATALOG_INVALID, "Catalog $name is not SHA-256")
		return ModelCatalogEntry(
			modelId = text("model_id"),
			modelVersion = text("model_version"),
			name = text("name"),
			description = node.path("description").asText(""),
			sizeTier = node.path("size_tier").asText(""),
			modelUri = checkedHttpsUri(text("model_url")),
			sidecarUri = checkedHttpsUri(text("metadata_url")),
			modelSizeBytes = size("model_size_bytes", MAX_MODEL_BYTES),
			modelSha256 = hash("model_sha256"),
			sidecarSizeBytes = size("metadata_size_bytes", MAX_SIDECAR_BYTES),
			sidecarSha256 = hash("metadata_sha256"),
		)
	}

	private fun checkedHttpsUri(value: String): URI {
		val uri = try {
			URI.create(value)
		} catch (error: IllegalArgumentException) {
			throw ModelCatalogException(ModelCatalogErrorCode.URL_REJECTED, "Invalid catalog URL", error)
		}
		val host = uri.host?.lowercase()
		if (uri.scheme != "https" || host == null || host !in allowedHosts || uri.userInfo != null || uri.fragment != null) {
			throw ModelCatalogException(ModelCatalogErrorCode.URL_REJECTED, "URL is not an allowlisted HTTPS resource")
		}
		return uri
	}

	private fun fetchVerified(uri: URI, expectedSize: Long, expectedSha256: String): ByteArray {
		val checked = checkedHttpsUri(uri.toString())
		val bytes = try {
			fetcher.fetch(checked, expectedSize)
		} catch (error: ModelCatalogException) {
			throw error
		} catch (error: Exception) {
			throw ModelCatalogException(ModelCatalogErrorCode.DOWNLOAD_FAILED, "Unable to download $checked: ${error.message}", error)
		}
		if (bytes.size.toLong() != expectedSize || ManagedModelStore.sha256(bytes) != expectedSha256) {
			throw ModelCatalogException(ModelCatalogErrorCode.MODEL_INTEGRITY, "Downloaded size or SHA-256 differs from catalog")
		}
		return bytes
	}

	companion object {
		const val DEFAULT_CATALOG_URL = "https://raw.githubusercontent.com/Rynn-Lee/NekoVR/main/models/catalog.json"
		private const val CATALOG_FORMAT = "nekovr-model-catalog-v1"
		private const val CATALOG_SCHEMA_VERSION = 1
		private const val MAX_CATALOG_BYTES = 2L * 1024L * 1024L
		private const val MAX_MODEL_BYTES = 64L * 1024L * 1024L
		private const val MAX_SIDECAR_BYTES = 1024L * 1024L
		private val HASH_REGEX = Regex("^[0-9a-f]{64}$")
	}
}
