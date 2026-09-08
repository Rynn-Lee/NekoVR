package dev.slimevr.ai

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Comparator
import java.util.UUID
import kotlin.io.path.createDirectories

enum class ManagedModelErrorCode {
	INVALID_ARGUMENT,
	PATH_REJECTED,
	FILE_NOT_FOUND,
	MODEL_INTEGRITY,
	IO_ERROR,
}

class ManagedModelException(
	val code: ManagedModelErrorCode,
	message: String,
	cause: Throwable? = null,
) : RuntimeException(message, cause)

data class ManagedModelArtifact(
	val metadata: ModelArtifactMetadata,
	val modelPath: Path,
	val sidecarPath: Path,
)

/** Hash-addressed model storage. A complete model becomes visible through one atomic directory move. */
class ManagedModelStore(
	modelsRoot: Path,
	private val maximumModelBytes: Long = 64L * 1024L * 1024L,
	private val maximumSidecarBytes: Long = 1024L * 1024L,
) {
	val root: Path = modelsRoot.toAbsolutePath().normalize().apply { createDirectories() }.toRealPath()

	fun importModel(modelSource: Path, sidecarSource: Path, expectedModelSha256: String? = null): ManagedModelArtifact {
		val model = validateSource(modelSource, maximumModelBytes, "model")
		val sidecar = validateSource(sidecarSource, maximumSidecarBytes, "metadata")
		return importStaged(expectedModelSha256) { staging ->
			Files.copy(model, staging.resolve(MODEL_FILE), StandardCopyOption.COPY_ATTRIBUTES)
			Files.copy(sidecar, staging.resolve(SIDECAR_FILE), StandardCopyOption.COPY_ATTRIBUTES)
		}
	}

	fun importBytes(modelBytes: ByteArray, sidecarBytes: ByteArray, expectedModelSha256: String): ManagedModelArtifact {
		if (modelBytes.isEmpty() || modelBytes.size > maximumModelBytes) throw ManagedModelException(ManagedModelErrorCode.INVALID_ARGUMENT, "Model size is outside managed limits")
		if (sidecarBytes.isEmpty() || sidecarBytes.size > maximumSidecarBytes) throw ManagedModelException(ManagedModelErrorCode.INVALID_ARGUMENT, "Metadata size is outside managed limits")
		return importStaged(expectedModelSha256) { staging ->
			Files.write(staging.resolve(MODEL_FILE), modelBytes)
			Files.write(staging.resolve(SIDECAR_FILE), sidecarBytes)
		}
	}

	fun resolve(modelSha256: String): ManagedModelArtifact? {
		val hash = normalizedHash(modelSha256)
		val directory = safeChild(hash)
		if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) return null
		return validateManaged(directory, hash)
	}

	fun list(): List<ManagedModelArtifact> = Files.list(root).use { paths ->
		paths.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) && HASH_REGEX.matches(it.fileName.toString()) }
			.map { directory -> runCatching { validateManaged(directory, directory.fileName.toString()) }.getOrNull() }
			.filter { it != null }.map { it!! }.toList()
	}

	private fun importStaged(expectedModelSha256: String?, writer: (Path) -> Unit): ManagedModelArtifact {
		val expectedHash = expectedModelSha256?.let(::normalizedHash)
		val staging = safeChild(".import-${UUID.randomUUID()}")
		try {
			Files.createDirectory(staging)
			writer(staging)
			val metadata = try {
				ModelArtifactValidator.validate(staging.resolve(MODEL_FILE), staging.resolve(SIDECAR_FILE))
			} catch (error: ModelLoadException) {
				throw ManagedModelException(ManagedModelErrorCode.MODEL_INTEGRITY, error.message ?: "Model validation failed", error)
			}
			val hash = normalizedHash(metadata.modelSha256)
			if (expectedHash != null && expectedHash != hash) throw ManagedModelException(ManagedModelErrorCode.MODEL_INTEGRITY, "Imported model SHA-256 differs from the expected hash")
			val destination = safeChild(hash)
			if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
				return resolve(hash) ?: throw ManagedModelException(ManagedModelErrorCode.MODEL_INTEGRITY, "Existing hash directory is not a valid managed model")
			}
			try {
				Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE)
			} catch (error: AtomicMoveNotSupportedException) {
				throw ManagedModelException(ManagedModelErrorCode.IO_ERROR, "Managed model storage does not support atomic imports", error)
			}
			return validateManaged(destination, hash)
		} catch (error: ManagedModelException) {
			throw error
		} catch (error: Exception) {
			throw ManagedModelException(ManagedModelErrorCode.IO_ERROR, "Unable to import model: ${error.message}", error)
		} finally {
			deleteTree(staging)
		}
	}

	private fun validateManaged(directory: Path, expectedHash: String): ManagedModelArtifact {
		val canonical = directory.toRealPath(LinkOption.NOFOLLOW_LINKS)
		if (!canonical.startsWith(root) || canonical.parent != root || Files.isSymbolicLink(canonical)) throw ManagedModelException(ManagedModelErrorCode.PATH_REJECTED, "Managed model path escapes the models root")
		val model = canonical.resolve(MODEL_FILE)
		val sidecar = canonical.resolve(SIDECAR_FILE)
		if (!Files.isRegularFile(model, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(model) || Files.isSymbolicLink(sidecar)) throw ManagedModelException(ManagedModelErrorCode.FILE_NOT_FOUND, "Managed model files are missing")
		val metadata = try {
			ModelArtifactValidator.validate(model, sidecar)
		} catch (error: ModelLoadException) {
			throw ManagedModelException(ManagedModelErrorCode.MODEL_INTEGRITY, error.message ?: "Managed model validation failed", error)
		}
		if (metadata.modelSha256 != expectedHash) throw ManagedModelException(ManagedModelErrorCode.MODEL_INTEGRITY, "Managed directory hash differs from model SHA-256")
		return ManagedModelArtifact(metadata, model, sidecar)
	}

	private fun validateSource(source: Path, limit: Long, label: String): Path {
		val absolute = source.toAbsolutePath().normalize()
		if (Files.isSymbolicLink(absolute)) throw ManagedModelException(ManagedModelErrorCode.PATH_REJECTED, "$label symlinks are not accepted")
		if (!Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) throw ManagedModelException(ManagedModelErrorCode.FILE_NOT_FOUND, "$label file does not exist")
		if (Files.size(absolute) !in 1..limit) throw ManagedModelException(ManagedModelErrorCode.INVALID_ARGUMENT, "$label size is outside managed limits")
		return absolute.toRealPath(LinkOption.NOFOLLOW_LINKS)
	}

	private fun safeChild(name: String): Path {
		val candidate = root.resolve(name).normalize()
		if (candidate.parent != root || !candidate.startsWith(root)) throw ManagedModelException(ManagedModelErrorCode.PATH_REJECTED, "Managed model path escapes the models root")
		return candidate
	}

	private fun normalizedHash(value: String): String = value.lowercase().takeIf(HASH_REGEX::matches)
		?: throw ManagedModelException(ManagedModelErrorCode.INVALID_ARGUMENT, "Model SHA-256 must contain 64 hexadecimal characters")

	private fun deleteTree(path: Path) {
		if (!path.startsWith(root) || path.parent != root || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
		Files.walk(path).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
	}

	companion object {
		private const val MODEL_FILE = "model.onnx"
		private const val SIDECAR_FILE = "model.onnx.json"
		private val HASH_REGEX = Regex("^[0-9a-f]{64}$")

		fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
	}
}
