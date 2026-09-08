package dev.slimevr.unit

import com.google.flatbuffers.FlatBufferBuilder
import dev.slimevr.ai.AIDriftEngine
import dev.slimevr.ai.ExecutionProviderType
import dev.slimevr.ai.ManagedModelStore
import dev.slimevr.protocol.ConnectionContext
import dev.slimevr.protocol.GenericConnection
import dev.slimevr.protocol.rpc.ai.RPCModelHandler
import solarxr_protocol.MessageBundle
import solarxr_protocol.datatypes.TransactionId
import solarxr_protocol.rpc.*
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelRPCHandlerTests {
	@Test
	fun `configuration and runtime status use typed correlated RPC responses`() {
		val root = java.nio.file.Files.createTempDirectory("nekovr-model-rpc")
		val engine = unavailableEngine(root)
		try {
			val handler = RPCModelHandler(engine = engine, workerExecutor = Executor(Runnable::run))
			val connection = TestConnection()
			val configBuilder = FlatBufferBuilder(256)
			val configRequest = ModelConfigureRequest.createModelConfigureRequest(
				configBuilder,
				configBuilder.createString("configure-1"),
				RPCModelHandler.ENABLED or RPCModelHandler.PROVIDER or RPCModelHandler.CONTEXT or RPCModelHandler.CONFIDENCE,
				true,
				AIExecutionProvider.CUDA,
				120,
				0.75f,
				0f, 0f, 0f, 0f, 0f,
				AILegacyDriftMode.REPLACE,
				0,
			)
			handler.onConfigureRequest(connection, header(configBuilder, RpcMessage.ModelConfigureRequest, configRequest))

			val actionHeader = connection.lastHeader()
			val action = actionHeader.message(ModelActionResponse()) as ModelActionResponse
			assertEquals(77L, actionHeader.txId().id())
			assertTrue(action.success())
			assertEquals(AIModelOperation.CONFIGURE, action.operation())
			assertEquals("configure-1", action.requestId())
			assertTrue(engine.config.enabled)
			assertEquals(ExecutionProviderType.CUDA, engine.config.requestedProvider)
			assertEquals(120, engine.config.contextFrames)

			val statusBuilder = FlatBufferBuilder(64)
			val statusRequest = ModelRuntimeStatusRequest.createModelRuntimeStatusRequest(statusBuilder, statusBuilder.createString("status-1"))
			handler.onRuntimeStatusRequest(connection, header(statusBuilder, RpcMessage.ModelRuntimeStatusRequest, statusRequest))
			val status = connection.lastHeader().message(ModelRuntimeStatusResponse()) as ModelRuntimeStatusResponse
			assertEquals("status-1", status.requestId())
			assertEquals(AIRuntimeRpcHealth.UNAVAILABLE, status.health())
			assertEquals(AIModelRpcLoadState.RUNTIME_UNAVAILABLE, status.loadState())
			assertNotNull(status.configuration())
			assertTrue(status.configuration().enabled())
			assertEquals(AIExecutionProvider.CUDA, status.configuration().requestedProvider())
			assertEquals(120, status.configuration().contextFrames())
			assertEquals(0.75f, status.configuration().confidenceThreshold())
			assertNotNull(status.metrics())
		} finally {
			engine.close()
			deleteTree(root)
		}
	}

	@Test
	fun `import RPC returns a typed result for a managed copy`() {
		val root = java.nio.file.Files.createTempDirectory("nekovr-model-rpc-import")
		val engine = unavailableEngine(root)
		try {
			val handler = RPCModelHandler(engine = engine, workerExecutor = Executor(Runnable::run))
			val connection = TestConnection()
			val model = resource("probe.onnx")
			val sidecar = resource("probe.onnx.json")
			val hash = ManagedModelStore.sha256(java.nio.file.Files.readAllBytes(model))
			val builder = FlatBufferBuilder(256)
			val request = ModelImportRequest.createModelImportRequest(
				builder,
				builder.createString("import-1"),
				builder.createString(model.toString()),
				builder.createString(sidecar.toString()),
				builder.createString(hash),
			)
			handler.onImportRequest(connection, header(builder, RpcMessage.ModelImportRequest, request))

			val response = connection.lastHeader().message(ModelActionResponse()) as ModelActionResponse
			assertTrue(response.success(), response.error())
			assertEquals(AIModelOperation.IMPORT, response.operation())
			assertEquals(hash, response.modelSha256())
			assertNotNull(engine.modelManager.resolve(hash))
		} finally {
			engine.close()
			deleteTree(root)
		}
	}

	private fun unavailableEngine(root: Path) = AIDriftEngine(
		modelsDir = root.toFile(),
		runtimeFactory = { throw IllegalStateException("runtime intentionally unavailable in RPC contract test") },
	)

	private fun resource(name: String): Path = Path.of(requireNotNull(javaClass.getResource("/dev/slimevr/ai/probe/$name")).toURI())

	private fun header(builder: FlatBufferBuilder, type: Byte, offset: Int): RpcMessageHeader {
		RpcMessageHeader.startRpcMessageHeader(builder)
		RpcMessageHeader.addMessageType(builder, type)
		RpcMessageHeader.addMessage(builder, offset)
		RpcMessageHeader.addTxId(builder, TransactionId.createTransactionId(builder, 77L))
		val header = RpcMessageHeader.endRpcMessageHeader(builder)
		builder.finish(header)
		return RpcMessageHeader.getRootAsRpcMessageHeader(builder.dataBuffer())
	}

	private class TestConnection : GenericConnection {
		override val connectionId: UUID = UUID.randomUUID()
		override val context: ConnectionContext = ConnectionContext()
		private val responses = mutableListOf<ByteBuffer>()

		override fun send(bytes: ByteBuffer) {
			responses += ByteBuffer.allocate(bytes.remaining()).also { copy -> copy.put(bytes.duplicate()); copy.flip() }
		}

		fun lastHeader(): RpcMessageHeader = MessageBundle.getRootAsMessageBundle(responses.last().duplicate()).rpcMsgs(0)
	}

	private fun deleteTree(root: Path) {
		if (!java.nio.file.Files.exists(root)) return
		java.nio.file.Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(java.nio.file.Files::deleteIfExists) }
	}
}
