package dev.slimevr.unit

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.flatbuffers.FlatBufferBuilder
import dev.slimevr.ai.*
import dev.slimevr.protocol.ConnectionContext
import dev.slimevr.protocol.GenericConnection
import dev.slimevr.protocol.rpc.ai.RPCModelHandler
import solarxr_protocol.MessageBundle
import solarxr_protocol.datatypes.TransactionId
import solarxr_protocol.rpc.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelSwitchRPCHandlerTests {
	@Test
	fun `quick switch and one-action rollback are transactional`() {
		val root = Files.createTempDirectory("nekovr-switch-rpc")
		val sources = Files.createTempDirectory("nekovr-switch-rpc-sources")
		val probeModel = resource("probe.onnx")
		val probeSidecar = resource("probe.onnx.json")
		val metadata = ModelArtifactValidator.validate(probeModel, probeSidecar)
		val engine = AIDriftEngine(modelsDir = root.toFile(), runtimeFactory = { FakeRuntime(metadata) })
		try {
			engine.config.contextFrames = 2
			engine.configureMappings(listOf(TrackerSlotMapping(7, 1, 0)))
			val first = importVariant(engine.modelManager, sources, "first")
			val second = importVariant(engine.modelManager, sources, "second")
			val handler = RPCModelHandler(engine = engine, workerExecutor = Executor(Runnable::run))
			val connection = TestConnection()

			handler.onSwitchRequest(connection, requestHeader(RpcMessage.ModelSwitchRequest) { builder ->
				ModelSwitchRequest.createModelSwitchRequest(builder, builder.createString("switch-1"), builder.createString(first.metadata.modelSha256), AIExecutionProvider.CPU)
			})
			assertTrue(connection.lastAction().success())
			assertEquals(first.metadata.modelSha256, engine.activeStatus?.modelSha256)

			handler.onSwitchRequest(connection, requestHeader(RpcMessage.ModelSwitchRequest) { builder ->
				ModelSwitchRequest.createModelSwitchRequest(builder, builder.createString("switch-2"), builder.createString(second.metadata.modelSha256), AIExecutionProvider.CPU)
			})
			assertEquals(second.metadata.modelSha256, engine.activeStatus?.modelSha256)

			handler.onRollbackRequest(connection, requestHeader(RpcMessage.ModelRollbackRequest) { builder ->
				ModelRollbackRequest.createModelRollbackRequest(builder, builder.createString("rollback-1"), AIExecutionProvider.CPU)
			})
			val response = connection.lastAction()
			assertTrue(response.success(), response.error())
			assertEquals(AIModelOperation.ROLLBACK, response.operation())
			assertEquals(first.metadata.modelSha256, engine.activeStatus?.modelSha256)
		} finally {
			engine.close(); deleteTree(root); deleteTree(sources)
		}
	}

	private fun importVariant(manager: RemoteModelManager, sources: Path, suffix: String): ManagedModelArtifact {
		val bytes = Files.readAllBytes(resource("probe.onnx")) + suffix.toByteArray()
		val hash = ManagedModelStore.sha256(bytes)
		val model = sources.resolve("$suffix.onnx"); Files.write(model, bytes)
		val sidecar = sources.resolve("$suffix.json")
		val json = ObjectMapper().readTree(resource("probe.onnx.json").toFile()) as com.fasterxml.jackson.databind.node.ObjectNode
		json.put("model_id", suffix); json.put("model_sha256", hash); json.put("model_size_bytes", bytes.size)
		ObjectMapper().writeValue(sidecar.toFile(), json)
		return manager.importLocal(model, sidecar, hash)
	}

	private fun requestHeader(type: Byte, create: (FlatBufferBuilder) -> Int): RpcMessageHeader {
		val builder = FlatBufferBuilder(256); val offset = create(builder)
		RpcMessageHeader.startRpcMessageHeader(builder); RpcMessageHeader.addMessageType(builder, type); RpcMessageHeader.addMessage(builder, offset)
		RpcMessageHeader.addTxId(builder, TransactionId.createTransactionId(builder, 91L)); builder.finish(RpcMessageHeader.endRpcMessageHeader(builder))
		return RpcMessageHeader.getRootAsRpcMessageHeader(builder.dataBuffer())
	}

	private class TestConnection : GenericConnection {
		override val connectionId = UUID.randomUUID(); override val context = ConnectionContext(); private val responses = mutableListOf<ByteBuffer>()
		override fun send(bytes: ByteBuffer) { responses += ByteBuffer.allocate(bytes.remaining()).also { it.put(bytes.duplicate()); it.flip() } }
		fun lastAction() = MessageBundle.getRootAsMessageBundle(responses.last().duplicate()).rpcMsgs(0).message(ModelActionResponse()) as ModelActionResponse
	}

	private class FakeRuntime(metadata: ModelArtifactMetadata) : OnnxRuntimeBackend {
		override val runtimePackage = OnnxRuntimePackage("cpu", "fixture")
		override val availableProviders = setOf(ExecutionProviderType.CPU)
		private val inputs = metadata.inputs.associate { it.name to RuntimeTensorInfo(it.dtype, it.shape.map { dimension -> (dimension as? Number)?.toLong() ?: -1 }.toLongArray()) }
		private val outputs = metadata.outputs.associate { it.name to RuntimeTensorInfo(it.dtype, it.shape.map { dimension -> (dimension as? Number)?.toLong() ?: -1 }.toLongArray()) }
		override fun createSession(modelPath: Path, provider: ExecutionProviderType): LoadedModelSession = object : LoadedModelSession {
			override val inputInfo = inputs; override val outputInfo = outputs
			override fun runProbe(metadata: ModelArtifactMetadata) = Unit
			override fun runInference(input: InferenceTensorBatch) = InferenceTensorOutput(FloatArray(input.slots * 3), FloatArray(input.slots), FloatArray(input.slots))
			override fun close() = Unit
		}
		override fun close() = Unit
	}

	private fun resource(name: String): Path = Path.of(requireNotNull(javaClass.getResource("/dev/slimevr/ai/probe/$name")).toURI())
	private fun deleteTree(root: Path) { if (Files.exists(root)) Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
}
