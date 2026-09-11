package dev.slimevr.dataset

import com.github.luben.zstd.ZstdInputStream
import dev.slimevr.dataset.generated.DatasetV1Bindings
import dev.slimevr.dataset.generated.DatasetV1Reader
import dev.slimevr.reset.FLAG_INSUFFICIENT_CONTEXT
import dev.slimevr.reset.FLAG_WINDOW_TRUNCATED
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class DatasetArchiveValidator {
	fun validate(path: Path): DatasetValidationReport {
		val findings = mutableListOf<ValidationFinding>()
		var manifest: DatasetManifest? = null
		var frames = 0L
		var resetLabels = 0L
		var lastFrameIndex = -1L
		val labels = mutableListOf<DatasetResetLabel>()
		val decodedFrames = mutableListOf<SessionFrame>()
		val events = mutableListOf<DatasetEvent>()
		runCatching {
			ZipFile(path.toFile()).use { zip ->
				val entries = zip.entries().asSequence().toList()
				val names = entries.map { it.name }
				if ("telemetry.bin" in names) return prototype(path, "GUI_TELEMETRY_BIN", listOf("schema version", "roster", "validity/provenance", "angular velocity", "footer/checksum"))
				if ("telemetry.zst" in names && "telemetry.fbs.zst" !in names) return prototype(path, "SERVER_TELEMETRY_ZST", listOf("FlatBuffer header", "channel registry", "stable roster IDs", "validity/provenance", "footer"))
				validateContainer(entries)
				val manifestEntry = zip.getEntry(MANIFEST_ENTRY) ?: error("$MANIFEST_ENTRY is missing")
				val telemetryEntry = zip.getEntry(TELEMETRY_ENTRY) ?: error("$TELEMETRY_ENTRY is missing")
				val currentManifest = DatasetManifest.fromJsonString(zip.getInputStream(manifestEntry).reader().readText())
				manifest = currentManifest
				if (currentManifest.schemaMajor != DATASET_SCHEMA_MAJOR) findings += fatal("UNSUPPORTED_SCHEMA", "Unsupported major schema ${currentManifest.schemaMajor}")
				if (!currentManifest.privacy.consent) findings += fatal("CONSENT_MISSING", "Recording consent is not present")
				if (currentManifest.state !in setOf(ArchiveState.COMPLETE, ArchiveState.RECOVERED)) findings += fatal("MANIFEST_INCOMPLETE", "Canonical archive manifest state must be COMPLETE or RECOVERED")
				if (currentManifest.durationNs < 0L) findings += fatal("DURATION_INVALID", "Manifest duration cannot be negative")
				if (currentManifest.telemetryBytes != telemetryEntry.size) findings += fatal("TELEMETRY_SIZE", "Manifest telemetry byte count does not match the ZIP entry")
				val compressedChecksum = zip.getInputStream(telemetryEntry).use(::sha256)
				if (compressedChecksum != currentManifest.telemetrySha256) findings += fatal("CHECKSUM_MISMATCH", "Telemetry SHA-256 does not match the manifest")

				var expectedSequence = 0L
				var sawHeader = false
				var sawRoster = false
				var sawFooter = false
				var header: DatasetFileHeader? = null
				var roster: DatasetTrackerRoster? = null
				var footer: DatasetFooter? = null
				val footerChecksum = DatasetTelemetryChecksum()
				ZstdInputStream(BufferedInputStream(zip.getInputStream(telemetryEntry))).use { input ->
					while (true) {
						val bytes = readRecord(input) ?: break
						val record = DatasetV1Reader.read(bytes)
						if (sawFooter) findings += fatal("FOOTER_NOT_TERMINAL", "No record may follow the terminal footer")
						if (record.sequence != expectedSequence) findings += fatal("SEQUENCE_GAP", "Expected record $expectedSequence but found ${record.sequence}")
						expectedSequence = record.sequence + 1
						when (record.type) {
							DatasetV1Bindings.RECORD_HEADER -> {
								if (sawHeader || record.sequence != 0L || sawRoster || frames > 0L || resetLabels > 0L) findings += fatal("HEADER_ORDER", "Exactly one header must be the first record")
								sawHeader = true
								header = record.header
								validateHeader(record.header ?: error("header payload missing"), currentManifest, findings)
							}

							DatasetV1Bindings.RECORD_ROSTER -> {
								if (!sawHeader || sawRoster || frames > 0L || resetLabels > 0L) findings += fatal("ROSTER_ORDER", "Exactly one roster must follow the header before data records")
								sawRoster = true
								roster = record.roster
							}

							DatasetV1Bindings.RECORD_FRAMES -> {
								if (!sawHeader || !sawRoster) findings += fatal("RECORD_ORDER", "Frame records require a preceding header and roster")
								frames += record.frames.size
								decodedFrames += record.frames
								lastFrameIndex = maxOf(lastFrameIndex, record.frames.maxOfOrNull { it.frameIndex } ?: -1L)
								validateSamples(record.frames, header, findings)
							}

							DatasetV1Bindings.RECORD_EVENTS -> {
								if (!sawHeader || !sawRoster) findings += fatal("RECORD_ORDER", "Event records require a preceding header and roster")
								resetLabels += record.resetLabels.size
								labels += record.resetLabels
								events += record.events
							}

							DatasetV1Bindings.RECORD_FOOTER -> {
								if (!sawHeader || !sawRoster || sawFooter) findings += fatal("FOOTER_ORDER", "Exactly one footer must terminate the telemetry stream")
								sawFooter = true
								footer = record.footer
							}

							else -> findings += fatal("RECORD_TYPE", "Unsupported telemetry record type ${record.type}")
						}
						if (record.type != DatasetV1Bindings.RECORD_FOOTER) footerChecksum.updateRecord(bytes)
					}
				}
				if (!sawHeader) findings += fatal("HEADER_MISSING", "Telemetry header is missing")
				if (!sawRoster) findings += fatal("ROSTER_MISSING", "Tracker roster is missing")
				if (!sawFooter) findings += fatal("FOOTER_MISSING", "Telemetry footer is missing")
				validateRoster(roster, currentManifest, findings)
				validateSemanticCoverage(header, roster, decodedFrames, events, labels, footer, findings)
				validateFooter(footer, footerChecksum.digestHex(), currentManifest, frames, findings)
				if (currentManifest.trackers.any { it.imuType == "UNKNOWN" || it.transport == "UNKNOWN" }) findings += ValidationFinding("UNKNOWN_HARDWARE", FindingSeverity.WARNING, "One or more trackers have explicit UNKNOWN hardware metadata")
			}
		}.onFailure { findings += fatal("ARCHIVE_READ_FAILED", it.message ?: it.javaClass.simpleName) }
		val trackerIds = manifest?.trackers?.mapTo(hashSetOf()) { it.sessionTrackerId }.orEmpty()
		val invalidWindowMask = FLAG_INSUFFICIENT_CONTEXT or FLAG_WINDOW_TRUNCATED
		val validWindows = labels.filter { label ->
			label.sessionTrackerId in trackerIds && label.preStartFrame >= 0L && label.preStartFrame <= label.preEndFrame && label.preEndFrame <= label.postStartFrame && label.postStartFrame <= label.postEndFrame && label.postEndFrame <= lastFrameIndex + 1L && (label.qualityFlags and invalidWindowMask) == 0 && label.trainingPolicy != "EXCLUDE"
		}.map { label -> ResetWindowSummary(label.eventIndex, label.sessionTrackerId, label.preStartFrame, label.preEndFrame, label.postStartFrame, label.postEndFrame, label.qualityFlags, label.trainingPolicy) }
		return DatasetValidationReport(path.toString(), manifest?.schemaMajor, manifest?.schemaMinor, frames, resetLabels, findings, manifest?.trackers?.size ?: 0, manifest?.channelIds.orEmpty(), manifest?.quality, validWindows, manifest?.trackers?.mapTo(linkedSetOf()) { it.transport }.orEmpty())
	}

	private fun validateContainer(entries: List<ZipEntry>) {
		val names = entries.map { it.name }
		require(names.size == names.toSet().size) { "Duplicate ZIP members are forbidden" }
		require(names.toSet() == CANONICAL_ENTRIES) { "ZIP members must be exactly ${CANONICAL_ENTRIES.sorted()}" }
		entries.forEach { entry ->
			require(!entry.isDirectory && entry.name.isNotBlank()) { "ZIP directories are forbidden" }
			require(!entry.name.startsWith('/') && !entry.name.startsWith('\\') && !DRIVE_PATH.matches(entry.name)) { "Absolute ZIP member is forbidden: ${entry.name}" }
			require(entry.name.split('/', '\\').none { it == ".." || it == "." }) { "Traversal ZIP member is forbidden: ${entry.name}" }
			require(entry.method == ZipEntry.STORED) { "ZIP member must not be compressed again: ${entry.name}" }
			require(entry.size >= 0L && entry.compressedSize == entry.size) { "ZIP member size is invalid: ${entry.name}" }
			val limit = if (entry.name == MANIFEST_ENTRY) MAX_MANIFEST_BYTES else MAX_COMPRESSED_TELEMETRY_BYTES
			require(entry.size <= limit) { "ZIP member exceeds $limit bytes: ${entry.name}" }
		}
	}

	private fun validateHeader(header: DatasetFileHeader, manifest: DatasetManifest, findings: MutableList<ValidationFinding>) {
		if (header.schemaMajor != DATASET_SCHEMA_MAJOR) findings += fatal("HEADER_SCHEMA", "Telemetry header schema is incompatible")
		if (header.schemaMajor != manifest.schemaMajor || header.schemaMinor != manifest.schemaMinor) findings += fatal("SCHEMA_MISMATCH", "Header and manifest schema versions differ")
		if (header.sessionId != manifest.sessionId) findings += fatal("SESSION_MISMATCH", "Header and manifest session IDs differ")
		if (header.createdUtc != manifest.createdUtc || header.applicationVersion != manifest.applicationVersion || header.applicationCommit != manifest.applicationCommit) findings += fatal("HEADER_MANIFEST_MISMATCH", "Header identity fields differ from the manifest")
		if (header.profile != manifest.profile || header.canonicalRateHz != manifest.canonicalSampleRateHz) findings += fatal("PROFILE_MISMATCH", "Header profile/rate differs from the manifest")
		val duplicateIds = header.channels.groupingBy { it.id }.eachCount().filterValues { it != 1 }.keys
		if (duplicateIds.isNotEmpty()) findings += fatal("CHANNEL_ID_COLLISION", "Duplicate channel IDs: ${duplicateIds.sorted().joinToString()}")
		header.channels.forEach { descriptor ->
			val known = TelemetryChannelRegistry.byId[descriptor.id]
			if (known != null && descriptor != known) findings += fatal("CHANNEL_SEMANTICS", "Channel ${descriptor.id} does not match the canonical descriptor")
			if (known == null && (descriptor.id <= 0 || descriptor.name.isBlank() || descriptor.unit.isBlank() || descriptor.coordinateFrame.isBlank() || descriptor.cadence.isBlank() || descriptor.precision.isBlank() || descriptor.allowedProvenance.isEmpty())) findings += fatal("UNKNOWN_CHANNEL_INCOMPLETE", "Unknown optional channel ${descriptor.id} has an incomplete descriptor")
		}
		val ids = header.channels.mapTo(linkedSetOf()) { it.id }
		if (ids != manifest.channelIds) findings += fatal("CHANNEL_MANIFEST_MISMATCH", "Header and manifest channel registries differ")
		val missingRequired = TelemetryChannelRegistry.requiredFor(header.profile) - ids
		if (missingRequired.isNotEmpty()) findings += fatal("PROFILE_CHANNELS_MISSING", "Profile is missing required channel descriptors: ${missingRequired.sorted().joinToString()}")
	}

	private fun validateRoster(roster: DatasetTrackerRoster?, manifest: DatasetManifest, findings: MutableList<ValidationFinding>) {
		if (roster == null) return
		val ids = roster.trackers.map { it.sessionTrackerId }
		if (ids.any(String::isBlank) || ids.size != ids.toSet().size) findings += fatal("ROSTER_IDS", "Roster tracker IDs must be non-empty and unique")
		if (ids.toSet() != manifest.trackers.mapTo(linkedSetOf()) { it.sessionTrackerId }) findings += fatal("ROSTER_MANIFEST_MISMATCH", "Telemetry roster and manifest trackers differ")
	}

	private fun validateSemanticCoverage(
		header: DatasetFileHeader?,
		roster: DatasetTrackerRoster?,
		frames: List<SessionFrame>,
		events: List<DatasetEvent>,
		labels: List<DatasetResetLabel>,
		footer: DatasetFooter?,
		findings: MutableList<ValidationFinding>,
	) {
		if (header == null || roster == null) return
		val rosterById = roster.trackers.associateBy { it.sessionTrackerId }
		val samples = frames.flatMap { it.trackers + it.contextSamples }
		val unrostered = samples.map { it.sessionTrackerId } + labels.map { it.sessionTrackerId } + events.mapNotNull { it.sessionTrackerId }
		if (unrostered.any { it !in rosterById }) findings += fatal("UNROSTERED_ID", "Samples, events, and reset labels must reference only rostered session tracker IDs")

		runCatching { TelemetryChannelRegistry.requireSupportedProfile(header.profile, roster.trackers) }
			.onFailure { findings += fatal("PROFILE_CAPABILITIES", it.message ?: "Profile capabilities are not satisfiable") }

		val samplesById = samples.groupBy { it.sessionTrackerId }
		for (sample in samples) {
			val capabilities = rosterById[sample.sessionTrackerId]?.capabilities ?: continue
			val fabricated =
				(sample.orientationValidity == ChannelValidity.VALID && !capabilities.containsAll(setOf(1, 2, 3))) ||
				(sample.accelerationValidity == ChannelValidity.VALID && !capabilities.containsAll(setOf(4, 5))) ||
				(sample.angularVelocityValidity == ChannelValidity.VALID && 6 !in capabilities) ||
				(sample.positionValidity == ChannelValidity.VALID && 36 !in capabilities && 38 !in capabilities) ||
				sample.nativeChannels.any { it.validity == ChannelValidity.VALID && it.provenance in setOf(ChannelProvenance.MEASURED, ChannelProvenance.FIRMWARE_REPORTED) && it.channelId !in capabilities }
			if (fabricated) findings += fatal("FABRICATED_VALID_OPTIONAL", "Tracker ${sample.sessionTrackerId} marks an unadvertised optional channel as valid")
		}
		for (tracker in roster.trackers.filter { it.imuType !in setOf("UNKNOWN", "NONE") }) {
			val observed = samplesById[tracker.sessionTrackerId].orEmpty()
			if (observed.isEmpty()) continue // an empty/instant session has no opportunity to prove producibility
			val produced = buildSet {
				if (observed.any { it.orientationValidity != ChannelValidity.UNAVAILABLE }) addAll(setOf(1, 2, 3, 19, 44, 45))
				if (observed.any { it.accelerationValidity != ChannelValidity.UNAVAILABLE }) addAll(setOf(4, 5))
				if (observed.any { it.angularVelocityValidity != ChannelValidity.UNAVAILABLE }) add(6)
				if (observed.size == 1 && 6 in tracker.capabilities) add(6) // derivative needs a second orientation sample
				observed.flatMap { it.nativeChannels }.filter { it.validity != ChannelValidity.UNAVAILABLE }.forEach { add(it.channelId) }
			}
			val missing = TelemetryChannelRegistry.requiredPerTracker(header.profile) - produced
			if (missing.isNotEmpty()) findings += fatal("REQUIRED_CHANNEL_NEVER_PRODUCED", "Tracker ${tracker.sessionTrackerId} advertised but never produced required channels ${missing.sorted()}")
		}

		val producedContext = buildSet {
			if (frames.any { frame -> frame.contextSamples.any { it.positionValidity != ChannelValidity.UNAVAILABLE } }) add(36)
			if (frames.any { it.skeletonBones.any { bone -> bone.validity != ChannelValidity.UNAVAILABLE } }) add(37)
			if (frames.any { it.floorContext?.validity != null && it.floorContext.validity != ChannelValidity.UNAVAILABLE }) add(38)
			if (frames.any { it.activity.type != ActivityType.UNKNOWN }) add(39)
		}
		val missingContext = TelemetryChannelRegistry.requiredContext(header.profile) - producedContext
		if (missingContext.isNotEmpty()) findings += fatal("REQUIRED_CONTEXT_MISSING", "Required session context was never produced: ${missingContext.sorted()}")

		val resetEvents = events.filter { it.requestId != null || it.resetOutcome != null }
		if (resetEvents.any { it.requestId == null || it.resetOutcome == null }) {
			findings += fatal("RESET_METADATA", "Reset lifecycle events must carry both requestId and resetOutcome")
		}
		val completeResetEvents = resetEvents.filter { it.requestId != null && it.resetOutcome != null }
		if (completeResetEvents.map { it.eventIndex }.any { it <= 0L } || completeResetEvents.map { it.eventIndex }.size != completeResetEvents.map { it.eventIndex }.toSet().size) {
			findings += fatal("RESET_EVENT_INDEX", "Reset lifecycle event indexes must be positive and unique")
		}
		val frameIndexes = frames.mapTo(hashSetOf()) { it.frameIndex }
		for ((requestId, lifecycle) in completeResetEvents.groupBy { it.requestId!! }) {
			val requested = lifecycle.count { it.resetOutcome == "REQUESTED" }
			val terminal = lifecycle.filter { it.resetOutcome in setOf("APPLIED", "CANCELLED", "FAILED") }
			if (requested != 1 || terminal.size != 1) findings += fatal("RESET_CONTINUITY", "Reset $requestId must have one REQUESTED and one terminal event")
			val requestLabels = labels.filter { it.requestId == requestId }
			val requestedEvent = lifecycle.singleOrNull { it.resetOutcome == "REQUESTED" }
			val terminalEvent = terminal.singleOrNull()
			if (requestedEvent != null && terminalEvent != null) {
				if (requestedEvent.eventIndex >= terminalEvent.eventIndex || requestedEvent.resetKind != terminalEvent.resetKind || requestedEvent.resetSource != terminalEvent.resetSource || requestedEvent.requestMonotonicNs != terminalEvent.requestMonotonicNs || requestedEvent.affectedBodyParts != terminalEvent.affectedBodyParts) {
					findings += fatal("RESET_RELATIONSHIP", "Reset $requestId request and terminal metadata are inconsistent")
				}
			}
			if (terminalEvent?.resetOutcome == "APPLIED") {
				if (requestLabels.isEmpty() || requestLabels.any { it.eventIndex != terminalEvent.eventIndex }) findings += fatal("RESET_LABEL_CONTINUITY", "Applied reset $requestId must retain labels linked to its terminal event")
			} else if (requestLabels.isNotEmpty()) {
				findings += fatal("RESET_LABEL_CONTINUITY", "Non-applied reset $requestId cannot carry training labels")
			}
			if (requestLabels.groupBy { it.sessionTrackerId }.any { it.value.size != 1 }) findings += fatal("RESET_LABEL_DUPLICATE", "Reset $requestId has duplicate labels for one tracker")
			for (label in requestLabels) {
				if (requestedEvent == null || terminalEvent == null || label.requestMonotonicNs != requestedEvent.requestMonotonicNs || label.appliedMonotonicNs != terminalEvent.appliedMonotonicNs) {
					findings += fatal("RESET_LABEL_TIMING", "Reset label $requestId is not linked to canonical lifecycle timestamps")
				}
				val expectedAxis = when (label.domain) { "YAW" -> 1; "FULL" -> 7; "MOUNTING" -> 8; else -> -1 }
				if (label.domain != terminalEvent?.resetKind || label.axisMask.toInt() != expectedAxis) findings += fatal("RESET_LABEL_DOMAIN", "Reset label $requestId has an incompatible domain or axis mask")
				val epochValid = label.resetEpoch > label.resetEpochBefore && label.calibrationEpoch >= label.calibrationEpochBefore && when (label.domain) {
					"YAW" -> label.calibrationEpoch == label.calibrationEpochBefore
					"FULL", "MOUNTING" -> label.calibrationEpoch > label.calibrationEpochBefore
					else -> false
				}
				if (!epochValid) findings += fatal("RESET_LABEL_EPOCH", "Reset label $requestId has regressing or incompatible epochs")
				val ordered = label.preStartFrame >= 0L && label.preStartFrame <= label.preEndFrame && label.preEndFrame <= label.postStartFrame && label.postStartFrame <= label.postEndFrame
				if (!ordered) {
					findings += fatal("RESET_LABEL_WINDOW", "Reset label $requestId has an unordered context window")
				} else if ((label.qualityFlags and FLAG_WINDOW_TRUNCATED) == 0) {
					val unresolved = (label.preStartFrame until label.preEndFrame).any { it !in frameIndexes } || (label.postStartFrame until label.postEndFrame).any { it !in frameIndexes }
					if (unresolved) findings += fatal("RESET_LABEL_WINDOW", "Reset label $requestId references unresolved frames without truncation")
				}
			}
		}
		if (labels.any { label -> completeResetEvents.none { it.eventIndex == label.eventIndex && it.requestId == label.requestId && it.resetOutcome == "APPLIED" } }) {
			findings += fatal("ORPHAN_RESET_LABEL", "Every reset label must reference a retained APPLIED lifecycle event")
		}
		val gaps = events.count { it.type == "GAP" }.toLong()
		if (footer != null && footer.counters.gapEvents != gaps) findings += fatal("GAP_CONTINUITY", "Footer gap count ${footer.counters.gapEvents} must equal $gaps retained GAP events")
	}

	private fun validateFooter(footer: DatasetFooter?, checksum: String, manifest: DatasetManifest, frames: Long, findings: MutableList<ValidationFinding>) {
		if (footer == null) return
		if (!footer.complete) findings += fatal("FOOTER_INCOMPLETE", "Terminal footer must be complete")
		if (footer.durationNs < 0L || footer.endedMonotonicNs < footer.durationNs) findings += fatal("FOOTER_DURATION", "Footer duration or end timestamp is invalid")
		if (footer.durationNs != manifest.durationNs) findings += fatal("FOOTER_DURATION_MISMATCH", "Footer and manifest durations differ")
		if (!SHA256.matches(footer.telemetrySha256) || footer.telemetrySha256 != checksum) findings += fatal("FOOTER_CHECKSUM", "Footer checksum does not match exact pre-footer records")
		if (footer.counters != manifest.quality) findings += fatal("FOOTER_COUNTERS", "Footer and manifest quality counters differ")
		if (footer.counters.writtenFrames != frames) findings += fatal("FRAME_COUNT", "Footer/manifest written frame count differs from decoded frames")
		val counters = footer.counters
		if (
			counters.sampledFrames < 0 ||
			counters.writtenFrames < 0 ||
			counters.droppedFrames < 0 ||
			counters.gapEvents < 0 ||
			counters.invalidSamples < 0 ||
			counters.queueHighWatermark < 0 ||
			counters.packetGaps < 0 ||
			counters.packetReordered < 0 ||
			counters.packetDuplicates < 0
		) {
			findings += fatal("QUALITY_COUNTERS", "Footer quality counters must be non-negative")
		}
	}

	private fun validateSamples(frames: List<SessionFrame>, header: DatasetFileHeader?, findings: MutableList<ValidationFinding>) {
		val descriptors = header?.channels?.associateBy { it.id }.orEmpty()
		frames.asSequence().flatMap { (it.trackers + it.contextSamples).asSequence() }.flatMap { it.nativeChannels.asSequence() }.forEach { sample ->
			val descriptor = descriptors[sample.channelId]
			if (descriptor == null) {
				findings += fatal("CHANNEL_UNDECLARED", "Sample uses undeclared channel ${sample.channelId}")
			} else if (sample.provenance !in descriptor.allowedProvenance) {
				findings += fatal("CHANNEL_PROVENANCE", "Channel ${sample.channelId} uses forbidden provenance ${sample.provenance}")
			}
			val carriesValue = sample.values.isNotEmpty() || sample.integerValue != null || sample.textValue != null
			if (sample.validity == ChannelValidity.UNAVAILABLE && carriesValue) findings += fatal("FABRICATED_UNAVAILABLE_VALUE", "Unavailable channel ${sample.channelId} carries a value")
			if (sample.validity == ChannelValidity.VALID && sample.provenance == ChannelProvenance.UNAVAILABLE) findings += fatal("FABRICATED_VALID_VALUE", "Valid channel ${sample.channelId} has unavailable provenance")
			if (sample.validity == ChannelValidity.VALID && sample.values.any { !it.isFinite() }) findings += fatal("NON_FINITE_VALID_VALUE", "Valid channel ${sample.channelId} carries a non-finite value")
		}
	}

	private fun prototype(path: Path, code: String, missing: List<String>) = DatasetValidationReport(path.toString(), null, null, 0, 0, listOf(ValidationFinding(code, FindingSeverity.FATAL, "Prototype archive is unsupported and is not training-ready", missing)))
	private fun fatal(code: String, message: String) = ValidationFinding(code, FindingSeverity.FATAL, message)

	companion object {
		private const val MANIFEST_ENTRY = "manifest.json"
		private const val TELEMETRY_ENTRY = "telemetry.fbs.zst"
		private const val MAX_MANIFEST_BYTES = 4L * 1024 * 1024
		private const val MAX_COMPRESSED_TELEMETRY_BYTES = 256L * 1024 * 1024
		private val CANONICAL_ENTRIES = setOf(MANIFEST_ENTRY, TELEMETRY_ENTRY)
		private val DRIVE_PATH = Regex("^[A-Za-z]:.*")
		private val SHA256 = Regex("^[0-9a-f]{64}$")

		fun readRecord(input: InputStream): ByteArray? {
			val first = input.read()
			if (first < 0) return null
			val b1 = input.read()
			val b2 = input.read()
			val b3 = input.read()
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
