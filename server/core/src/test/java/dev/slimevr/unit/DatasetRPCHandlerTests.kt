package dev.slimevr.unit

import com.google.flatbuffers.FlatBufferBuilder
import dev.slimevr.dataset.CollectionProfile
import dev.slimevr.dataset.DatasetArchiveValidator
import dev.slimevr.dataset.DatasetManifest
import dev.slimevr.dataset.DatasetPrivacy
import dev.slimevr.dataset.DatasetRecordingService
import dev.slimevr.dataset.FindingSeverity
import dev.slimevr.dataset.RecordingRequest
import dev.slimevr.dataset.RecordingState
import dev.slimevr.dataset.SessionPrivacyOptions
import dev.slimevr.protocol.ConnectionContext
import dev.slimevr.protocol.GenericConnection
import dev.slimevr.protocol.rpc.dataset.RPCDatasetHandler
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import dev.slimevr.tracking.trackers.udp.IMUType
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import solarxr_protocol.MessageBundle
import solarxr_protocol.datatypes.TransactionId
import solarxr_protocol.rpc.*
import java.nio.ByteBuffer
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.io.path.writeBytes

class DatasetRPCHandlerTests {
	private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
		val deadline = System.nanoTime() + timeoutMs * 1_000_000
		while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
		assertTrue(condition(), "Condition was not met within ${timeoutMs}ms")
	}

	private class TestConnection : GenericConnection {
		override val connectionId: UUID = UUID.randomUUID()
		override val context: ConnectionContext = ConnectionContext()
		val responses = mutableListOf<ByteBuffer>()

		override fun send(bytes: ByteBuffer) {
			val copy = ByteBuffer.allocate(bytes.remaining())
			copy.put(bytes.duplicate())
			copy.flip()
			responses.add(copy)
		}

		fun lastRpcHeader(): RpcMessageHeader {
			val lastBuf = responses.last()
			val bundle = MessageBundle.getRootAsMessageBundle(lastBuf)
			return bundle.rpcMsgs(0)
		}
	}

	private fun createHeadTracker(active: Boolean = true): Tracker = Tracker(
		device = null,
		id = 0,
		name = "Headset",
		trackerPosition = TrackerPosition.HEAD,
		trackerNum = 0,
		hasPosition = true,
		hasRotation = true,
		hasAcceleration = false,
		isComputed = true,
		trackRotDirection = false,
		isHmd = true,
	).apply {
		status = if (active) TrackerStatus.OK else TrackerStatus.DISCONNECTED
		position = Vector3(0f, 1.7f, 0f)
		setRotation(Quaternion.IDENTITY)
	}

	private fun createImuTracker(id: Int, position: TrackerPosition): Tracker = Tracker(
		device = null,
		id = id,
		name = "Tracker-$id",
		trackerPosition = position,
		trackerNum = id,
		hasPosition = false,
		hasRotation = true,
		hasAcceleration = true,
		isComputed = false,
		trackRotDirection = true,
		imuType = IMUType.BNO085,
	).apply {
		status = TrackerStatus.OK
		setRotation(Quaternion.IDENTITY)
		setAcceleration(Vector3(0f, 9.81f, 0f))
	}

	private fun createHeader(fbb: FlatBufferBuilder, messageType: Byte, messageOffset: Int, transactionId: Long = 42L): RpcMessageHeader {
		RpcMessageHeader.startRpcMessageHeader(fbb)
		RpcMessageHeader.addMessageType(fbb, messageType)
		RpcMessageHeader.addMessage(fbb, messageOffset)
		RpcMessageHeader.addTxId(fbb, TransactionId.createTransactionId(fbb, transactionId))
		val headerOffset = RpcMessageHeader.endRpcMessageHeader(fbb)
		fbb.finish(headerOffset)
		return RpcMessageHeader.getRootAsRpcMessageHeader(fbb.dataBuffer())
	}

	@Test
	fun testReadinessCheckMissingHMD(@TempDir tempDir: Path) {
		val recorder = DatasetRecordingService(datasetsRoot = tempDir)
		val tracker = createImuTracker(1, TrackerPosition.CHEST)
		val handler = RPCDatasetHandler(
			datasetRecorder = recorder,
			trackerProvider = { listOf(tracker) },
			taskQueue = { it.run() },
		)

		val findings = handler.getReadinessFindings()
		assertTrue(findings.any { it.code == "HMD_UNAVAILABLE" && it.severity == 2 })

		val conn = TestConnection()
		val fbb = FlatBufferBuilder(64)
		val startReq = StartDatasetRecordingRequest.createStartDatasetRecordingRequest(
			fbb,
			fbb.createString("session-1"),
			1,
			true,
			fbb.createString("subj"),
			true,
		)
		val header = createHeader(fbb, RpcMessage.StartDatasetRecordingRequest, startReq)

		handler.onStartDatasetRecordingRequest(conn, header)

		val respHeader = conn.lastRpcHeader()
		assertEquals(RpcMessage.DatasetRecordingStatusResponse, respHeader.messageType())
		val statusResp = respHeader.message(DatasetRecordingStatusResponse()) as DatasetRecordingStatusResponse
		assertEquals(DatasetRecordingState.IDLE, statusResp.state())
		assertEquals(RecordingState.IDLE, recorder.status().state)
	}

	@Test
	fun testReadinessCheckMissingIMU(@TempDir tempDir: Path) {
		val recorder = DatasetRecordingService(datasetsRoot = tempDir)
		val head = createHeadTracker()
		val handler = RPCDatasetHandler(
			datasetRecorder = recorder,
			trackerProvider = { listOf(head) },
			taskQueue = { it.run() },
		)

		val findings = handler.getReadinessFindings()
		assertTrue(findings.any { it.code == "NO_ASSIGNED_IMUS" && it.severity == 2 })

		val conn = TestConnection()
		val fbb = FlatBufferBuilder(64)
		val startReq = StartDatasetRecordingRequest.createStartDatasetRecordingRequest(
			fbb,
			fbb.createString("session-2"),
			1,
			true,
			fbb.createString("subj"),
			true,
		)
		val header = createHeader(fbb, RpcMessage.StartDatasetRecordingRequest, startReq)

		handler.onStartDatasetRecordingRequest(conn, header)

		val respHeader = conn.lastRpcHeader()
		val statusResp = respHeader.message(DatasetRecordingStatusResponse()) as DatasetRecordingStatusResponse
		assertEquals(DatasetRecordingState.IDLE, statusResp.state())
	}

	@Test
	fun testHigherFidelityProfilesRemainLocked(@TempDir tempDir: Path) {
		val recorder = DatasetRecordingService(datasetsRoot = tempDir)
		val trackers = listOf(createHeadTracker(), createImuTracker(1, TrackerPosition.WAIST))
		val handler = RPCDatasetHandler(
			datasetRecorder = recorder,
			trackerProvider = { trackers },
			taskQueue = { it.run() },
		)
		val conn = TestConnection()
		val fbb = FlatBufferBuilder(64)
		val startReq = StartDatasetRecordingRequest.createStartDatasetRecordingRequest(
			fbb,
			fbb.createString("locked-profile"),
			1,
			true,
			fbb.createString("subject"),
			true,
		)

		handler.onStartDatasetRecordingRequest(
			conn,
			createHeader(fbb, RpcMessage.StartDatasetRecordingRequest, startReq),
		)

		val response = conn.lastRpcHeader().message(DatasetRecordingStatusResponse()) as DatasetRecordingStatusResponse
		assertEquals(DatasetRecordingState.IDLE, response.state())
		assertEquals(DatasetOperation.START, response.operation())
		assertEquals(DatasetErrorCode.READINESS_FAILED, response.errorCode())
		assertEquals(RecordingState.IDLE, recorder.status().state)
	}

	@Test
	fun testPathTraversalSanitizationAndContainment(@TempDir tempDir: Path) {
		val recorder = DatasetRecordingService(datasetsRoot = tempDir)
		val handler = RPCDatasetHandler(datasetRecorder = recorder)

		assertNull(handler.sanitizeSessionId("../traversal"))
		assertNull(handler.sanitizeSessionId("..\\traversal"))
		assertNull(handler.sanitizeSessionId("sub/dir"))
		assertNull(handler.sanitizeSessionId("sub\\dir"))
		assertNull(handler.sanitizeSessionId("bad*char"))
		assertNull(handler.sanitizeSessionId(""))
		assertNull(handler.sanitizeSessionId(null))
		assertNull(handler.sanitizeSessionId("valid_session-123.4"))

		tempDir.resolve("child.nvrdata").writeBytes(byteArrayOf(1))
		tempDir.resolve("sub").createDirectories()
		tempDir.resolve("sub/child.nvrdata").writeBytes(byteArrayOf(1))
		assertTrue(handler.isContainedInRoot(tempDir.resolve("child.nvrdata"), tempDir))
		assertTrue(handler.isContainedInRoot(tempDir.resolve("sub/child.nvrdata"), tempDir))
		assertFalse(handler.isContainedInRoot(tempDir.resolve("../outside.nvrdata"), tempDir))
	}

	@Test
	fun testStartAndStopLifecycle(@TempDir tempDir: Path) {
		val recorder = DatasetRecordingService(datasetsRoot = tempDir)
		val head = createHeadTracker()
		val imu = createImuTracker(1, TrackerPosition.WAIST)
		val trackers = listOf(head, imu)

		val handler = RPCDatasetHandler(
			datasetRecorder = recorder,
			trackerProvider = { trackers },
			taskQueue = { it.run() },
		)

		val conn = TestConnection()

		// 1. Start recording
		val fbb1 = FlatBufferBuilder(64)
		val startReq = StartDatasetRecordingRequest.createStartDatasetRecordingRequest(
			fbb1,
			fbb1.createString("rpc-session-1"),
			0,
			true,
			fbb1.createString("pilot"),
			true,
		)
		val startHeader = createHeader(fbb1, RpcMessage.StartDatasetRecordingRequest, startReq)
		handler.onStartDatasetRecordingRequest(conn, startHeader)

		val resp1 = conn.lastRpcHeader().message(DatasetRecordingStatusResponse()) as DatasetRecordingStatusResponse
		assertEquals(DatasetRecordingState.RECORDING, resp1.state())
		assertEquals(DatasetOperation.START, resp1.operation())
		assertEquals(RecordingState.RECORDING, recorder.status().state)

		// 2. Poll status
		val fbbStatus = FlatBufferBuilder(64)
		DatasetRecordingStatusRequest.startDatasetRecordingStatusRequest(fbbStatus)
		val statusReq = DatasetRecordingStatusRequest.endDatasetRecordingStatusRequest(fbbStatus)
		val statusHeader = createHeader(fbbStatus, RpcMessage.DatasetRecordingStatusRequest, statusReq)
		handler.onDatasetRecordingStatusRequest(conn, statusHeader)

		val respStatus = conn.lastRpcHeader().message(DatasetRecordingStatusResponse()) as DatasetRecordingStatusResponse
		assertEquals(DatasetRecordingState.RECORDING, respStatus.state())
		assertEquals(42L, conn.lastRpcHeader().txId()!!.id())
		val assignedSessionId = recorder.status().sessionId
		assertNotNull(assignedSessionId)
		assertEquals(assignedSessionId, respStatus.sessionId())
		assertEquals(DatasetOperation.STATUS, respStatus.operation())

		val mismatchBuilder = FlatBufferBuilder(64)
		val wrongId = mismatchBuilder.createString("wrong-session")
		val mismatchRequest = StopDatasetRecordingRequest.createStopDatasetRecordingRequest(mismatchBuilder, 10L, wrongId)
		handler.onStopDatasetRecordingRequest(conn, createHeader(mismatchBuilder, RpcMessage.StopDatasetRecordingRequest, mismatchRequest, 77L))
		val mismatchResponse = conn.lastRpcHeader().message(DatasetRecordingStatusResponse()) as DatasetRecordingStatusResponse
		assertEquals(DatasetErrorCode.SESSION_MISMATCH, mismatchResponse.errorCode())
		assertEquals(77L, conn.lastRpcHeader().txId()!!.id())
		assertEquals(RecordingState.RECORDING, recorder.status().state)

		// 3. Stop recording
		val fbb2 = FlatBufferBuilder(64)
		val stopReq = StopDatasetRecordingRequest.createStopDatasetRecordingRequest(fbb2, 10L, 0)
		val stopHeader = createHeader(fbb2, RpcMessage.StopDatasetRecordingRequest, stopReq)
		handler.onStopDatasetRecordingRequest(conn, stopHeader)

		val resp2 = conn.lastRpcHeader().message(DatasetRecordingStatusResponse()) as DatasetRecordingStatusResponse
		assertEquals(DatasetRecordingState.FINALIZING, resp2.state())
		assertEquals(DatasetOperation.STOP_FINALIZE, resp2.operation())
		waitUntil { recorder.status().state == RecordingState.COMPLETED }

		val archive = tempDir.resolve("$assignedSessionId.nvrdata")
		assertTrue(archive.exists())
	}

	@Test
	fun testDuplicateStartRejection(@TempDir tempDir: Path) {
		val recorder = DatasetRecordingService(datasetsRoot = tempDir)
		val head = createHeadTracker()
		val imu = createImuTracker(1, TrackerPosition.WAIST)
		val trackers = listOf(head, imu)

		val handler = RPCDatasetHandler(
			datasetRecorder = recorder,
			trackerProvider = { trackers },
			taskQueue = { it.run() },
		)

		val conn = TestConnection()

		val fbb1 = FlatBufferBuilder(64)
		val startReq1 = StartDatasetRecordingRequest.createStartDatasetRecordingRequest(
			fbb1,
			fbb1.createString("session-orig"),
			0,
			true,
			fbb1.createString("subj"),
			true,
		)
		handler.onStartDatasetRecordingRequest(conn, createHeader(fbb1, RpcMessage.StartDatasetRecordingRequest, startReq1))
		assertEquals(RecordingState.RECORDING, recorder.status().state)
		val initialSessionId = recorder.status().sessionId
		assertNotNull(initialSessionId)

		// Attempt duplicate start
		val fbb2 = FlatBufferBuilder(64)
		val startReq2 = StartDatasetRecordingRequest.createStartDatasetRecordingRequest(
			fbb2,
			fbb2.createString("session-dup"),
			0,
			true,
			fbb2.createString("subj"),
			true,
		)
		handler.onStartDatasetRecordingRequest(conn, createHeader(fbb2, RpcMessage.StartDatasetRecordingRequest, startReq2))

		// Active session remains initialSessionId
		assertEquals(initialSessionId, recorder.status().sessionId)
		assertEquals(RecordingState.RECORDING, recorder.status().state)

		recorder.stopAndFinalize(10L)
	}

	@Test
	fun testListAndValidateSessions(@TempDir tempDir: Path) {
		var timeNs = 1_000_000_000L
		val recorder = DatasetRecordingService(
			datasetsRoot = tempDir,
			clockNs = { timeNs },
		)
		val head = createHeadTracker()
		val imu = createImuTracker(1, TrackerPosition.CHEST)
		val trackers = listOf(head, imu)

		val handler = RPCDatasetHandler(
			datasetRecorder = recorder,
			trackerProvider = { trackers },
			taskQueue = { it.run() },
		)

		// Record a short session
		recorder.startRecording(
			RecordingRequest(
				profile = CollectionProfile.STANDARD,
				privacy = SessionPrivacyOptions(consent = true),
			),
			trackers,
		)
		recorder.sampleIfDue(trackers)
		val archive = recorder.stopAndFinalize(10L)
		val sId = archive.fileName.toString().removeSuffix(".nvrdata")

		val conn = TestConnection()

		// 1. List Request
		val fbbList = FlatBufferBuilder(64)
		DatasetListRequest.startDatasetListRequest(fbbList)
		val listReq = DatasetListRequest.endDatasetListRequest(fbbList)
		handler.onDatasetListRequest(conn, createHeader(fbbList, RpcMessage.DatasetListRequest, listReq))

		val listHeader = conn.lastRpcHeader()
		assertEquals(RpcMessage.DatasetListResponse, listHeader.messageType())
		val listResp = listHeader.message(DatasetListResponse()) as DatasetListResponse
		assertEquals(1, listResp.sessionsLength())
		val sessionInfo = listResp.sessions(0)!!
		assertEquals(sId, sessionInfo.sessionId())
		assertTrue(sessionInfo.isValid())

		// 2. Validate Request (valid session)
		val fbbVal = FlatBufferBuilder(64)
		val valReq = DatasetValidateRequest.createDatasetValidateRequest(fbbVal, fbbVal.createString(sId))
		handler.onDatasetValidateRequest(conn, createHeader(fbbVal, RpcMessage.DatasetValidateRequest, valReq))

		val valHeader = conn.lastRpcHeader()
		assertEquals(RpcMessage.DatasetValidateResponse, valHeader.messageType())
		val valResp = valHeader.message(DatasetValidateResponse()) as DatasetValidateResponse
		assertEquals(sId, valResp.sessionId())
		assertTrue(valResp.valid())

		// 3. Validate Request with path traversal
		val fbbBad = FlatBufferBuilder(64)
		val badReq = DatasetValidateRequest.createDatasetValidateRequest(fbbBad, fbbBad.createString("../escaped"))
		handler.onDatasetValidateRequest(conn, createHeader(fbbBad, RpcMessage.DatasetValidateRequest, badReq))

		val badResp = conn.lastRpcHeader().message(DatasetValidateResponse()) as DatasetValidateResponse
		assertFalse(badResp.valid())
	}

	@Test
	fun testExportAndDeleteSession(@TempDir tempDir: Path) {
		val recorder = DatasetRecordingService(datasetsRoot = tempDir)
		val head = createHeadTracker()
		val imu = createImuTracker(1, TrackerPosition.WAIST)
		val trackers = listOf(head, imu)

		val handler = RPCDatasetHandler(
			datasetRecorder = recorder,
			trackerProvider = { trackers },
			taskQueue = { it.run() },
		)

		recorder.startRecording(RecordingRequest(privacy = SessionPrivacyOptions(consent = true)), trackers)
		val archive = recorder.stopAndFinalize(10L)
		val sId = archive.fileName.toString().removeSuffix(".nvrdata")

		val conn = TestConnection()
		val exportTarget = tempDir.resolve("export_folder/exported.nvrdata")

		// 1. Export Request
		val fbbExport = FlatBufferBuilder(64)
		val expReq = DatasetExportRequest.createDatasetExportRequest(
			fbbExport,
			fbbExport.createString(sId),
			fbbExport.createString(exportTarget.toString()),
		)
		handler.onDatasetExportRequest(conn, createHeader(fbbExport, RpcMessage.DatasetExportRequest, expReq))

		val expResp = conn.lastRpcHeader().message(DatasetActionResponse()) as DatasetActionResponse
		assertFalse(expResp.success())
		assertEquals(DatasetErrorCode.PATH_REJECTED, expResp.errorCode())
		assertFalse(exportTarget.exists())

		val fbbSafeExport = FlatBufferBuilder(64)
		val safeExportReq = DatasetExportRequest.createDatasetExportRequest(
			fbbSafeExport,
			fbbSafeExport.createString(sId),
			fbbSafeExport.createString(""),
		)
		handler.onDatasetExportRequest(conn, createHeader(fbbSafeExport, RpcMessage.DatasetExportRequest, safeExportReq))
		val safeExportResp = conn.lastRpcHeader().message(DatasetActionResponse()) as DatasetActionResponse
		assertTrue(safeExportResp.success())
		assertEquals(DatasetOperation.EXPORT, safeExportResp.operation())
		assertEquals("", safeExportResp.path())

		// 2. Delete Request with path traversal is rejected
		val fbbBadDel = FlatBufferBuilder(64)
		val badDelReq = DatasetDeleteRequest.createDatasetDeleteRequest(fbbBadDel, fbbBadDel.createString("../../system"))
		handler.onDatasetDeleteRequest(conn, createHeader(fbbBadDel, RpcMessage.DatasetDeleteRequest, badDelReq))
		val badDelResp = conn.lastRpcHeader().message(DatasetActionResponse()) as DatasetActionResponse
		assertFalse(badDelResp.success())
		assertTrue(archive.exists())

		// 3. Delete Request on actual session
		val fbbDel = FlatBufferBuilder(64)
		val delReq = DatasetDeleteRequest.createDatasetDeleteRequest(fbbDel, fbbDel.createString(sId))
		handler.onDatasetDeleteRequest(conn, createHeader(fbbDel, RpcMessage.DatasetDeleteRequest, delReq))
		val delResp = conn.lastRpcHeader().message(DatasetActionResponse()) as DatasetActionResponse
		assertTrue(delResp.success())
		assertFalse(archive.exists())
	}

	@Test
	fun testRecoverPartialSession(@TempDir tempDir: Path) {
		val recorder = DatasetRecordingService(datasetsRoot = tempDir)
		val handler = RPCDatasetHandler(datasetRecorder = recorder)

		// Create an unfinalized partial session directory
		val partialDir = tempDir.resolve("unfinalized-session-99.partial")
		partialDir.createDirectories()
		partialDir.resolve(DatasetRecordingService.TELEMETRY_PARTIAL).writeBytes(ByteArray(128))

		val conn = TestConnection()

		// List discovers recoverable session
		val fbbList = FlatBufferBuilder(64)
		DatasetListRequest.startDatasetListRequest(fbbList)
		val listReq = DatasetListRequest.endDatasetListRequest(fbbList)
		handler.onDatasetListRequest(conn, createHeader(fbbList, RpcMessage.DatasetListRequest, listReq))
		val listResp = conn.lastRpcHeader().message(DatasetListResponse()) as DatasetListResponse
		assertEquals(1, listResp.recoverableLength())
		val recInfo = listResp.recoverable(0)!!
		assertEquals("unfinalized-session-99", recInfo.sessionId())

		// Quarantine the session
		val fbbRec = FlatBufferBuilder(64)
		val recReq = DatasetRecoverRequest.createDatasetRecoverRequest(fbbRec, fbbRec.createString("unfinalized-session-99"), true, DatasetRecoveryAction.QUARANTINE)
		handler.onDatasetRecoverRequest(conn, createHeader(fbbRec, RpcMessage.DatasetRecoverRequest, recReq))
		waitUntil { !partialDir.exists() }
		val recResp = conn.lastRpcHeader().message(DatasetActionResponse()) as DatasetActionResponse
		assertTrue(recResp.success())
		assertFalse(partialDir.exists())
		assertEquals("", recResp.path())
	}

	@Test
	fun testMultiClientIsolationAndStateSync(@TempDir tempDir: Path) {
		val recorder = DatasetRecordingService(datasetsRoot = tempDir)
		val head = createHeadTracker()
		val imu = createImuTracker(1, TrackerPosition.WAIST)
		val trackers = listOf(head, imu)

		val handler = RPCDatasetHandler(
			datasetRecorder = recorder,
			trackerProvider = { trackers },
			taskQueue = { it.run() },
		)

		val clientA = TestConnection()
		val clientB = TestConnection()

		// Client A starts recording
		val fbbStart = FlatBufferBuilder(64)
		val startReq = StartDatasetRecordingRequest.createStartDatasetRecordingRequest(
			fbbStart,
			fbbStart.createString("sync-session"),
			0,
			true,
			fbbStart.createString(""),
			true,
		)
		handler.onStartDatasetRecordingRequest(clientA, createHeader(fbbStart, RpcMessage.StartDatasetRecordingRequest, startReq))

		// Client B reconnects/polls status
		val fbbStatus = FlatBufferBuilder(64)
		DatasetRecordingStatusRequest.startDatasetRecordingStatusRequest(fbbStatus)
		val statusReq = DatasetRecordingStatusRequest.endDatasetRecordingStatusRequest(fbbStatus)
		handler.onDatasetRecordingStatusRequest(clientB, createHeader(fbbStatus, RpcMessage.DatasetRecordingStatusRequest, statusReq))

		val respB = clientB.lastRpcHeader().message(DatasetRecordingStatusResponse()) as DatasetRecordingStatusResponse
		assertEquals(DatasetRecordingState.RECORDING, respB.state())
		val assignedSessionId = recorder.status().sessionId
		assertNotNull(assignedSessionId)
		assertEquals(assignedSessionId, respB.sessionId())

		recorder.stopAndFinalize(10L)
	}

	@Test
	fun testCancelRecording(@TempDir tempDir: Path) {
		val recorder = DatasetRecordingService(datasetsRoot = tempDir)
		val head = createHeadTracker()
		val imu = createImuTracker(1, TrackerPosition.WAIST)
		val trackers = listOf(head, imu)

		val handler = RPCDatasetHandler(
			datasetRecorder = recorder,
			trackerProvider = { trackers },
			taskQueue = { it.run() },
		)

		val conn = TestConnection()

		// Start recording
		val fbbStart = FlatBufferBuilder(64)
		val startReq = StartDatasetRecordingRequest.createStartDatasetRecordingRequest(
			fbbStart,
			fbbStart.createString("session-to-cancel"),
			0,
			true,
			fbbStart.createString(""),
			true,
		)
		handler.onStartDatasetRecordingRequest(conn, createHeader(fbbStart, RpcMessage.StartDatasetRecordingRequest, startReq))
		val assignedId = recorder.status().sessionId
		assertNotNull(assignedId)
		assertEquals(RecordingState.RECORDING, recorder.status().state)

		// Cancel recording
		val fbbCancel = FlatBufferBuilder(64)
		val cancelReq = CancelDatasetRecordingRequest.createCancelDatasetRecordingRequest(
			fbbCancel,
			fbbCancel.createString(assignedId),
		)
		handler.onCancelDatasetRecordingRequest(conn, createHeader(fbbCancel, RpcMessage.CancelDatasetRecordingRequest, cancelReq))

		waitUntil { recorder.status().state == RecordingState.CANCELLED }
		assertFalse(recorder.isRecording)
		// Cancelled archive should be discarded/deleted
		val archive = tempDir.resolve("$assignedId.nvrdata")
		assertFalse(archive.exists())
	}

	@Test
	fun testRevealRequest(@TempDir tempDir: Path) {
		val recorder = DatasetRecordingService(datasetsRoot = tempDir)
		val head = createHeadTracker()
		val imu = createImuTracker(1, TrackerPosition.WAIST)
		val trackers = listOf(head, imu)

		val handler = RPCDatasetHandler(
			datasetRecorder = recorder,
			trackerProvider = { trackers },
			taskQueue = { it.run() },
		)

		recorder.startRecording(RecordingRequest(privacy = SessionPrivacyOptions(consent = true)), trackers)
		val archive = recorder.stopAndFinalize(10L)
		val sId = archive.fileName.toString().removeSuffix(".nvrdata")

		val conn = TestConnection()

		// 1. Valid reveal request
		val fbbRev = FlatBufferBuilder(64)
		val revReq = DatasetRevealRequest.createDatasetRevealRequest(fbbRev, fbbRev.createString(sId))
		handler.onDatasetRevealRequest(conn, createHeader(fbbRev, RpcMessage.DatasetRevealRequest, revReq))

		val revResp = conn.lastRpcHeader().message(DatasetActionResponse()) as DatasetActionResponse
		assertTrue(revResp.success())
		assertEquals("", revResp.path())

		// 2. Traversal reveal request is rejected
		val fbbBad = FlatBufferBuilder(64)
		val badReq = DatasetRevealRequest.createDatasetRevealRequest(fbbBad, fbbBad.createString("../../system"))
		handler.onDatasetRevealRequest(conn, createHeader(fbbBad, RpcMessage.DatasetRevealRequest, badReq))

		val badResp = conn.lastRpcHeader().message(DatasetActionResponse()) as DatasetActionResponse
		assertFalse(badResp.success())
	}

	@Test
	fun testMultiClientAuthoritativeBroadcast(@TempDir tempDir: Path) {
		val recorder = DatasetRecordingService(datasetsRoot = tempDir)
		val head = createHeadTracker()
		val imu = createImuTracker(1, TrackerPosition.WAIST)
		val trackers = listOf(head, imu)

		val clientA = TestConnection()
		val clientB = TestConnection()
		val allClients = listOf(clientA, clientB)

		val handler = RPCDatasetHandler(
			datasetRecorder = recorder,
			trackerProvider = { trackers },
			taskQueue = { it.run() },
			broadcaster = { action -> allClients.forEach(action) },
		)

		// Client A starts recording
		val fbbStart = FlatBufferBuilder(64)
		val startReq = StartDatasetRecordingRequest.createStartDatasetRecordingRequest(
			fbbStart,
			fbbStart.createString("broadcast-session"),
			0,
			true,
			fbbStart.createString(""),
			true,
		)
		handler.onStartDatasetRecordingRequest(clientA, createHeader(fbbStart, RpcMessage.StartDatasetRecordingRequest, startReq))

		// Client B should have received the broadcast DatasetRecordingStatusResponse!
		assertTrue(clientB.responses.isNotEmpty())
		val broadcastResp = clientB.lastRpcHeader().message(DatasetRecordingStatusResponse()) as DatasetRecordingStatusResponse
		assertEquals(DatasetRecordingState.RECORDING, broadcastResp.state())
		assertEquals(recorder.status().sessionId, broadcastResp.sessionId())

		// Stop recording
		val fbbStop = FlatBufferBuilder(64)
		val stopReq = StopDatasetRecordingRequest.createStopDatasetRecordingRequest(fbbStop, 10L, 0)
		handler.onStopDatasetRecordingRequest(clientA, createHeader(fbbStop, RpcMessage.StopDatasetRecordingRequest, stopReq))

		// Client B should have received the stop broadcast!
		waitUntil {
			recorder.status().state == RecordingState.COMPLETED && runCatching {
				(clientB.lastRpcHeader().message(DatasetRecordingStatusResponse()) as DatasetRecordingStatusResponse).state() == DatasetRecordingState.COMPLETED
			}.getOrDefault(false)
		}
		val stopBroadcastResp = clientB.lastRpcHeader().message(DatasetRecordingStatusResponse()) as DatasetRecordingStatusResponse
		assertEquals(DatasetRecordingState.COMPLETED, stopBroadcastResp.state())
		val broadcasts = clientB.responses.map {
			val bundle = MessageBundle.getRootAsMessageBundle(it.duplicate())
			bundle.rpcMsgs(0).message(DatasetRecordingStatusResponse()) as DatasetRecordingStatusResponse
		}
		val orderedStates = broadcasts.map { it.state() }
		assertEquals(
			listOf(
				DatasetRecordingState.STARTING,
				DatasetRecordingState.RECORDING,
				DatasetRecordingState.FINALIZING,
				DatasetRecordingState.COMPLETED,
			),
			orderedStates,
		)
		assertTrue(broadcasts.zipWithNext().all { (before, after) -> after.statusVersion() > before.statusVersion() })
	}
}
