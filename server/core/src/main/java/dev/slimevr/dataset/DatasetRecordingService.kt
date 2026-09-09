package dev.slimevr.dataset

import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdOutputStream
import dev.slimevr.dataset.generated.DatasetV1Bindings
import dev.slimevr.dataset.generated.DatasetV1Reader
import dev.slimevr.reset.FLAG_EXCESS_MOTION
import dev.slimevr.reset.FLAG_INSUFFICIENT_CONTEXT
import dev.slimevr.reset.FLAG_INVALID_OR_STALE_HMD
import dev.slimevr.reset.FLAG_OVERLAPPING_RESETS
import dev.slimevr.reset.FLAG_PACKET_GAPS
import dev.slimevr.reset.FLAG_RECONNECT_OR_REASSIGNMENT
import dev.slimevr.reset.FLAG_WINDOW_TRUNCATED
import dev.slimevr.reset.ResetEvent
import dev.slimevr.reset.ResetEventListener
import dev.slimevr.reset.ResetEventPublisher
import dev.slimevr.reset.ResetOutcome
import dev.slimevr.reset.ResetKind
import dev.slimevr.reset.ResetSupervisionPolicy
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerUtils
import io.eiren.util.logging.LogManager
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.max

enum class RecordingState { IDLE, STARTING, RECORDING, FINALIZING, COMPLETED, FAILED, RECOVERABLE, QUARANTINED, CANCELLED }

data class RecordingRequest(
	val profile: CollectionProfile = CollectionProfile.MINIMUM,
	val privacy: SessionPrivacyOptions,
	val applicationVersion: String = "development",
	val applicationCommit: String = "unknown",
	val minFreeSpaceBytes: Long = 512L * 1024L * 1024L,
)

data class RecordingStatus(
	val state: RecordingState,
	val sessionId: String? = null,
	val sampledFrames: Long = 0,
	val writtenFrames: Long = 0,
	val droppedFrames: Long = 0,
	val queuedItems: Int = 0,
	val queueHighWatermark: Int = 0,
	val bytesWritten: Long = 0,
	val output: Path? = null,
	val lastError: String? = null,
	val resetCount: Long = 0,
	val elapsedNs: Long = 0,
	val finalizationProgress: Float = 0f,
	val statusVersion: Long = 0,
	val roster: List<SessionTrackerMetadata> = emptyList(),
	val validationReport: DatasetValidationReport? = null,
)

fun interface RecordingStatusListener {
	fun onStatusChanged(status: RecordingStatus)
}

data class RecoverableSession(val sessionId: String, val directory: Path, val manifest: DatasetManifest?, val reason: String)

private sealed interface RecorderItem {
	data class Sample(
		val frame: SessionFrame,
		val events: List<DatasetEvent>,
		val resetLabels: List<DatasetResetLabel> = emptyList(),
	) : RecorderItem
	data class EventFlush(
		val events: List<DatasetEvent>,
		val resetLabels: List<DatasetResetLabel>,
	) : RecorderItem
	data object Finish : RecorderItem
}

