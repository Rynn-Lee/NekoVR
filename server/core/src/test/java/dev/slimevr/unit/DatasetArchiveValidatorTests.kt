package dev.slimevr.unit

import dev.slimevr.dataset.ChannelProvenance
import dev.slimevr.dataset.CollectionProfile
import dev.slimevr.dataset.DatasetArchiveValidator
import dev.slimevr.dataset.DatasetConformanceFixture
import dev.slimevr.dataset.DatasetQualityCounters
import dev.slimevr.dataset.TelemetryChannel
import dev.slimevr.dataset.TelemetryChannelRegistry
import dev.slimevr.dataset.ChannelValidity
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatasetArchiveValidatorTests {
	@Test
	fun `reset relationship mutation archives are rejected`(@TempDir root: Path) {
		DatasetConformanceFixture.writeResetRelationMutations(root).forEach { archive ->
			val report = DatasetArchiveValidator().validate(archive)
			assertFalse(report.valid, "${archive.fileName} must fail reset relationship validation: ${report.findings}")
		}
	}

	@Test
	fun `minimum standard and full profiles validate without fabricated optional samples`(@TempDir root: Path) {
		CollectionProfile.entries.forEach { profile ->
			val archive = DatasetConformanceFixture.writeArchive(root, "${profile.name}.nvrdata", profile)
			val report = DatasetArchiveValidator().validate(archive)
			assertTrue(report.valid, "$profile must validate: ${report.findings}")
		}
	}

	@Test
	fun `complete unknown optional descriptor is forward compatible`(@TempDir root: Path) {
		val optional = TelemetryChannel(
			1001,
			"future_optional_signal",
			"ratio",
			"device",
			"change",
			"fp32",
			CollectionProfile.FULL_FIDELITY,
			setOf(ChannelProvenance.FIRMWARE_REPORTED),
		)
		val archive = DatasetConformanceFixture.writeArchive(root, channels = TelemetryChannelRegistry.channels + optional)
		assertTrue(DatasetArchiveValidator().validate(archive).valid)
	}

	@Test
	fun `descriptor collisions incompatible semantics and missing required channels fail`(@TempDir root: Path) {
		val canonical = TelemetryChannelRegistry.channels
		val cases = mapOf(
			"collision" to (canonical + canonical.first()),
			"semantic" to canonical.map { if (it.id == 1) it.copy(unit = "degrees") else it },
			"incomplete-unknown" to (canonical + TelemetryChannel(1002, "", "", "", "", "", CollectionProfile.FULL_FIDELITY, emptySet())),
			"missing-required" to canonical.filterNot { it.id == 45 },
		)
		cases.forEach { (name, channels) ->
			val archive = DatasetConformanceFixture.writeArchive(root, "$name.nvrdata", channels = channels)
			assertFalse(DatasetArchiveValidator().validate(archive).valid, name)
		}
	}

	@Test
	fun `footer completeness duration counters and checksum are authoritative`(@TempDir root: Path) {
		val cases = listOf(
			DatasetConformanceFixture.writeArchive(root, "incomplete.nvrdata", footerComplete = false),
			DatasetConformanceFixture.writeArchive(root, "duration.nvrdata", footerDurationNs = 1L),
			DatasetConformanceFixture.writeArchive(root, "counters.nvrdata", footerCounters = DatasetQualityCounters()),
			DatasetConformanceFixture.writeArchive(root, "checksum.nvrdata", footerChecksumOverride = "0".repeat(64)),
		)
		cases.forEach { archive -> assertFalse(DatasetArchiveValidator().validate(archive).valid, archive.fileName.toString()) }
	}

	@Test
	fun `semantic mutations fail closed with specific findings`(@TempDir root: Path) {
		val cases = mapOf(
			"unrostered" to DatasetConformanceFixture.writeArchive(root, "unrostered.nvrdata", frameTransform = { frame ->
				frame.copy(trackers = frame.trackers.map { it.copy(sessionTrackerId = "ghost") })
			}),
			"never-produced" to DatasetConformanceFixture.writeArchive(root, "never-produced.nvrdata", frameTransform = { frame ->
				frame.copy(trackers = frame.trackers.map { it.copy(nativeChannels = it.nativeChannels.filterNot { sample -> sample.channelId == 15 }) })
			}),
			"provenance" to DatasetConformanceFixture.writeArchive(root, "provenance.nvrdata", frameTransform = { frame ->
				frame.copy(trackers = frame.trackers.map { tracker -> tracker.copy(nativeChannels = tracker.nativeChannels.map { sample -> if (sample.channelId == 15) sample.copy(provenance = ChannelProvenance.USER_ANNOTATED) else sample }) })
			}),
			"fabricated-zero" to DatasetConformanceFixture.writeArchive(root, "fabricated-zero.nvrdata", frameTransform = { frame ->
				frame.copy(contextSamples = frame.contextSamples.map { it.copy(orientationValidity = ChannelValidity.VALID) })
			}),
			"missing-context" to DatasetConformanceFixture.writeArchive(root, "missing-context.nvrdata", frameTransform = { it.copy(skeletonBones = emptyList()) }),
			"lost-gap" to DatasetConformanceFixture.writeArchive(root, "lost-gap.nvrdata", eventsTransform = { events -> events.filterNot { it.type == "GAP" } }),
			"lost-reset" to DatasetConformanceFixture.writeArchive(root, "lost-reset.nvrdata", eventsTransform = { events -> events.filterNot { it.resetOutcome == "REQUESTED" } }),
		)
		val expected = mapOf(
			"unrostered" to "UNROSTERED_ID",
			"never-produced" to "REQUIRED_CHANNEL_NEVER_PRODUCED",
			"provenance" to "CHANNEL_PROVENANCE",
			"fabricated-zero" to "FABRICATED_VALID_OPTIONAL",
			"missing-context" to "REQUIRED_CONTEXT_MISSING",
			"lost-gap" to "GAP_CONTINUITY",
			"lost-reset" to "RESET_CONTINUITY",
		)
		cases.forEach { (name, archive) ->
			val report = DatasetArchiveValidator().validate(archive)
			assertFalse(report.valid, name)
			assertTrue(report.findings.any { it.code == expected.getValue(name) }, "$name: ${report.findings}")
		}
	}

	@Test
	fun `canonical container rejects extra compressed and prototype layouts`(@TempDir root: Path) {
		val canonical = DatasetConformanceFixture.writeArchive(root)
		val members = ZipFile(canonical.toFile()).use { zip ->
			mapOf(
				"manifest.json" to zip.getInputStream(zip.getEntry("manifest.json")).readBytes(),
				"telemetry.fbs.zst" to zip.getInputStream(zip.getEntry("telemetry.fbs.zst")).readBytes(),
			)
		}
		val extra = root.resolve("extra.nvrdata")
		writeZip(extra, members + ("../unexpected" to byteArrayOf(1)), stored = true)
		assertFalse(DatasetArchiveValidator().validate(extra).valid)
		val compressed = root.resolve("compressed.nvrdata")
		writeZip(compressed, members, stored = false)
		assertFalse(DatasetArchiveValidator().validate(compressed).valid)

		mapOf("telemetry.bin" to "GUI_TELEMETRY_BIN", "telemetry.zst" to "SERVER_TELEMETRY_ZST").forEach { (member, code) ->
			val prototype = root.resolve("$member.nvrdata")
			writeZip(prototype, mapOf(member to byteArrayOf(1)), stored = true)
			val report = DatasetArchiveValidator().validate(prototype)
			assertFalse(report.valid)
			assertTrue(report.findings.any { it.code == code && "training-ready" in it.message })
		}
	}

	private fun writeZip(path: Path, members: Map<String, ByteArray>, stored: Boolean) {
		ZipOutputStream(Files.newOutputStream(path)).use { zip ->
			members.forEach { (name, bytes) ->
				val entry = ZipEntry(name)
				if (stored) {
					entry.method = ZipEntry.STORED
					entry.size = bytes.size.toLong()
					entry.compressedSize = entry.size
					entry.crc = CRC32().apply { update(bytes) }.value
				}
				zip.putNextEntry(entry)
				zip.write(bytes)
				zip.closeEntry()
			}
		}
	}
}
