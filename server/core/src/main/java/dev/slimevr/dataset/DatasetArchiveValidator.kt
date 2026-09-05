package dev.slimevr.dataset

import com.github.luben.zstd.ZstdInputStream
import dev.slimevr.dataset.generated.DatasetV1Bindings
import dev.slimevr.dataset.generated.DatasetV1Reader
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile

class DatasetArchiveValidator {
	fun validate(path: Path): DatasetValidationReport {
		val findings = mutableListOf<ValidationFinding>()
		var manifest: DatasetManifest? = null
		var frames = 0L
		var resetLabels = 0L
		runCatching {
			ZipFile(path.toFile()).use { zip ->
				val names = zip.entries().asSequence().map { it.name }.toSet()
				if ("telemetry.bin" in names) return prototype(path, "GUI_TELEMETRY_BIN", listOf("schema version", "roster", "validity/provenance", "angular velocity", "footer/checksum"))
				if ("telemetry.zst" in names && "telemetry.fbs.zst" !in names) return prototype(path, "SERVER_TELEMETRY_ZST", listOf("FlatBuffer header", "channel registry", "stable roster IDs", "validity/provenance", "footer"))
				val manifestEntry = zip.getEntry("manifest.json") ?: error("manifest.json is missing")
				val telemetryEntry = zip.getEntry("telemetry.fbs.zst") ?: error("telemetry.fbs.zst is missing")
				manifest = DatasetManifest.fromJsonString(zip.getInputStream(manifestEntry).reader().readText())
				if (manifest!!.schemaMajor != DATASET_SCHEMA_MAJOR) findings += fatal("UNSUPPORTED_SCHEMA", "Unsupported major schema ${manifest!!.schemaMajor}")
				if (!manifest!!.privacy.consent) findings += fatal("CONSENT_MISSING", "Recording consent is not present")
				val checksum = zip.getInputStream(telemetryEntry).use(::sha256)
				if (checksum != manifest!!.telemetrySha256) findings += fatal("CHECKSUM_MISMATCH", "Telemetry SHA-256 does not match the manifest")
				var expectedSequence = 0L
				var sawHeader = false
				var sawRoster = false
				var sawFooter = false
				ZstdInputStream(BufferedInputStream(zip.getInputStream(telemetryEntry))).use { input ->
					while (true) {
						val bytes = readRecord(input) ?: break
						val record = DatasetV1Reader.read(bytes)
						if (record.sequence != expectedSequence) findings += fatal("SEQUENCE_GAP", "Expected record $expectedSequence but found ${record.sequence}")
						expectedSequence = record.sequence + 1
						when (record.type) {
							DatasetV1Bindings.RECORD_HEADER -> {
								sawHeader = true
								if (record.schemaMajor != DATASET_SCHEMA_MAJOR) findings += fatal("HEADER_SCHEMA", "Telemetry header schema is incompatible")
								if (record.sessionId != manifest!!.sessionId) findings += fatal("SESSION_MISMATCH", "Header and manifest session IDs differ")
							}
							DatasetV1Bindings.RECORD_ROSTER -> sawRoster = true
							DatasetV1Bindings.RECORD_FRAMES -> frames += record.frames.size
							DatasetV1Bindings.RECORD_EVENTS -> resetLabels += record.resetLabels.size
							DatasetV1Bindings.RECORD_FOOTER -> sawFooter = true
						}
					}
				}
				if (!sawHeader) findings += fatal("HEADER_MISSING", "Telemetry header is missing")
				if (!sawRoster) findings += fatal("ROSTER_MISSING", "Tracker roster is missing")
				if (!sawFooter) findings += fatal("FOOTER_MISSING", "Telemetry footer is missing")
				if (frames != manifest!!.quality.writtenFrames) findings += fatal("FRAME_COUNT", "Decoded and manifest frame counts differ")
				if (manifest!!.trackers.any { it.imuType == "UNKNOWN" || it.transport == "UNKNOWN" }) {
					findings += ValidationFinding("UNKNOWN_HARDWARE", FindingSeverity.WARNING, "One or more trackers have explicit UNKNOWN hardware metadata")
				}
			}
		}.onFailure { findings += fatal("ARCHIVE_READ_FAILED", it.message ?: it.javaClass.simpleName) }
		return DatasetValidationReport(path.toString(), manifest?.schemaMajor, manifest?.schemaMinor, frames, resetLabels, findings)
	}

	private fun prototype(path: Path, code: String, missing: List<String>) = DatasetValidationReport(
		path.toString(), null, null, 0, 0,
		listOf(ValidationFinding(code, FindingSeverity.FATAL, "Prototype archive is unsupported and is not training-ready", missing)),
	)

	private fun fatal(code: String, message: String) = ValidationFinding(code, FindingSeverity.FATAL, message)

	companion object {
		fun readRecord(input: InputStream): ByteArray? {
			val first = input.read()
			if (first < 0) return null
			val b1 = input.read(); val b2 = input.read(); val b3 = input.read()
			if (b1 < 0 || b2 < 0 || b3 < 0) throw EOFException("Truncated record length")
			val length = first or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
			require(length in 8..16_777_216) { "Invalid FlatBuffer record length $length" }
			return input.readNBytes(length).also { if (it.size != length) throw EOFException("Truncated FlatBuffer record") }
		}

		private fun sha256(input: InputStream): String {
			val digest = MessageDigest.getInstance("SHA-256")
			input.use {
				val bytes = ByteArray(64 * 1024)
				while (true) {
					val read = it.read(bytes)
					if (read < 0) break
					digest.update(bytes, 0, read)
				}
			}
			return digest.digest().joinToString("") { "%02x".format(it) }
		}
	}
}