/** Fixed-memory recorder: sampling is server-thread-only; encoding and I/O use one writer thread. */
class DatasetRecordingService(
	val datasetsRoot: Path = System.getenv("NEKOVR_DATASETS_DIR")?.takeIf(String::isNotBlank)?.let(Path::of) ?: Path.of("datasets"),
	private val clockNs: () -> Long = System::nanoTime,
	private val freeSpace: (Path) -> Long = { Files.getFileStore(it).usableSpace },
	queueCapacity: Int = 256,
	private val batchSize: Int = 25,
	private val durableEveryBatches: Int = 4,
	private val writerDelayMillis: Long = 0,
	private val resetPreContextFrames: Int = 50,
	private val resetPostContextFrames: Int = 50,
	private val maxReferenceAgeNs: Long = 250_000_000L,
) : AutoCloseable {
	private val queue = ArrayBlockingQueue<RecorderItem>(queueCapacity)
	private val directBuffers = ArrayBlockingQueue<ByteBuffer>(2).apply {
		repeat(2) { add(ByteBuffer.allocateDirect(1024 * 1024).order(ByteOrder.LITTLE_ENDIAN)) }
	}
	private val currentState = AtomicReference(RecordingState.IDLE)
	private val statusVersion = AtomicLong()
	private val finalizationProgress = AtomicReference(0f)
	private val statusListeners = CopyOnWriteArrayList<RecordingStatusListener>()
	private val sampled = AtomicLong()
	private val written = AtomicLong()
	private val dropped = AtomicLong()
	private val invalid = AtomicLong()
	private val gapEvents = AtomicLong()
	private val highWatermark = AtomicLong()
	private val pendingQueueDrops = AtomicLong()
	private val resetCount = AtomicLong()
	private val pendingResetEvents = ConcurrentLinkedQueue<DatasetEvent>()
	private val pendingResetLabels = ConcurrentLinkedQueue<DatasetResetLabel>()
	private val activeContextLabels = CopyOnWriteArrayList<DatasetResetLabel>()
	private val nextEventIndex = AtomicLong(1L)
	private var lastAppliedResetFrame: Long = -1L
	private val contextRetentionFrames = (resetPreContextFrames * 2 + resetPostContextFrames + 2).coerceAtLeast(4)
	private val frameRingBuffer = ArrayDeque<SessionFrame>(contextRetentionFrames)
	private val contextEventRingBuffer = ArrayDeque<DatasetEvent>(contextRetentionFrames)
	private var boundResetPublisher: ResetEventPublisher? = null
	private val resetListener = ResetEventListener { event -> onResetEvent(event) }
	private var sessionId: String? = null
	private var sessionStartNs = 0L
	private var lastSampleNs = 0L
	private var nextSampleNs = 0L
	private var frameIndex = 0L
	private var registry: SessionTrackerRegistry? = null
	private var snapshotFactory: SessionSnapshotFactory? = null
	private var manifest: DatasetManifest? = null
	private var workingDirectory: Path? = null
	private var outputArchive: Path? = null
	private var writerThread: Thread? = null
	private var completion = CountDownLatch(0)
	private var lastError: String? = null
	private var validationReport: DatasetValidationReport? = null

	val isRecording: Boolean get() = currentState.get() == RecordingState.RECORDING

	fun addStatusListener(listener: RecordingStatusListener) {
		statusListeners.add(listener)
	}

	fun removeStatusListener(listener: RecordingStatusListener) {
		statusListeners.remove(listener)
	}

	private fun transitionTo(state: RecordingState) {
		currentState.set(state)
		statusVersion.incrementAndGet()
		val snapshot = status()
		statusListeners.forEach { listener ->
			runCatching { listener.onStatusChanged(snapshot) }
				.onFailure { LogManager.warning("[Dataset] Status listener failed: ${it.message}") }
		}
	}

	fun bindResetPublisher(publisher: ResetEventPublisher) {
		boundResetPublisher?.removeListener(resetListener)
		boundResetPublisher = publisher
		publisher.addListener(resetListener)
	}

	fun unbindResetPublisher() {
		boundResetPublisher?.removeListener(resetListener)
		boundResetPublisher = null
	}

	private fun onResetEvent(event: ResetEvent) {
		if (currentState.get() != RecordingState.RECORDING) return
		val now = clockNs()
		val relNs = (now - sessionStartNs).coerceAtLeast(0)
		val requestRelNs = (event.requestMonotonicNs - sessionStartNs).coerceAtLeast(0)
		val appliedRelNs = event.appliedMonotonicNs?.let { (it - sessionStartNs).coerceAtLeast(0) } ?: 0L
		val currentF = frameIndex
		val eIndex = nextEventIndex.getAndIncrement()

		val eventType = "RESET_${event.kind.name}_${event.outcome.name}"
		val partsStr = event.bodyParts.joinToString(",")
		val detailStr = "source=${event.source},parts=$partsStr${if (event.failureReason != null) ",error=${event.failureReason}" else ""}"
		pendingResetEvents.add(
			DatasetEvent(
				type = eventType,
				monotonicNs = if (event.appliedMonotonicNs != null) appliedRelNs else requestRelNs,
				frameIndex = currentF,
				sessionTrackerId = null,
				detail = detailStr,
				requestId = event.requestId,
				requestMonotonicNs = requestRelNs,
				appliedMonotonicNs = appliedRelNs,
				eventIndex = eIndex,
				resetOutcome = event.outcome.name,
				resetSource = event.source,
				resetKind = event.kind.name,
				affectedBodyParts = event.bodyParts.map { it.toString() },
			),
		)

		if (event.outcome == ResetOutcome.APPLIED && event.labels.isNotEmpty()) {
			resetCount.incrementAndGet()
			val preStart = (currentF - resetPreContextFrames).coerceAtLeast(0)
			val preEnd = currentF
			val postStart = currentF
			val postEnd = currentF + resetPostContextFrames
			var extraFlags = 0
			if (currentF < resetPreContextFrames) {
				extraFlags = extraFlags or FLAG_WINDOW_TRUNCATED or FLAG_INSUFFICIENT_CONTEXT
			}
			if (lastAppliedResetFrame >= 0 && (currentF - lastAppliedResetFrame) < resetPreContextFrames + resetPostContextFrames) {
				extraFlags = extraFlags or FLAG_OVERLAPPING_RESETS
				for (index in activeContextLabels.indices) {
					activeContextLabels[index] = activeContextLabels[index].copy(
						qualityFlags = activeContextLabels[index].qualityFlags or FLAG_OVERLAPPING_RESETS,
					)
				}
			}
			lastAppliedResetFrame = currentF

			for (record in event.labels) {
				val sessionTrackerId = record.sessionTrackerId ?: registry?.idForTrackerId(record.trackerId) ?: "t${record.trackerId}"
				val label = DatasetResetLabel(
					eventIndex = eIndex,
					sessionTrackerId = sessionTrackerId,
					correction = QuaternionSample(record.correction.x, record.correction.y, record.correction.z, record.correction.w),
					diagnosticYawRadians = record.diagnosticYawRadians,
					axisMask = record.axisMask,
					preStartFrame = preStart,
					preEndFrame = preEnd,
					postStartFrame = postStart,
					postEndFrame = postEnd,
					qualityFlags = record.qualityFlags or extraFlags,
					domain = record.domain.name,
					requestId = event.requestId,
					requestMonotonicNs = requestRelNs,
					appliedMonotonicNs = appliedRelNs,
					rawOrientationBefore = QuaternionSample(record.preResetState.rawOrientation.x, record.preResetState.rawOrientation.y, record.preResetState.rawOrientation.z, record.preResetState.rawOrientation.w),
					rawOrientationAfter = QuaternionSample(record.postResetState.rawOrientation.x, record.postResetState.rawOrientation.y, record.postResetState.rawOrientation.z, record.postResetState.rawOrientation.w),
					calibratedPreAiBefore = QuaternionSample(record.preResetState.calibratedPreAiOrientation.x, record.preResetState.calibratedPreAiOrientation.y, record.preResetState.calibratedPreAiOrientation.z, record.preResetState.calibratedPreAiOrientation.w),
					calibratedPreAiAfter = QuaternionSample(record.postResetState.calibratedPreAiOrientation.x, record.postResetState.calibratedPreAiOrientation.y, record.postResetState.calibratedPreAiOrientation.z, record.postResetState.calibratedPreAiOrientation.w),
					attachmentRotBefore = QuaternionSample(record.preResetState.adjustments.attachmentFix.x, record.preResetState.adjustments.attachmentFix.y, record.preResetState.adjustments.attachmentFix.z, record.preResetState.adjustments.attachmentFix.w),
					attachmentRotAfter = QuaternionSample(record.postResetState.adjustments.attachmentFix.x, record.postResetState.adjustments.attachmentFix.y, record.postResetState.adjustments.attachmentFix.z, record.postResetState.adjustments.attachmentFix.w),
					mountingRotBefore = QuaternionSample(record.preResetState.adjustments.mountingOrientation.x, record.preResetState.adjustments.mountingOrientation.y, record.preResetState.adjustments.mountingOrientation.z, record.preResetState.adjustments.mountingOrientation.w),
					mountingRotAfter = QuaternionSample(record.postResetState.adjustments.mountingOrientation.x, record.postResetState.adjustments.mountingOrientation.y, record.postResetState.adjustments.mountingOrientation.z, record.postResetState.adjustments.mountingOrientation.w),
					yawRotBefore = QuaternionSample(record.preResetState.adjustments.yawFix.x, record.preResetState.adjustments.yawFix.y, record.preResetState.adjustments.yawFix.z, record.preResetState.adjustments.yawFix.w),
					yawRotAfter = QuaternionSample(record.postResetState.adjustments.yawFix.x, record.postResetState.adjustments.yawFix.y, record.postResetState.adjustments.yawFix.z, record.postResetState.adjustments.yawFix.w),
					hmdReferenceBefore = QuaternionSample(record.hmdReferenceBefore.x, record.hmdReferenceBefore.y, record.hmdReferenceBefore.z, record.hmdReferenceBefore.w),
					hmdReferenceAfter = QuaternionSample(record.hmdReferenceAfter.x, record.hmdReferenceAfter.y, record.hmdReferenceAfter.z, record.hmdReferenceAfter.w),
					hmdValid = record.hmdValid,
					resetEpoch = record.postResetState.resetEpoch,
					trainingPolicy = record.trainingPolicy.name,
					gyroFixBefore = record.preResetState.adjustments.gyroFix.sample(),
					gyroFixAfter = record.postResetState.adjustments.gyroFix.sample(),
					mountRotFixBefore = record.preResetState.adjustments.mountRotFix.sample(),
					mountRotFixAfter = record.postResetState.adjustments.mountRotFix.sample(),
					tposeDownFixBefore = record.preResetState.adjustments.tposeDownFix.sample(),
					tposeDownFixAfter = record.postResetState.adjustments.tposeDownFix.sample(),
					constraintFixBefore = record.preResetState.adjustments.constraintFix.sample(),
					constraintFixAfter = record.postResetState.adjustments.constraintFix.sample(),
					calibrationEpoch = record.postResetState.calibrationEpoch,
					bodyRole = record.trackerPosition?.designation ?: "UNASSIGNED",
					hmdSampleAgeBeforeNs = record.hmdSampleAgeBeforeNs,
					hmdSampleAgeAfterNs = record.hmdSampleAgeAfterNs,
				)
				activeContextLabels.add(label)
			}
		}
	}

	private fun deriveContextLabel(label: DatasetResetLabel, endFrame: Long, truncated: Boolean): DatasetResetLabel {
		val windowFrames = frameRingBuffer.filter { it.frameIndex >= label.preStartFrame && it.frameIndex < endFrame }
		var flags = label.qualityFlags
		if (truncated) flags = flags or FLAG_WINDOW_TRUNCATED or FLAG_INSUFFICIENT_CONTEXT
		if (windowFrames.size.toLong() < endFrame - label.preStartFrame) flags = flags or FLAG_INSUFFICIENT_CONTEXT
		for (frame in windowFrames) {
			if (frame.hmd.validity != ChannelValidity.VALID || frame.hmd.sampleAgeNs > maxReferenceAgeNs) flags = flags or FLAG_INVALID_OR_STALE_HMD
			val tracker = frame.trackers.firstOrNull { it.sessionTrackerId == label.sessionTrackerId }
			if (tracker == null) {
				flags = flags or FLAG_RECONNECT_OR_REASSIGNMENT or FLAG_INSUFFICIENT_CONTEXT
				continue
			}
			val accel = tracker.linearAcceleration
			val gyro = tracker.angularVelocity
			val accelMagnitude = kotlin.math.sqrt(accel.x * accel.x + accel.y * accel.y + accel.z * accel.z)
			val gyroMagnitude = kotlin.math.sqrt(gyro.x * gyro.x + gyro.y * gyro.y + gyro.z * gyro.z)
			if (accelMagnitude > 2.5f || gyroMagnitude > 1.0f) flags = flags or FLAG_EXCESS_MOTION
			if (tracker.orientationValidity != ChannelValidity.VALID) flags = flags or FLAG_RECONNECT_OR_REASSIGNMENT
			if (tracker.nativeChannels.any { it.channelId == 26 && (it.integerValue ?: 0L) > 0L }) flags = flags or FLAG_PACKET_GAPS
		}
		val events = contextEventRingBuffer.filter { it.frameIndex >= label.preStartFrame && it.frameIndex < endFrame }
		if (events.any { it.type == "GAP" }) flags = flags or FLAG_PACKET_GAPS
		if (events.any { it.sessionTrackerId == label.sessionTrackerId && it.type in setOf("CONNECT", "DISCONNECT", "ASSIGNMENT", "CALIBRATION") }) {
			flags = flags or FLAG_RECONNECT_OR_REASSIGNMENT
		}
		val domain = runCatching { ResetKind.valueOf(label.domain) }.getOrDefault(ResetKind.YAW)
		return label.copy(
			postEndFrame = endFrame,
			qualityFlags = flags,
			trainingPolicy = ResetSupervisionPolicy.evaluate(domain, flags).action.name,
		)
	}

	init {
		datasetsRoot.createDirectories()
		discoverRecoverableSessions()
	}

	@Synchronized
	fun startRecording(request: RecordingRequest, trackers: List<Tracker>): RecordingStatus {
		check(currentState.get() in setOf(RecordingState.IDLE, RecordingState.COMPLETED, RecordingState.FAILED, RecordingState.CANCELLED, RecordingState.QUARANTINED)) {
			"Recorder is ${currentState.get()}"
		}
		sessionId = null
		manifest = null
		outputArchive = null
		lastError = null
		validationReport = null
		transitionTo(RecordingState.STARTING)
		try {
			require(request.privacy.consent) { "Explicit dataset recording consent is required" }
			val hmd = TrackerUtils.getTrackerForSkeleton(trackers, TrackerPosition.HEAD)
			require(hmd != null && hmd.status.sendData) { "A valid HMD/reference tracker is required" }
			val assignedImus = trackers.filter { it.isImu() && it.trackerPosition != null }
			require(assignedImus.isNotEmpty()) { "At least one assigned physical IMU tracker is required" }
			require(freeSpace(datasetsRoot) >= request.minFreeSpaceBytes) { "Insufficient free space for recording" }
			resetCounters()
			val newSessionId = UUID.randomUUID().toString()
			val start = clockNs()
			val trackerRegistry = SessionTrackerRegistry(trackers, request.privacy)
			validateProfile(request.profile, trackerRegistry.initialRoster)
			val work = datasetsRoot.resolve("$newSessionId.partial")
			work.createDirectories()
			val initialManifest = DatasetManifest(
				sessionId = newSessionId,
				createdUtc = Instant.now().toString(),
				monotonicStartNs = start,
				profile = request.profile,
				applicationVersion = request.applicationVersion,
				applicationCommit = request.applicationCommit,
				privacy = trackerRegistry.datasetPrivacy,
				trackers = trackerRegistry.initialRoster,
			)
			writeManifestDurably(work, initialManifest)
			sessionId = newSessionId
			sessionStartNs = start
			lastSampleNs = start
			nextSampleNs = start
			frameIndex = 0
			registry = trackerRegistry
			snapshotFactory = SessionSnapshotFactory(trackerRegistry)
			manifest = initialManifest
			workingDirectory = work
			outputArchive = null
			lastError = null
			finalizationProgress.set(0f)
			completion = CountDownLatch(1)
			transitionTo(RecordingState.RECORDING)
			writerThread = Thread({ writerLoop(work, initialManifest, request.minFreeSpaceBytes) }, "dataset-writer-$newSessionId").apply {
				isDaemon = true
				start()
			}
			LogManager.info("[Dataset] Recording session $newSessionId at 50 Hz")
			return status()
		} catch (error: Throwable) {
			lastError = error.message
			transitionTo(RecordingState.FAILED)
			throw error
		}
	}

	/** Called from the server tracking thread and never blocks. */
	fun sampleIfDue(trackers: List<Tracker>) {
		if (!isRecording) return
		val now = clockNs()
		if (now < nextSampleNs) return
		val missedIntervals = ((now - nextSampleNs) / CANONICAL_SAMPLE_INTERVAL_NS).coerceAtLeast(0)
		if (missedIntervals > 0) {
			dropped.addAndGet(missedIntervals)
			pendingQueueDrops.addAndGet(missedIntervals)
			frameIndex += missedIntervals
		}
		val delta = if (sampled.get() == 0L) 0 else (now - lastSampleNs).coerceAtLeast(0)
		val factory = snapshotFactory ?: return
		val (frame, topologyEvents) = factory.snapshot(trackers.toList(), frameIndex, now - sessionStartNs, delta)
		if (frameRingBuffer.size >= contextRetentionFrames) {
			frameRingBuffer.removeFirst()
		}
		frameRingBuffer.addLast(frame)
		val lost = pendingQueueDrops.getAndSet(0)
		val baseEvents = if (lost > 0) topologyEvents + DatasetEvent(
			"GAP", now - sessionStartNs, frameIndex, detail = "$lost canonical frames were not queued",
		) else topologyEvents

		val drainedEvents = mutableListOf<DatasetEvent>()
		while (true) {
			val ev = pendingResetEvents.poll() ?: break
			drainedEvents.add(ev)
		}
		val allEvents = if (drainedEvents.isNotEmpty()) baseEvents + drainedEvents else baseEvents
		for (event in allEvents) contextEventRingBuffer.addLast(event)
		while (contextEventRingBuffer.firstOrNull()?.frameIndex?.let { it < frameIndex - contextRetentionFrames } == true) {
			contextEventRingBuffer.removeFirst()
		}

		val readyLabels = mutableListOf<DatasetResetLabel>()
		val iterator = activeContextLabels.iterator()
		while (iterator.hasNext()) {
			val label = iterator.next()
			if (frameIndex + 1 >= label.postEndFrame + resetPreContextFrames) {
				readyLabels.add(deriveContextLabel(label, label.postEndFrame, false))
				activeContextLabels.remove(label)
			}
		}
		while (true) {
			val pl = pendingResetLabels.poll() ?: break
			readyLabels.add(pl)
		}

		if (!queue.offer(RecorderItem.Sample(frame, allEvents, readyLabels))) {
			dropped.incrementAndGet()
			pendingQueueDrops.incrementAndGet()
		} else {
			sampled.incrementAndGet()
			if (lost > 0) gapEvents.incrementAndGet()
			updateHighWatermark(queue.size)
		}
		lastSampleNs = now
		frameIndex++
		nextSampleNs = max(nextSampleNs + (missedIntervals + 1) * CANONICAL_SAMPLE_INTERVAL_NS, now + 1)
	}

	@Synchronized
	fun beginFinalization() {
		if (currentState.get() == RecordingState.FINALIZING) return
		check(currentState.get() == RecordingState.RECORDING) { "Recorder is not recording" }
		finalizationProgress.set(0.05f)
		transitionTo(RecordingState.FINALIZING)
		val finalF = frameIndex
		val truncatedLabels = mutableListOf<DatasetResetLabel>()
		for (active in activeContextLabels) {
			val endFrame = minOf(active.postEndFrame, finalF)
			truncatedLabels.add(deriveContextLabel(active, endFrame, finalF < active.postEndFrame))
		}
		activeContextLabels.clear()
		while (true) {
			val pl = pendingResetLabels.poll() ?: break
			truncatedLabels.add(pl)
		}
		val remainingEvents = mutableListOf<DatasetEvent>()
		while (true) {
			val ev = pendingResetEvents.poll() ?: break
			remainingEvents.add(ev)
		}
		if (truncatedLabels.isNotEmpty() || remainingEvents.isNotEmpty()) {
			queue.put(RecorderItem.EventFlush(remainingEvents, truncatedLabels))
		}
		queue.put(RecorderItem.Finish)
	}

	fun stopAndFinalize(timeoutSeconds: Long = 30): Path {
		beginFinalization()
		check(completion.await(timeoutSeconds, TimeUnit.SECONDS)) { "Timed out finalizing dataset" }
		if (currentState.get() != RecordingState.COMPLETED) error(lastError ?: "Dataset finalization failed")
		return outputArchive ?: error("Dataset archive was not created")
	}

	fun status(): RecordingStatus = RecordingStatus(
		currentState.get(), sessionId, sampled.get(), written.get(), dropped.get(), queue.size, highWatermark.get().toInt(),
		runCatching { workingDirectory?.resolve(TELEMETRY_PARTIAL)?.takeIf(Path::exists)?.fileSize() ?: 0 }.getOrDefault(0),
		outputArchive, lastError, resetCount.get(),
		if (sessionStartNs <= 0L) 0L else if (currentState.get() in setOf(RecordingState.STARTING, RecordingState.RECORDING, RecordingState.FINALIZING)) {
			(clockNs() - sessionStartNs).coerceAtLeast(0L)
		} else manifest?.durationNs ?: 0L,
		finalizationProgress.get(), statusVersion.get(), manifest?.trackers ?: emptyList(), validationReport,
	)

	fun discoverRecoverableSessions(): List<RecoverableSession> {
		if (!datasetsRoot.exists()) return emptyList()
		return Files.list(datasetsRoot).use { paths ->
			paths.filter { it.isDirectory() && !Files.isSymbolicLink(it) && it.name.endsWith(".partial") }.map { directory ->
				val manifestPath = directory.resolve(MANIFEST_PARTIAL)
				val telemetryPath = directory.resolve(TELEMETRY_PARTIAL)
				val parsed = runCatching { DatasetManifest.fromJsonString(manifestPath.readText()) }.getOrNull()
				val reason = when {
					parsed == null -> "manifest.partial.json is missing or invalid"
					!telemetryPath.exists() -> "telemetry.fbs.zst.partial is missing"
					telemetryPath.fileSize() == 0L -> "telemetry stream is empty"
					else -> "interrupted recording has durable telemetry"
				}
				RecoverableSession(directory.name.removeSuffix(".partial"), directory, parsed, reason)
			}.toList()
		}
	}

	fun recoverabilityFindings(session: RecoverableSession): List<ValidationFinding> {
		val findings = mutableListOf<ValidationFinding>()
		val durableManifest = session.manifest
		if (durableManifest == null) findings += ValidationFinding("RECOVERY_MANIFEST_INVALID", FindingSeverity.FATAL, "A valid durable manifest is required")
		else if (!durableManifest.privacy.consent) findings += ValidationFinding("CONSENT_MISSING", FindingSeverity.FATAL, "The durable manifest has no recording consent")
		else if (durableManifest.sessionId != session.sessionId) findings += ValidationFinding("SESSION_MISMATCH", FindingSeverity.FATAL, "Directory and manifest session IDs differ")
		val telemetry = session.directory.resolve(TELEMETRY_PARTIAL)
		if (!telemetry.exists() || runCatching { telemetry.fileSize() }.getOrDefault(0) == 0L) {
			findings += ValidationFinding("RECOVERY_TELEMETRY_MISSING", FindingSeverity.FATAL, "Durable telemetry is missing or empty")
			return findings
		}
		if (durableManifest == null) return findings
		runCatching {
			var expected = 0L
			var header = false
			var roster = false
			ZstdInputStream(BufferedInputStream(Files.newInputStream(telemetry))).use { input ->
				while (true) {
					val bytes = runCatching { DatasetArchiveValidator.readRecord(input) }.getOrNull() ?: break
					val record = runCatching { DatasetV1Reader.read(bytes) }.getOrNull() ?: break
					if (record.sequence != expected++) break
					if (record.type == DatasetV1Bindings.RECORD_HEADER) {
						header = record.sessionId == durableManifest.sessionId
					}
					if (record.type == DatasetV1Bindings.RECORD_ROSTER) roster = true
				}
			}
			if (!header) findings += ValidationFinding("RECOVERY_HEADER_INVALID", FindingSeverity.FATAL, "A matching durable header is required")
			if (!roster) findings += ValidationFinding("RECOVERY_ROSTER_MISSING", FindingSeverity.FATAL, "A durable tracker roster is required")
		}.onFailure { findings += ValidationFinding("RECOVERY_READ_FAILED", FindingSeverity.FATAL, it.message ?: "Telemetry cannot be read") }
		if (findings.none { it.severity == FindingSeverity.FATAL }) findings += ValidationFinding("INTERRUPTED_SESSION", FindingSeverity.WARNING, session.reason)
		return findings
	}

	fun quarantinePartial(session: RecoverableSession): Path {
		requireManagedPartial(session)
		val target = datasetsRoot.resolve("${session.sessionId}.${System.currentTimeMillis()}.quarantine")
		try {
			Files.move(session.directory, target, StandardCopyOption.ATOMIC_MOVE)
		} catch (_: Exception) {
			Files.move(session.directory, target)
		}
		sessionId = session.sessionId
		manifest = session.manifest
		outputArchive = null
		transitionTo(RecordingState.QUARANTINED)
		return target
	}

	@Synchronized
	fun cancelRecording() {
		val state = currentState.get()
		if (state != RecordingState.RECORDING && state != RecordingState.STARTING && state != RecordingState.FINALIZING) {
			return
		}
		transitionTo(RecordingState.CANCELLED)
		queue.clear()
		writerThread?.interrupt()
		try {
			writerThread?.join(2000)
		} catch (_: InterruptedException) {
			Thread.currentThread().interrupt()
		}
		writerThread = null
		workingDirectory?.let { work ->
			runCatching {
				if (work.exists()) {
					Files.walk(work)
						.sorted(Comparator.reverseOrder())
						.forEach { Files.deleteIfExists(it) }
				}
			}
		}
		outputArchive = null
		lastError = null
		resetCounters()
		LogManager.info("[Dataset] Cancelled recording session $sessionId")
	}

	fun recoverPartial(session: RecoverableSession): Path {
		requireManagedPartial(session)
		val telemetryPartial = session.directory.resolve(TELEMETRY_PARTIAL)
		require(telemetryPartial.exists()) { "telemetry.fbs.zst.partial does not exist" }

		val manifestPartial = session.directory.resolve(MANIFEST_PARTIAL)
		val parsedManifest = if (manifestPartial.exists()) {
			runCatching { DatasetManifest.fromJsonString(manifestPartial.readText()) }.getOrNull()
		} else null
		require(parsedManifest != null) { "A valid durable manifest is required for recovery" }
		require(parsedManifest.privacy.consent) { "The durable manifest has no recording consent" }

		val validRecords = mutableListOf<ByteArray>()
		var totalFrames = 0L
		var sawHeader = false
		var sawRoster = false
		var expectedSequence = 0L
		var durationNs = 0L

		ZstdInputStream(BufferedInputStream(Files.newInputStream(telemetryPartial))).use { input ->
			recordLoop@ while (true) {
				val recordBytes = runCatching { DatasetArchiveValidator.readRecord(input) }.getOrNull() ?: break
				val record = runCatching { DatasetV1Reader.read(recordBytes) }.getOrNull() ?: break
				if (record.sequence != expectedSequence) break
				expectedSequence++
				when (record.type) {
					DatasetV1Bindings.RECORD_HEADER -> {
						sawHeader = true
						require(record.sessionId == parsedManifest.sessionId) { "Header and manifest session IDs differ" }
					}
					DatasetV1Bindings.RECORD_ROSTER -> sawRoster = true
					DatasetV1Bindings.RECORD_FRAMES -> {
						totalFrames += record.frames.size
						durationNs = maxOf(durationNs, record.frames.lastOrNull()?.monotonicNs ?: 0L)
					}
					DatasetV1Bindings.RECORD_FOOTER -> {
						expectedSequence--
						break@recordLoop
					}
				}
				validRecords.add(recordBytes)
			}
		}
		require(sawHeader) { "A durable telemetry header is required for recovery" }
		require(sawRoster) { "A durable tracker roster is required for recovery" }

		val finalManifest = parsedManifest.copy(
			endedUtc = Instant.now().toString(),
			durationNs = durationNs,
			quality = parsedManifest.quality.copy(
				writtenFrames = totalFrames,
				sampledFrames = maxOf(parsedManifest.quality.sampledFrames, totalFrames),
			),
			state = ArchiveState.RECOVERED,
			recovered = true,
		)

		val recoveredTelemetry = session.directory.resolve("telemetry.recovered.zst")
		val footerChecksum = DatasetTelemetryChecksum()

		FileChannel.open(recoveredTelemetry, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { channel ->
			val bufferedOutput = BufferedOutputStream(Channels.newOutputStream(channel), 64 * 1024)
			ZstdOutputStream(bufferedOutput, 3).use { zstd ->
				for (rec in validRecords) {
					writeRecord(zstd, rec, footerChecksum)
				}
				val preFooterChecksum = footerChecksum.digestHex()
				writeRecord(zstd, DatasetV1Bindings.footer(finalManifest.durationNs, finalManifest.durationNs, finalManifest.quality, preFooterChecksum, true, expectedSequence))
				zstd.flush()
				channel.force(true)
			}
		}

		val finalTelemetrySha = sha256(recoveredTelemetry)
		val finalManifestUpdated = finalManifest.copy(
			telemetrySha256 = finalTelemetrySha,
			telemetryBytes = recoveredTelemetry.fileSize(),
		)

		val archivePath = finalizeArchive(session.directory, finalManifestUpdated, recoveredTelemetry)
		validationReport = DatasetArchiveValidator().validate(archivePath)
		require(validationReport?.valid == true) { "Recovered archive failed canonical validation" }
		sessionId = finalManifestUpdated.sessionId
		sessionStartNs = 0L
		outputArchive = archivePath
		manifest = finalManifestUpdated
		finalizationProgress.set(1f)
		transitionTo(RecordingState.COMPLETED)
		LogManager.info("[Dataset] Recovered $totalFrames frames to $archivePath")
		return archivePath
	}

	private fun requireManagedPartial(session: RecoverableSession) {
		val root = datasetsRoot.apply { createDirectories() }.toRealPath()
		require(!Files.isSymbolicLink(session.directory)) { "Symbolic-link sessions are not allowed" }
		val directory = session.directory.toRealPath()
		require(directory.parent == root && directory.fileName.toString() == "${session.sessionId}.partial") {
			"Session is outside the datasets root"
		}
	}

	private fun writerLoop(work: Path, initialManifest: DatasetManifest, minFreeSpaceBytes: Long) {
		val telemetry = work.resolve(TELEMETRY_PARTIAL)
		val footerChecksum = DatasetTelemetryChecksum()
		var sequence = 0L
		var batches = 0
		val frames = ArrayList<SessionFrame>(batchSize)
		val events = ArrayList<DatasetEvent>()
		val resetLabels = ArrayList<DatasetResetLabel>()
		try {
			FileChannel.open(telemetry, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
				val bufferedOutput = BufferedOutputStream(Channels.newOutputStream(channel), 64 * 1024)
				ZstdOutputStream(bufferedOutput, 3).use { zstd ->
					writeRecord(zstd, DatasetV1Bindings.header(initialManifest.sessionId, initialManifest.createdUtc, initialManifest.applicationVersion, initialManifest.applicationCommit, initialManifest.profile), footerChecksum)
					writeRecord(zstd, DatasetV1Bindings.roster(initialManifest.trackers, sequence = ++sequence), footerChecksum)
					var finishing = false
					while (!finishing) {
						when (val item = queue.take()) {
							is RecorderItem.Sample -> {
								frames += item.frame
								events += item.events
								resetLabels += item.resetLabels
								if (frames.size >= batchSize) {
									check(freeSpace(datasetsRoot) >= minFreeSpaceBytes) { "Disk free-space threshold reached" }
									writeRecord(zstd, DatasetV1Bindings.frames(frames, ++sequence), footerChecksum)
									if (events.isNotEmpty() || resetLabels.isNotEmpty()) {
										writeRecord(zstd, DatasetV1Bindings.events(events, resetLabels, ++sequence), footerChecksum)
										events.clear()
										resetLabels.clear()
									}
									written.addAndGet(frames.size.toLong())
									frames.clear()
									batches++
									if (writerDelayMillis > 0) Thread.sleep(writerDelayMillis)
									if (batches % durableEveryBatches == 0) {
										zstd.flush()
										channel.force(false)
										persistProgress(work, initialManifest)
									}
								}
							}
							is RecorderItem.EventFlush -> {
								events += item.events
								resetLabels += item.resetLabels
								if (events.isNotEmpty() || resetLabels.isNotEmpty()) {
									writeRecord(zstd, DatasetV1Bindings.events(events, resetLabels, ++sequence), footerChecksum)
									events.clear()
									resetLabels.clear()
								}
							}
							RecorderItem.Finish -> finishing = true
						}
					}
					if (frames.isNotEmpty()) {
					writeRecord(zstd, DatasetV1Bindings.frames(frames, ++sequence), footerChecksum)
						written.addAndGet(frames.size.toLong())
					}
					if (events.isNotEmpty() || resetLabels.isNotEmpty()) {
						writeRecord(zstd, DatasetV1Bindings.events(events, resetLabels, ++sequence), footerChecksum)
						events.clear()
						resetLabels.clear()
					}
					val preFooterChecksum = footerChecksum.digestHex()
					val duration = (clockNs() - sessionStartNs).coerceAtLeast(0)
					writeRecord(zstd, DatasetV1Bindings.footer(duration, duration, counters(), preFooterChecksum, true, ++sequence))
					zstd.flush()
					channel.force(true)
				}
			}
			val telemetryChecksum = sha256(telemetry)
			val finalManifest = initialManifest.copy(
				endedUtc = Instant.now().toString(),
				durationNs = (clockNs() - sessionStartNs).coerceAtLeast(0),
				quality = counters(),
				telemetrySha256 = telemetryChecksum,
				telemetryBytes = telemetry.fileSize(),
				state = ArchiveState.COMPLETE,
			)
			finalizationProgress.set(0.75f)
			writeManifestDurably(work, finalManifest)
			val archive = finalizeArchive(work, finalManifest, telemetry)
			validationReport = DatasetArchiveValidator().validate(archive)
			require(validationReport?.valid == true) { "Final archive failed canonical validation" }
			finalizationProgress.set(1f)
			manifest = finalManifest
			outputArchive = archive
			transitionTo(RecordingState.COMPLETED)
			LogManager.info("[Dataset] Finalized ${finalManifest.quality.writtenFrames} frames to $archive")
		} catch (error: Throwable) {
			lastError = error.message ?: error.javaClass.simpleName
			if (currentState.get() != RecordingState.CANCELLED) {
				transitionTo(RecordingState.FAILED)
				LogManager.severe("[Dataset] Recording failed: $lastError")
			}
		} finally {
			completion.countDown()
		}
	}

	private fun writeRecord(output: ZstdOutputStream, payload: ByteArray, checksum: DatasetTelemetryChecksum? = null) {
		checksum?.updateRecord(payload)
		val buffer = directBuffers.take()
		try {
			require(payload.size + 4 <= buffer.capacity()) { "FlatBuffer batch exceeds the fixed direct-buffer budget" }
			buffer.clear()
			buffer.putInt(payload.size)
			buffer.put(payload)
			buffer.flip()
			val scratch = ByteArray(minOf(8192, buffer.remaining()))
			while (buffer.hasRemaining()) {
				val count = minOf(scratch.size, buffer.remaining())
				buffer.get(scratch, 0, count)
				output.write(scratch, 0, count)
			}
		} finally {
			directBuffers.put(buffer)
		}
	}

	private fun persistProgress(work: Path, original: DatasetManifest) {
		writeManifestDurably(work, original.copy(durationNs = (clockNs() - sessionStartNs).coerceAtLeast(0), quality = counters()))
	}

	private fun writeManifestDurably(work: Path, value: DatasetManifest) {
		val target = work.resolve(MANIFEST_PARTIAL)
		val temporary = work.resolve("$MANIFEST_PARTIAL.tmp")
		temporary.writeText(value.toJsonString())
		FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
		try {
			Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
		} catch (_: Exception) {
			Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
		}
	}

	private fun finalizeArchive(work: Path, finalManifest: DatasetManifest, telemetry: Path): Path {
		val manifestBytes = finalManifest.toJsonString().toByteArray(Charsets.UTF_8)
		val partialArchive = datasetsRoot.resolve("${finalManifest.sessionId}.nvrdata.partial")
		val archive = datasetsRoot.resolve("${finalManifest.sessionId}.nvrdata")
		ZipOutputStream(BufferedOutputStream(Files.newOutputStream(partialArchive))).use { zip ->
			putStored(zip, "manifest.json", manifestBytes)
			putStored(zip, "telemetry.fbs.zst", telemetry)
		}
		FileChannel.open(partialArchive, StandardOpenOption.WRITE).use { it.force(true) }
		try {
			Files.move(partialArchive, archive, StandardCopyOption.ATOMIC_MOVE)
		} catch (_: Exception) {
			Files.move(partialArchive, archive)
		}
		Files.deleteIfExists(work.resolve(MANIFEST_PARTIAL))
		Files.deleteIfExists(telemetry)
		Files.deleteIfExists(work.resolve(TELEMETRY_PARTIAL))
		Files.deleteIfExists(work)
		return archive
	}

	private fun putStored(zip: ZipOutputStream, name: String, bytes: ByteArray) {
		val crc = CRC32().apply { update(bytes) }
		zip.putNextEntry(ZipEntry(name).apply {
			method = ZipEntry.STORED
			size = bytes.size.toLong()
			compressedSize = size
			this.crc = crc.value
		})
		zip.write(bytes)
		zip.closeEntry()
	}

	private fun putStored(zip: ZipOutputStream, name: String, source: Path) {
		val crc = CRC32()
		BufferedInputStream(Files.newInputStream(source)).use { input ->
			val bytes = ByteArray(64 * 1024)
			while (true) {
				val read = input.read(bytes)
				if (read < 0) break
				crc.update(bytes, 0, read)
			}
		}
		zip.putNextEntry(ZipEntry(name).apply {
			method = ZipEntry.STORED
			size = source.fileSize()
			compressedSize = size
			this.crc = crc.value
		})
		Files.newInputStream(source).use { it.copyTo(zip) }
		zip.closeEntry()
	}

	private fun validateProfile(profile: CollectionProfile, roster: List<SessionTrackerMetadata>) {
		if (profile != CollectionProfile.FULL_FIDELITY) return
		val required = TelemetryChannelRegistry.requiredFor(profile)
		val incomplete = roster.filter { !it.capabilities.containsAll(required) }
		require(incomplete.isEmpty()) { "Full-fidelity profile is unsupported by ${incomplete.size} tracker(s)" }
	}

	private fun resetCounters() {
		queue.clear()
		listOf(sampled, written, dropped, invalid, gapEvents, highWatermark, pendingQueueDrops, resetCount).forEach { it.set(0) }
		pendingResetEvents.clear()
		pendingResetLabels.clear()
		activeContextLabels.clear()
		frameRingBuffer.clear()
		contextEventRingBuffer.clear()
		lastAppliedResetFrame = -1L
		nextEventIndex.set(1L)
		validationReport = null
	}

	private fun updateHighWatermark(value: Int) {
		while (true) {
			val current = highWatermark.get()
			if (value <= current || highWatermark.compareAndSet(current, value.toLong())) return
		}
	}

	private fun counters() = DatasetQualityCounters(
		sampledFrames = sampled.get(), writtenFrames = written.get(), droppedFrames = dropped.get(),
		gapEvents = gapEvents.get(), invalidSamples = invalid.get(), queueHighWatermark = highWatermark.get().toInt(),
	)

	private fun sha256(path: Path): String {
		val digest = MessageDigest.getInstance("SHA-256")
		Files.newInputStream(path).use { input ->
			val bytes = ByteArray(64 * 1024)
			while (true) {
				val read = input.read(bytes)
				if (read < 0) break
				digest.update(bytes, 0, read)
			}
		}
		return digest.digest().hex()
	}

	private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

	private fun io.github.axisangles.ktmath.Quaternion.sample() = QuaternionSample(x, y, z, w)

	override fun close() {
		unbindResetPublisher()
		if (isRecording) runCatching { stopAndFinalize() }
	}

	companion object {
		const val MANIFEST_PARTIAL = "manifest.partial.json"
		const val TELEMETRY_PARTIAL = "telemetry.fbs.zst.partial"
	}
}
