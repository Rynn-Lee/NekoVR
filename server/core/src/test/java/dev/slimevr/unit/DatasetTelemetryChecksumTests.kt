package dev.slimevr.unit

import com.github.luben.zstd.ZstdInputStream
import dev.slimevr.dataset.DatasetArchiveValidator
import dev.slimevr.dataset.DatasetConformanceFixture
import dev.slimevr.dataset.DatasetManifest
import dev.slimevr.dataset.DatasetTelemetryChecksum
import dev.slimevr.dataset.generated.DatasetV1Bindings
import dev.slimevr.dataset.generated.DatasetV1Reader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile

class DatasetTelemetryChecksumTests {
	@Test
	fun testFixedPreFooterByteScope() {
		assertEquals(
			"75b727446b0c555dd1c47ae5848c42b36f7c1b8f9fc207b12dc6da75313c29a3",
			DatasetTelemetryChecksum.computeHex(listOf(byteArrayOf(1, 2, 3), byteArrayOf(0xfe.toByte(), 0xff.toByte()))),
		)
		assertNotEquals(
			DatasetTelemetryChecksum.computeHex(listOf(byteArrayOf(1, 2, 3), byteArrayOf(0xfe.toByte(), 0xff.toByte()))),
			DatasetTelemetryChecksum.computeHex(listOf(byteArrayOf(1, 2, 3, 0xfe.toByte(), 0xff.toByte()))),
			"record length prefixes are part of the checksum scope",
		)
	}

	@Test
	fun testFooterAndManifestUseIndependentDocumentedScopes(@TempDir directory: Path) {
		val archive = DatasetConformanceFixture.writeArchive(directory)
		ZipFile(archive.toFile()).use { zip ->
			val telemetry = zip.getInputStream(zip.getEntry("telemetry.fbs.zst")).readAllBytes()
			val manifest = DatasetManifest.fromJsonString(zip.getInputStream(zip.getEntry("manifest.json")).reader().readText())
			assertEquals(sha256(telemetry), manifest.telemetrySha256)

			val records = ZstdInputStream(telemetry.inputStream()).use { input ->
				buildList {
					while (true) add(DatasetArchiveValidator.readRecord(input) ?: break)
				}
			}
			val footer = DatasetV1Reader.read(records.last()).also {
				assertEquals(DatasetV1Bindings.RECORD_FOOTER, it.type)
			}.footer!!
			assertEquals(DatasetTelemetryChecksum.computeHex(records.dropLast(1)), footer.telemetrySha256)
			assertNotEquals(manifest.telemetrySha256, footer.telemetrySha256)
		}
	}

	private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
		.digest(bytes).joinToString("") { "%02x".format(it) }
}
