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
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.name

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
) {
	private val statusListener = RecordingStatusListener { broadcastStatusResponse() }

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

	fun onStartDatasetRecordingRequest(conn: GenericConnection, header: RpcMessageHeader) {
		val req = header.message(StartDatasetRecordingRequest()) as? StartDatasetRecordingRequest ?: return
		if (!req.consent()) { sendStatus(conn, header, DatasetOperation.START, DatasetErrorCode.CONSENT_REQUIRED, "Explicit recording consent is required"); return }
		if (datasetRecorder.isRecording || datasetRecorder.status().state in setOf(RecordingState.STARTING, RecordingState.FINALIZING)) {
			sendStatus(conn, header, DatasetOperation.START, DatasetErrorCode.ALREADY_RECORDING, "A recorder operation is already active"); return
		}
		if (getReadinessFindings().any { it.severity == DatasetReadinessSeverity.ERROR }) {
			sendStatus(conn, header, DatasetOperation.START, DatasetErrorCode.READINESS_FAILED, "Recorder readiness checks failed"); return
		}
		if (req.profile() != 0 && !datasetReadyStatusProvider().ready) {
			sendStatus(conn, header, DatasetOperation.START, DatasetErrorCode.READINESS_FAILED, "Higher-fidelity recording profiles remain locked until the dataset-ready gate passes")
			return
		}
		val profile = when (req.profile()) { 1 -> CollectionProfile.STANDARD; 2 -> CollectionProfile.FULL_FIDELITY; else -> CollectionProfile.MINIMUM }
		try {
			datasetRecorder.startRecording(RecordingRequest(profile, SessionPrivacyOptions(req.consent(), req.subjectPseudonym()?.takeIf(String::isNotBlank), req.hashHardwareIdentifiers())), trackerProvider())
			sendStatus(conn, header, DatasetOperation.START)
		} catch (error: Throwable) {
			LogManager.severe("[Dataset RPC] Start failed: ${error.message}", error)
			sendStatus(conn, header, DatasetOperation.START, DatasetErrorCode.INTERNAL_ERROR, error.message ?: "Start failed")
		}
	}

	fun onStopDatasetRecordingRequest(conn: GenericConnection, header: RpcMessageHeader) {
		val req = header.message(StopDatasetRecordingRequest()) as? StopDatasetRecordingRequest ?: return
		val status = datasetRecorder.status()
		if (status.state != RecordingState.RECORDING) { sendStatus(conn, header, DatasetOperation.STOP_FINALIZE, DatasetErrorCode.NOT_RECORDING, "Recorder is not recording"); return }
		if (!req.sessionId().isNullOrBlank() && req.sessionId() != status.sessionId) { sendStatus(conn, header, DatasetOperation.STOP_FINALIZE, DatasetErrorCode.SESSION_MISMATCH, "Session ID mismatch"); return }
		try {
			datasetRecorder.beginFinalization()
			sendStatus(conn, header, DatasetOperation.STOP_FINALIZE)
			workerExecutor.execute { runCatching { datasetRecorder.stopAndFinalize(req.timeoutSeconds().coerceAtLeast(5L)) }.onFailure { LogManager.severe("[Dataset RPC] Finalization failed: ${it.message}", it) } }
		} catch (error: Throwable) {
			sendStatus(conn, header, DatasetOperation.STOP_FINALIZE, DatasetErrorCode.INVALID_STATE, error.message ?: "Stop failed")
		}
	}

	fun onCancelDatasetRecordingRequest(conn: GenericConnection, header: RpcMessageHeader) {
		val req = header.message(CancelDatasetRecordingRequest()) as? CancelDatasetRecordingRequest ?: return
		val status = datasetRecorder.status()
		if (status.state !in setOf(RecordingState.STARTING, RecordingState.RECORDING, RecordingState.FINALIZING)) { sendStatus(conn, header, DatasetOperation.CANCEL, DatasetErrorCode.NOT_RECORDING, "No active recording"); return }
		if (!req.sessionId().isNullOrBlank() && req.sessionId() != status.sessionId) { sendStatus(conn, header, DatasetOperation.CANCEL, DatasetErrorCode.SESSION_MISMATCH, "Session ID mismatch"); return }
		workerExecutor.execute { datasetRecorder.cancelRecording(); sendStatus(conn, header, DatasetOperation.CANCEL, DatasetErrorCode.CANCELLED) }
	}

	fun onDatasetRecordingStatusRequest(conn: GenericConnection, header: RpcMessageHeader) = sendStatus(conn, header, DatasetOperation.STATUS)

	private fun ValidationFinding.toRpc() = DatasetValidationFindingT().also {
		it.code = code; it.message = message
		it.severity = when (severity) { FindingSeverity.INFO -> DatasetReadinessSeverity.INFO; FindingSeverity.WARNING -> DatasetReadinessSeverity.WARNING; FindingSeverity.FATAL -> DatasetReadinessSeverity.ERROR }
	}

	fun onDatasetListRequest(conn: GenericConnection, header: RpcMessageHeader) {
		val sessions = mutableListOf<DatasetSessionInfoT>()
		val root = runCatching { managedRoot() }.getOrNull()
		if (root != null) Files.list(root).use { paths -> paths.filter { it.name.endsWith(".nvrdata") && !Files.isSymbolicLink(it) }.forEach { archive ->
			val report = DatasetArchiveValidator().validate(archive)
			val manifest = runCatching { ZipFile(archive.toFile()).use { zip -> zip.getEntry("manifest.json")?.let { DatasetManifest.fromJsonString(zip.getInputStream(it).reader().readText()) } } }.getOrNull()
			sessions += DatasetSessionInfoT().apply {
				sessionId = manifest?.sessionId ?: archive.name.removeSuffix(".nvrdata"); archivePath = ""; archiveBytes = runCatching { archive.fileSize() }.getOrDefault(0)
				durationNs = manifest?.durationNs ?: 0; sampledFrames = manifest?.quality?.sampledFrames ?: report.frames; writtenFrames = manifest?.quality?.writtenFrames ?: report.frames
				droppedFrames = manifest?.quality?.droppedFrames ?: 0; resetCount = report.resetLabels; schemaMajor = (manifest?.schemaMajor ?: report.schemaMajor ?: 0).toLong(); schemaMinor = (manifest?.schemaMinor ?: report.schemaMinor ?: 0).toLong()
				createdUtc = manifest?.createdUtc ?: ""; isValid = report.valid; validationError = report.findings.filter { it.severity == FindingSeverity.FATAL }.joinToString("; ") { it.message }
				trackersCount = (manifest?.trackers?.size ?: 0).toLong(); validationState = if (report.valid) DatasetValidationState.VALID else DatasetValidationState.INVALID
				validationFindings = report.findings.map { it.toRpc() }.toTypedArray(); roster = manifest?.trackers?.map { it.toRpc() }?.toTypedArray() ?: emptyArray()
			}
		} }
		val recoverable = datasetRecorder.discoverRecoverableSessions().map { session ->
			val recoveryFindings = datasetRecorder.recoverabilityFindings(session)
			DatasetRecoverableInfoT().apply {
			sessionId = session.sessionId; directory = ""; reason = session.reason
			canRecover = recoveryFindings.none { it.severity == FindingSeverity.FATAL }
			validationState = DatasetValidationState.RECOVERABLE
			findings = recoveryFindings.map { it.toRpc() }.toTypedArray()
		} }
		val response = DatasetListResponseT().apply { this.sessions = sessions.toTypedArray(); this.recoverable = recoverable.toTypedArray(); errorCode = DatasetErrorCode.OK }
		val fbb = FlatBufferBuilder(256); val offset = DatasetListResponse.pack(fbb, response); fbb.finish(createRPCMessage(fbb, RpcMessage.DatasetListResponse, offset, header)); conn.send(fbb.dataBuffer())
	}

	fun onDatasetValidateRequest(conn: GenericConnection, header: RpcMessageHeader) {
		val req = header.message(DatasetValidateRequest()) as? DatasetValidateRequest ?: return
		val id = sanitizeSessionId(req.sessionId())
		if (id == null) { sendValidate(conn, header, req.sessionId() ?: "", null, DatasetErrorCode.INVALID_ARGUMENT, "Invalid session ID"); return }
		val archive = managedArchive(id)
		if (archive == null) { sendValidate(conn, header, id, null, DatasetErrorCode.SESSION_NOT_FOUND, "Archive not found"); return }
		sendValidate(conn, header, id, DatasetArchiveValidator().validate(archive))
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
		val session = datasetRecorder.discoverRecoverableSessions().firstOrNull { it.sessionId == id && managedPartial(id) == it.directory.toRealPath() }
		if (session == null) { sendAction(conn, header, id, false, DatasetOperation.RECOVER, DatasetErrorCode.NOT_RECOVERABLE, "Recoverable session not found"); return }
		val quarantine = req.action() == DatasetRecoveryAction.QUARANTINE || req.quarantine()
		if (!quarantine && datasetRecorder.recoverabilityFindings(session).any { it.severity == FindingSeverity.FATAL }) {
			sendAction(conn, header, id, false, DatasetOperation.RECOVER, DatasetErrorCode.NOT_RECOVERABLE, "Session does not contain a trustworthy recoverable prefix")
			return
		}
		workerExecutor.execute { runCatching { if (quarantine) datasetRecorder.quarantinePartial(session) else datasetRecorder.recoverPartial(session) }
			.onSuccess { sendAction(conn, header, id, true, if (quarantine) DatasetOperation.QUARANTINE else DatasetOperation.RECOVER) }
			.onFailure { sendAction(conn, header, id, false, if (quarantine) DatasetOperation.QUARANTINE else DatasetOperation.RECOVER, DatasetErrorCode.RECOVERY_FAILED, it.message ?: "Recovery failed") } }
	}

	fun onDatasetDeleteRequest(conn: GenericConnection, header: RpcMessageHeader) {
		val req = header.message(DatasetDeleteRequest()) as? DatasetDeleteRequest ?: return
		val id = sanitizeSessionId(req.sessionId())
		if (id == null) { sendAction(conn, header, req.sessionId() ?: "", false, DatasetOperation.DELETE, DatasetErrorCode.INVALID_ARGUMENT, "Invalid session ID"); return }
		val targets = listOfNotNull(managedArchive(id), managedPartial(id))
		for (target in targets) if (target.isDirectory()) Files.walk(target).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } else Files.deleteIfExists(target)
		sendAction(conn, header, id, targets.isNotEmpty(), DatasetOperation.DELETE, if (targets.isEmpty()) DatasetErrorCode.SESSION_NOT_FOUND else DatasetErrorCode.OK, if (targets.isEmpty()) "Session not found" else "")
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
}
