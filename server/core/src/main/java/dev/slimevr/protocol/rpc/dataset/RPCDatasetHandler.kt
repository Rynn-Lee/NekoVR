package dev.slimevr.protocol.rpc.dataset

import com.google.flatbuffers.FlatBufferBuilder
import dev.slimevr.dataset.*
import dev.slimevr.protocol.GenericConnection
import dev.slimevr.protocol.ProtocolAPI
import dev.slimevr.protocol.rpc.RPCHandler
import dev.slimevr.tracking.trackers.Tracker
import io.eiren.util.logging.LogManager
import solarxr_protocol.MessageBundle
import solarxr_protocol.datatypes.TransactionId
import solarxr_protocol.rpc.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Comparator
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.name

private const val MAX_INVENTORY_ENTRIES = 10_000

private fun boundedDatasetExecutor(): Executor = ThreadPoolExecutor(
	2,
	2,
	0L,
	TimeUnit.MILLISECONDS,
	ArrayBlockingQueue(32),
	{ runnable -> Thread(runnable, "dataset-inventory-worker").apply { isDaemon = true } },
	ThreadPoolExecutor.AbortPolicy(),
)

/** Server-authoritative recorder control plane. Renderer clients never supply filesystem paths. */
class RPCDatasetHandler(
	private val rpcHandler: RPCHandler? = null,
	private val api: ProtocolAPI? = null,
	val datasetRecorder: DatasetRecordingService = api?.server?.datasetRecorder ?: DatasetRecordingService(),
	val trackerProvider: () -> List<Tracker> = { api?.server?.allTrackers ?: emptyList() },
	val datasetReadyStatusProvider: () -> DatasetReadyStatus = {
		DatasetReadyReportStore.load(DatasetReadyReportStore.resolve(datasetRecorder.datasetsRoot))
	},
	@Suppress("unused") val taskQueue: (Runnable) -> Unit = { runnable -> api?.server?.queueTask(runnable) ?: runnable.run() },
	val broadcaster: ((GenericConnection) -> Unit) -> Unit = { action -> api?.apiServers?.forEach { server -> server.apiConnections.forEach(action) } },
	val workerExecutor: Executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "dataset-rpc-worker").apply { isDaemon = true } },
	val inventoryExecutor: Executor = boundedDatasetExecutor(),
) {
	private val statusListener = RecordingStatusListener { broadcastStatusResponse() }
	private data class ArchiveIdentity(val canonicalPath: Path, val size: Long, val modifiedMillis: Long, val fileKey: String?)
	private data class CachedArchive(
		val identity: ArchiveIdentity,
		val report: DatasetValidationReport,
		val manifest: DatasetManifest?,
		val sha256: String,
	)
	private val archiveCache = ConcurrentHashMap<Path, CachedArchive>()
	internal val archiveCacheSize: Int get() = archiveCache.size

	init {
		rpcHandler?.registerPacketListener(RpcMessage.StartDatasetRecordingRequest, ::onStartDatasetRecordingRequest)
		rpcHandler?.registerPacketListener(RpcMessage.StopDatasetRecordingRequest, ::onStopDatasetRecordingRequest)
		rpcHandler?.registerPacketListener(RpcMessage.CancelDatasetRecordingRequest, ::onCancelDatasetRecordingRequest)
		rpcHandler?.registerPacketListener(RpcMessage.DatasetRecordingStatusRequest, ::onDatasetRecordingStatusRequest)
		rpcHandler?.registerPacketListener(RpcMessage.DatasetListRequest, ::onDatasetListRequest)
		rpcHandler?.registerPacketListener(RpcMessage.DatasetValidateRequest, ::onDatasetValidateRequest)
		rpcHandler?.registerPacketListener(RpcMessage.DatasetRecoverRequest, ::onDatasetRecoverRequest)
		rpcHandler?.registerPacketListener(RpcMessage.DatasetDeleteRequest, ::onDatasetDeleteRequest)
		rpcHandler?.registerPacketListener(RpcMessage.DatasetExportRequest, ::onDatasetExportRequest)
		rpcHandler?.registerPacketListener(RpcMessage.DatasetRevealRequest, ::onDatasetRevealRequest)
		datasetRecorder.addStatusListener(statusListener)
	}

	fun createRPCMessage(fbb: FlatBufferBuilder, messageType: Byte, messageOffset: Int, respondTo: RpcMessageHeader? = null): Int {
		if (rpcHandler != null) return rpcHandler.createRPCMessage(fbb, messageType, messageOffset, respondTo)
		RpcMessageHeader.startRpcMessageHeader(fbb)
		RpcMessageHeader.addMessage(fbb, messageOffset)
		RpcMessageHeader.addMessageType(fbb, messageType)
		respondTo?.txId()?.id()?.let { RpcMessageHeader.addTxId(fbb, TransactionId.createTransactionId(fbb, it)) }
		val rpcHeader = RpcMessageHeader.endRpcMessageHeader(fbb)
		val messages = MessageBundle.createRpcMsgsVector(fbb, intArrayOf(rpcHeader))
		MessageBundle.startMessageBundle(fbb)
		MessageBundle.addRpcMsgs(fbb, messages)
		return MessageBundle.endMessageBundle(fbb)
	}

	fun sanitizeSessionId(sessionId: String?): String? = sessionId?.takeIf { it.matches(Regex("^[A-Za-z0-9_-]{1,128}$")) }

	fun isContainedInRoot(targetPath: Path, root: Path): Boolean = runCatching {
		val canonicalRoot = root.apply { createDirectories() }.toRealPath()
		!Files.isSymbolicLink(targetPath) && targetPath.toRealPath().startsWith(canonicalRoot)
	}.getOrDefault(false)

	private fun managedRoot(): Path = datasetRecorder.datasetsRoot.apply { createDirectories() }.toRealPath()

	private fun managedArchive(sessionId: String): Path? = runCatching {
		val root = managedRoot()
		val candidate = root.resolve("$sessionId.nvrdata")
		if (!candidate.exists() || Files.isSymbolicLink(candidate)) return@runCatching null
		candidate.toRealPath().takeIf { it.parent == root }
	}.getOrNull()

	private fun managedPartial(sessionId: String): Path? = runCatching {
		val root = managedRoot()
		val candidate = root.resolve("$sessionId.partial")
		if (!candidate.exists() || Files.isSymbolicLink(candidate)) return@runCatching null
		candidate.toRealPath().takeIf { it.parent == root && it.isDirectory() }
	}.getOrNull()

	fun getReadinessFindings(): List<DatasetReadinessFindingT> {
		val findings = mutableListOf<DatasetReadinessFindingT>()
		val trackers = trackerProvider()
		fun add(code: String, severityValue: Int, text: String) = findings.add(DatasetReadinessFindingT().apply {
			this.code = code; severity = severityValue; message = text
		})
		val hmd = trackers.firstOrNull { it.isHmd }
		if (hmd == null || !hmd.status.sendData) add("HMD_UNAVAILABLE", DatasetReadinessSeverity.ERROR, "HMD reference is absent or stale")
		val assignedImus = trackers.filter { it.isImu() && it.trackerPosition != null && !it.isHmd }
		if (assignedImus.isEmpty()) add("NO_ASSIGNED_IMUS", DatasetReadinessSeverity.ERROR, "At least one assigned physical IMU is required")
		runCatching {
			val root = managedRoot()
			if (Files.getFileStore(root).usableSpace < 250L * 1024L * 1024L) add("LOW_DISK_SPACE", DatasetReadinessSeverity.ERROR, "At least 250 MiB of free space is required")
		}.onFailure { add("STORAGE_ERROR", DatasetReadinessSeverity.ERROR, "Dataset storage is unavailable: ${it.message}") }
		val unknown = assignedImus.count { it.imuType == null || it.imuType == dev.slimevr.tracking.trackers.udp.IMUType.UNKNOWN }
		if (unknown > 0) add("UNKNOWN_HARDWARE", DatasetReadinessSeverity.WARNING, "$unknown tracker(s) have unknown IMU metadata")
		val datasetReady = datasetReadyStatusProvider()
		if (datasetReady.ready) {
			add("DATASET_READY", DatasetReadinessSeverity.INFO, datasetReady.detail)
		} else {
			add("DATASET_READY_GATE_PENDING", DatasetReadinessSeverity.WARNING, datasetReady.detail)
		}
		return findings
	}

	fun toProtocolState(state: RecordingState): Int = when (state) {
		RecordingState.IDLE -> DatasetRecordingState.IDLE
		RecordingState.STARTING -> DatasetRecordingState.STARTING
		RecordingState.RECORDING -> DatasetRecordingState.RECORDING
		RecordingState.FINALIZING -> DatasetRecordingState.FINALIZING
		RecordingState.COMPLETED -> DatasetRecordingState.COMPLETED
		RecordingState.FAILED -> DatasetRecordingState.FAILED
		RecordingState.RECOVERABLE -> DatasetRecordingState.RECOVERABLE
		RecordingState.QUARANTINED -> DatasetRecordingState.QUARANTINED
		RecordingState.CANCELLED -> DatasetRecordingState.CANCELLED
	}

	private fun SessionTrackerMetadata.toRpc(statusValue: String = "RECORDED") = DatasetActiveTrackerT().also {
		it.sessionTrackerId = sessionTrackerId; it.displayName = bodyRole.ifBlank { sessionTrackerId }; it.bodyRole = bodyRole
		it.imuType = imuType; it.transport = transport; it.status = statusValue
	}

	private fun statusPayload(operationValue: Int, errorCodeValue: Int = DatasetErrorCode.OK, errorText: String = ""): DatasetRecordingStatusResponseT {
		val status = datasetRecorder.status()
		val root = runCatching { managedRoot() }.getOrNull()
		val validationReport = status.validationReport
		return DatasetRecordingStatusResponseT().apply {
			state = toProtocolState(status.state); sessionId = status.sessionId ?: ""
			sampledFrames = status.sampledFrames; writtenFrames = status.writtenFrames; droppedFrames = status.droppedFrames
			queuedFrames = status.queuedItems.toLong(); queueHighWatermark = status.queueHighWatermark.toLong(); writtenBytes = status.bytesWritten
			outputArchive = ""; error = errorText.ifBlank { status.lastError ?: "" }; resetCount = status.resetCount
			readiness = getReadinessFindings().toTypedArray(); elapsedDurationNs = status.elapsedNs; finalizationProgress = status.finalizationProgress
			diskFreeBytes = root?.let { runCatching { Files.getFileStore(it).usableSpace }.getOrDefault(0L) } ?: 0L
			diskTotalBytes = root?.let { runCatching { Files.getFileStore(it).totalSpace }.getOrDefault(0L) } ?: 0L
			activeRoster = status.roster.map { it.bodyRole }.toTypedArray()
			errorCode = if (errorCodeValue != DatasetErrorCode.OK) errorCodeValue else if (status.state == RecordingState.FAILED) DatasetErrorCode.INTERNAL_ERROR else DatasetErrorCode.OK
			operation = operationValue; statusVersion = status.statusVersion
			validationState = when {
				validationReport == null -> DatasetValidationState.UNKNOWN
				validationReport.valid -> DatasetValidationState.VALID
				else -> DatasetValidationState.INVALID
			}
			validationFindings = validationReport?.findings?.map { it.toRpc() }?.toTypedArray() ?: emptyArray()
			roster = status.roster.map { it.toRpc(if (status.state == RecordingState.RECORDING) "ACTIVE" else "RECORDED") }.toTypedArray()
		}
	}

	private fun sendStatus(conn: GenericConnection, header: RpcMessageHeader?, operation: Int, errorCode: Int = DatasetErrorCode.OK, error: String = "") {
		val fbb = FlatBufferBuilder(256)
		val offset = DatasetRecordingStatusResponse.pack(fbb, statusPayload(operation, errorCode, error))
		fbb.finish(createRPCMessage(fbb, RpcMessage.DatasetRecordingStatusResponse, offset, header))
		conn.send(fbb.dataBuffer())
	}

	fun broadcastStatusResponse() {
		val fbb = FlatBufferBuilder(256)
		val offset = DatasetRecordingStatusResponse.pack(fbb, statusPayload(DatasetOperation.NONE))
		fbb.finish(createRPCMessage(fbb, RpcMessage.DatasetRecordingStatusResponse, offset))
		val buffer = fbb.dataBuffer()
		val bytes = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
		broadcaster { it.send(java.nio.ByteBuffer.wrap(bytes)) }
	}

	private fun submitRecorderCommand(command: () -> Unit) {
		taskQueue(Runnable(command))
	}

	fun onStartDatasetRecordingRequest(conn: GenericConnection, header: RpcMessageHeader) {
		val req = header.message(StartDatasetRecordingRequest()) as? StartDatasetRecordingRequest ?: return
		val profileValue = req.profile()
		if (profileValue !in 0..2) {
			sendStatus(conn, header, DatasetOperation.START, DatasetErrorCode.INVALID_ARGUMENT, "Unknown collection profile $profileValue")
			return
		}
		val consent = req.consent()
		val pseudonym = req.subjectPseudonym()?.takeIf(String::isNotBlank)
		val hashHardwareIdentifiers = req.hashHardwareIdentifiers()
		submitRecorderCommand {
			if (!consent) { sendStatus(conn, header, DatasetOperation.START, DatasetErrorCode.CONSENT_REQUIRED, "Explicit recording consent is required"); return@submitRecorderCommand }
			if (datasetRecorder.status().state in setOf(RecordingState.STARTING, RecordingState.RECORDING, RecordingState.FINALIZING)) {
				sendStatus(conn, header, DatasetOperation.START, DatasetErrorCode.ALREADY_RECORDING, "A recorder operation is already active"); return@submitRecorderCommand
			}
			if (getReadinessFindings().any { it.severity == DatasetReadinessSeverity.ERROR }) {
				sendStatus(conn, header, DatasetOperation.START, DatasetErrorCode.READINESS_FAILED, "Recorder readiness checks failed"); return@submitRecorderCommand
			}
			if (profileValue != 0 && !datasetReadyStatusProvider().ready) {
				sendStatus(conn, header, DatasetOperation.START, DatasetErrorCode.READINESS_FAILED, "Higher-fidelity recording profiles remain locked until the dataset-ready gate passes")
				return@submitRecorderCommand
			}
			val profile = CollectionProfile.entries[profileValue]
			try {
				datasetRecorder.startRecording(RecordingRequest(profile, SessionPrivacyOptions(consent, pseudonym, hashHardwareIdentifiers)), trackerProvider())
				invalidateArchiveCache()
				sendStatus(conn, header, DatasetOperation.START)
			} catch (error: IllegalArgumentException) {
				sendStatus(conn, header, DatasetOperation.START, DatasetErrorCode.INVALID_ARGUMENT, error.message ?: "Invalid recording arguments")
			} catch (error: IllegalStateException) {
				sendStatus(conn, header, DatasetOperation.START, DatasetErrorCode.INVALID_STATE, error.message ?: "Recorder state changed")
			} catch (error: Throwable) {
				LogManager.severe("[Dataset RPC] Start failed: ${error.message}", error)
				sendStatus(conn, header, DatasetOperation.START, DatasetErrorCode.INTERNAL_ERROR, error.message ?: "Start failed")
			}
		}
	}

	fun onStopDatasetRecordingRequest(conn: GenericConnection, header: RpcMessageHeader) {
		val req = header.message(StopDatasetRecordingRequest()) as? StopDatasetRecordingRequest ?: return
		val requestedSessionId = req.sessionId()?.takeIf(String::isNotBlank)
		val timeoutSeconds = req.timeoutSeconds().coerceAtLeast(5L)
		submitRecorderCommand {
			val status = datasetRecorder.status()
			if (status.state != RecordingState.RECORDING) { sendStatus(conn, header, DatasetOperation.STOP_FINALIZE, DatasetErrorCode.NOT_RECORDING, "Recorder is not recording"); return@submitRecorderCommand }
			if (requestedSessionId != null && requestedSessionId != status.sessionId) { sendStatus(conn, header, DatasetOperation.STOP_FINALIZE, DatasetErrorCode.SESSION_MISMATCH, "Session ID mismatch"); return@submitRecorderCommand }
			try {
				datasetRecorder.beginFinalization()
				sendStatus(conn, header, DatasetOperation.STOP_FINALIZE)
				workerExecutor.execute {
					runCatching { datasetRecorder.stopAndFinalize(timeoutSeconds) }
						.onSuccess { invalidateArchiveCache(status.sessionId) }
						.onFailure { LogManager.severe("[Dataset RPC] Finalization failed: ${it.message}", it) }
				}
			} catch (error: Throwable) {
				sendStatus(conn, header, DatasetOperation.STOP_FINALIZE, DatasetErrorCode.INVALID_STATE, error.message ?: "Stop failed")
			}
		}
	}

	fun onCancelDatasetRecordingRequest(conn: GenericConnection, header: RpcMessageHeader) {
		val req = header.message(CancelDatasetRecordingRequest()) as? CancelDatasetRecordingRequest ?: return
		val requestedSessionId = req.sessionId()?.takeIf(String::isNotBlank)
		submitRecorderCommand {
			val status = datasetRecorder.status()
			if (status.state !in setOf(RecordingState.STARTING, RecordingState.RECORDING, RecordingState.FINALIZING)) { sendStatus(conn, header, DatasetOperation.CANCEL, DatasetErrorCode.NOT_RECORDING, "No active recording"); return@submitRecorderCommand }
			if (requestedSessionId != null && requestedSessionId != status.sessionId) { sendStatus(conn, header, DatasetOperation.CANCEL, DatasetErrorCode.SESSION_MISMATCH, "Session ID mismatch"); return@submitRecorderCommand }
			if (!datasetRecorder.requestCancellation()) { sendStatus(conn, header, DatasetOperation.CANCEL, DatasetErrorCode.INVALID_STATE, "Recorder state changed"); return@submitRecorderCommand }
			invalidateArchiveCache(status.sessionId)
			sendStatus(conn, header, DatasetOperation.CANCEL, DatasetErrorCode.CANCELLED)
			workerExecutor.execute { datasetRecorder.finishCancellation() }
		}
	}

	fun onDatasetRecordingStatusRequest(conn: GenericConnection, header: RpcMessageHeader) = sendStatus(conn, header, DatasetOperation.STATUS)

	private fun ValidationFinding.toRpc() = DatasetValidationFindingT().also {
		it.code = code; it.message = message
		it.severity = when (severity) { FindingSeverity.INFO -> DatasetReadinessSeverity.INFO; FindingSeverity.WARNING -> DatasetReadinessSeverity.WARNING; FindingSeverity.FATAL -> DatasetReadinessSeverity.ERROR }
	}

	fun onDatasetListRequest(conn: GenericConnection, header: RpcMessageHeader) {
		try {
			inventoryExecutor.execute {
				runCatching { buildInventory() }
					.onSuccess { (sessions, recoverable) -> sendList(conn, header, sessions, recoverable, DatasetErrorCode.OK) }
					.onFailure { error ->
						LogManager.severe("[Dataset RPC] Inventory failed: ${error.message}", error)
						sendList(conn, header, emptyList(), emptyList(), DatasetErrorCode.IO_ERROR)
					}
			}
		} catch (_: RejectedExecutionException) {
			sendList(conn, header, emptyList(), emptyList(), DatasetErrorCode.BUSY)
		}
	}

	fun onDatasetValidateRequest(conn: GenericConnection, header: RpcMessageHeader) {
		val req = header.message(DatasetValidateRequest()) as? DatasetValidateRequest ?: return
		val id = sanitizeSessionId(req.sessionId())
		if (id == null) { sendValidate(conn, header, req.sessionId() ?: "", null, DatasetErrorCode.INVALID_ARGUMENT, "Invalid session ID"); return }
		try {
			inventoryExecutor.execute {
				val archive = managedArchive(id)
				if (archive == null) {
					sendValidate(conn, header, id, null, DatasetErrorCode.SESSION_NOT_FOUND, "Archive not found")
				} else {
					runCatching { cachedArchive(archive).report }
						.onSuccess { sendValidate(conn, header, id, it) }
						.onFailure { sendValidate(conn, header, id, null, DatasetErrorCode.IO_ERROR, it.message ?: "Validation failed") }
				}
			}
		} catch (_: RejectedExecutionException) {
			sendValidate(conn, header, id, null, DatasetErrorCode.BUSY, "Dataset inventory workers are busy")
		}
	}

	private fun buildInventory(): Pair<List<DatasetSessionInfoT>, List<DatasetRecoverableInfoT>> {
		val root = managedRoot()
		val sessions = Files.list(root).use { paths ->
			paths.filter { it.name.endsWith(".nvrdata") && !Files.isSymbolicLink(it) }
				.limit(MAX_INVENTORY_ENTRIES.toLong())
				.map { cachedArchive(it).toRpc() }
				.toList()
		}
		archiveCache.keys.removeIf { !Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
		val recoverable = datasetRecorder.discoverRecoverableSessions().take(MAX_INVENTORY_ENTRIES).map { session ->
			val recoveryFindings = datasetRecorder.recoverabilityFindings(session)
			DatasetRecoverableInfoT().apply {
				sessionId = session.sessionId; directory = ""; reason = session.reason
				canRecover = recoveryFindings.none { it.severity == FindingSeverity.FATAL }
				validationState = DatasetValidationState.RECOVERABLE
				findings = recoveryFindings.map { it.toRpc() }.toTypedArray()
			}
		}
		return sessions to recoverable
	}

	private fun CachedArchive.toRpc() = DatasetSessionInfoT().apply {
		val archive = identity.canonicalPath
		sessionId = manifest?.sessionId ?: archive.name.removeSuffix(".nvrdata"); archiveSha256 = sha256; archivePath = ""; archiveBytes = identity.size
		durationNs = manifest?.durationNs ?: 0; sampledFrames = manifest?.quality?.sampledFrames ?: report.frames; writtenFrames = manifest?.quality?.writtenFrames ?: report.frames
		droppedFrames = manifest?.quality?.droppedFrames ?: 0; resetCount = report.resetLabels; schemaMajor = (manifest?.schemaMajor ?: report.schemaMajor ?: 0).toLong(); schemaMinor = (manifest?.schemaMinor ?: report.schemaMinor ?: 0).toLong()
		createdUtc = manifest?.createdUtc ?: ""; isValid = report.valid; validationError = report.findings.filter { it.severity == FindingSeverity.FATAL }.joinToString("; ") { it.message }
		trackersCount = (manifest?.trackers?.size ?: 0).toLong(); validationState = if (report.valid) DatasetValidationState.VALID else DatasetValidationState.INVALID
		validationFindings = report.findings.map { it.toRpc() }.toTypedArray(); roster = manifest?.trackers?.map { it.toRpc() }?.toTypedArray() ?: emptyArray()
	}

	private fun cachedArchive(archive: Path): CachedArchive {
		val identity = archiveIdentity(archive)
		archiveCache[identity.canonicalPath]?.takeIf { it.identity == identity }?.let { return it }
		return archiveCache.compute(identity.canonicalPath) { _, current ->
			if (current?.identity == identity) current else CachedArchive(
				identity,
				DatasetArchiveValidator().validate(identity.canonicalPath),
				runCatching { ZipFile(identity.canonicalPath.toFile()).use { zip -> zip.getEntry("manifest.json")?.let { DatasetManifest.fromJsonString(zip.getInputStream(it).reader().readText()) } } }.getOrNull(),
				fileSha256(identity.canonicalPath),
			)
		}!!
	}

	private fun archiveIdentity(path: Path): ArchiveIdentity {
		val canonical = path.toRealPath()
		require(canonical.parent == managedRoot() && !Files.isSymbolicLink(path)) { "Archive is outside the managed root" }
		val attributes = Files.readAttributes(canonical, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
		require(attributes.isRegularFile) { "Archive is not a regular file" }
		return ArchiveIdentity(canonical, attributes.size(), attributes.lastModifiedTime().toMillis(), attributes.fileKey()?.toString())
	}

	internal fun invalidateArchiveCache(sessionId: String? = null) {
		if (sessionId == null) archiveCache.clear() else archiveCache.keys.removeIf { it.fileName.toString() == "$sessionId.nvrdata" }
	}

	private fun sendList(conn: GenericConnection, header: RpcMessageHeader, sessions: List<DatasetSessionInfoT>, recoverable: List<DatasetRecoverableInfoT>, errorCodeValue: Int) {
		val response = DatasetListResponseT().apply { this.sessions = sessions.toTypedArray(); this.recoverable = recoverable.toTypedArray(); errorCode = errorCodeValue }
		val fbb = FlatBufferBuilder(256); val offset = DatasetListResponse.pack(fbb, response); fbb.finish(createRPCMessage(fbb, RpcMessage.DatasetListResponse, offset, header)); conn.send(fbb.dataBuffer())
	}

	private fun sendValidate(conn: GenericConnection, header: RpcMessageHeader, id: String, report: DatasetValidationReport?, errorCodeValue: Int = DatasetErrorCode.OK, errorText: String = "") {
		val response = DatasetValidateResponseT().apply {
			sessionId = id; valid = report?.valid == true; error = errorText.ifBlank { report?.findings?.filter { it.severity == FindingSeverity.FATAL }?.joinToString("; ") { it.message } ?: "" }
			quality = DatasetQualitySummaryT().apply { writtenFrames = report?.frames ?: 0 }; findings = report?.findings?.map { it.toRpc() }?.toTypedArray() ?: emptyArray()
			errorCode = if (report != null && !report.valid) DatasetErrorCode.VALIDATION_FAILED else errorCodeValue
		}
		val fbb = FlatBufferBuilder(256); val offset = DatasetValidateResponse.pack(fbb, response); fbb.finish(createRPCMessage(fbb, RpcMessage.DatasetValidateResponse, offset, header)); conn.send(fbb.dataBuffer())
	}

	fun onDatasetRecoverRequest(conn: GenericConnection, header: RpcMessageHeader) {
		val req = header.message(DatasetRecoverRequest()) as? DatasetRecoverRequest ?: return
		val id = sanitizeSessionId(req.sessionId())
		if (id == null) { sendAction(conn, header, req.sessionId() ?: "", false, DatasetOperation.RECOVER, DatasetErrorCode.INVALID_ARGUMENT, "Invalid session ID"); return }
		val quarantine = req.action() == DatasetRecoveryAction.QUARANTINE || req.quarantine()
		submitRecorderCommand {
			val session = datasetRecorder.discoverRecoverableSessions().firstOrNull { it.sessionId == id && managedPartial(id) == it.directory.toRealPath() }
			if (session == null) { sendAction(conn, header, id, false, DatasetOperation.RECOVER, DatasetErrorCode.NOT_RECOVERABLE, "Recoverable session not found"); return@submitRecorderCommand }
			if (!quarantine && datasetRecorder.recoverabilityFindings(session).any { it.severity == FindingSeverity.FATAL }) {
				sendAction(conn, header, id, false, DatasetOperation.RECOVER, DatasetErrorCode.NOT_RECOVERABLE, "Session does not contain a trustworthy recoverable prefix")
				return@submitRecorderCommand
			}
			workerExecutor.execute { runCatching { if (quarantine) datasetRecorder.quarantinePartial(session) else datasetRecorder.recoverPartial(session) }
				.onSuccess { invalidateArchiveCache(id); sendAction(conn, header, id, true, if (quarantine) DatasetOperation.QUARANTINE else DatasetOperation.RECOVER) }
				.onFailure { sendAction(conn, header, id, false, if (quarantine) DatasetOperation.QUARANTINE else DatasetOperation.RECOVER, DatasetErrorCode.RECOVERY_FAILED, it.message ?: "Recovery failed") } }
		}
	}

	fun onDatasetDeleteRequest(conn: GenericConnection, header: RpcMessageHeader) {
		val req = header.message(DatasetDeleteRequest()) as? DatasetDeleteRequest ?: return
		val id = sanitizeSessionId(req.sessionId())
		if (id == null) { sendAction(conn, header, req.sessionId() ?: "", false, DatasetOperation.DELETE, DatasetErrorCode.INVALID_ARGUMENT, "Invalid session ID"); return }
		submitRecorderCommand {
			val targets = listOfNotNull(managedArchive(id), managedPartial(id))
			for (target in targets) if (target.isDirectory()) Files.walk(target).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } else Files.deleteIfExists(target)
			invalidateArchiveCache(id)
			sendAction(conn, header, id, targets.isNotEmpty(), DatasetOperation.DELETE, if (targets.isEmpty()) DatasetErrorCode.SESSION_NOT_FOUND else DatasetErrorCode.OK, if (targets.isEmpty()) "Session not found" else "")
		}
	}

	fun onDatasetExportRequest(conn: GenericConnection, header: RpcMessageHeader) {
		val req = header.message(DatasetExportRequest()) as? DatasetExportRequest ?: return
		val id = sanitizeSessionId(req.sessionId())
		if (!req.targetPath().isNullOrBlank()) { sendAction(conn, header, id ?: "", false, DatasetOperation.EXPORT, DatasetErrorCode.PATH_REJECTED, "Export destinations are selected only through Electron"); return }
		managedArchiveAction(conn, header, id, DatasetOperation.EXPORT)
	}

	fun onDatasetRevealRequest(conn: GenericConnection, header: RpcMessageHeader) {
		val req = header.message(DatasetRevealRequest()) as? DatasetRevealRequest ?: return
		managedArchiveAction(conn, header, sanitizeSessionId(req.sessionId()), DatasetOperation.REVEAL)
	}

	private fun managedArchiveAction(conn: GenericConnection, header: RpcMessageHeader, id: String?, operation: Int) {
		if (id == null) { sendAction(conn, header, "", false, operation, DatasetErrorCode.INVALID_ARGUMENT, "Invalid session ID"); return }
		if (managedArchive(id) == null) { sendAction(conn, header, id, false, operation, DatasetErrorCode.SESSION_NOT_FOUND, "Archive not found"); return }
		sendAction(conn, header, id, true, operation)
	}

	private fun sendAction(conn: GenericConnection, header: RpcMessageHeader, id: String, successValue: Boolean, operationValue: Int, errorCodeValue: Int = DatasetErrorCode.OK, errorText: String = "") {
		val response = DatasetActionResponseT().apply { sessionId = id; success = successValue; error = errorText; path = ""; errorCode = errorCodeValue; operation = operationValue; statusVersion = datasetRecorder.status().statusVersion }
		val fbb = FlatBufferBuilder(128); val offset = DatasetActionResponse.pack(fbb, response); fbb.finish(createRPCMessage(fbb, RpcMessage.DatasetActionResponse, offset, header)); conn.send(fbb.dataBuffer())
	}

	private fun fileSha256(path: Path): String {
		val digest = MessageDigest.getInstance("SHA-256")
		Files.newInputStream(path).use { input ->
			val buffer = ByteArray(64 * 1024)
			while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
		}
		return digest.digest().joinToString("") { "%02x".format(it) }
	}
}
