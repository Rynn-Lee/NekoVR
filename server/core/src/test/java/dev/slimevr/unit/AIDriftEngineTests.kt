package dev.slimevr.unit

import dev.slimevr.ai.AIDriftEngine
import dev.slimevr.ai.ExecutionProviderType
import dev.slimevr.ai.InferenceSnapshot
import dev.slimevr.ai.InferenceTensorBatch
import dev.slimevr.ai.InferenceTensorOutput
import dev.slimevr.ai.InferenceTrackerSample
import dev.slimevr.ai.JavaOnnxRuntimeBackend
import dev.slimevr.ai.LoadedModelSession
import dev.slimevr.ai.ModelArtifactMetadata
import dev.slimevr.ai.ModelArtifactValidator
import dev.slimevr.ai.ModelLoadErrorCode
import dev.slimevr.ai.ModelLoadException
import dev.slimevr.ai.OnnxRuntimeBackend
import dev.slimevr.ai.OnnxRuntimePackage
import dev.slimevr.ai.RuntimeTensorInfo
import dev.slimevr.ai.TrackerSlotMapping
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AIDriftEngineTests {
	@Test
	fun `real packaged CPU runtime validates contract and runs probe before activation`() {
		val (model, sidecar) = probeFiles()
		AIDriftEngine(runtimeFactory = { JavaOnnxRuntimeBackend() }).use { engine ->
			assertNull(engine.currentProvider)
			val result = engine.loadModel(model, sidecar, ExecutionProviderType.CPU)
			assertTrue(result.activated, result.failure?.message)
			assertEquals(ExecutionProviderType.CPU, engine.currentProvider)
			assertEquals("nekovr-provider-probe", engine.activeStatus?.modelId)
			assertEquals("cpu", engine.activeStatus?.runtimeFlavor)
		}
	}

	@Test
	fun `real packaged CPU session executes causal inference tensors`() {
		val (model, sidecar) = probeFiles()
		val metadata = ModelArtifactValidator.validate(model, sidecar)
		JavaOnnxRuntimeBackend().use { backend ->
			backend.createSession(model, ExecutionProviderType.CPU).use { session ->
				val output = session.runInference(
					InferenceTensorBatch(
						time = 2, slots = 2, featureCount = metadata.featureCount,
						features = floatArrayOf(0.25f, -0.5f, 0f, 0f, 0.5f, 0.125f, 0f, 0f),
						roleIds = longArrayOf(1, 0), slotMask = booleanArrayOf(true, false),
						channelValidity = booleanArrayOf(true, true, false, false, true, true, false, false),
						timeDeltasSeconds = floatArrayOf(0.02f, 0.02f), timeMask = booleanArrayOf(true, true),
					),
				)
				assertEquals(6, output.correctionRotationVectors.size)
				assertTrue(output.correctionRotationVectors.all(Float::isFinite))
				assertTrue(output.confidence.all(Float::isFinite))
				assertTrue(output.correctionRotationVectors.copyOfRange(3, 6).all { it == 0f })
			}
		}
	}

	@Test
	fun `reload and unload close superseded model sessions`() {
		val (model, sidecar) = probeFiles()
		val metadata = ModelArtifactValidator.validate(model, sidecar)
		val backend = FakeRuntime(metadata, setOf(ExecutionProviderType.CPU))
		AIDriftEngine(runtimeFactory = { backend }).use { engine ->
			assertTrue(engine.loadModel(model, sidecar, ExecutionProviderType.CPU).activated)
			val first = backend.sessions.single()
			assertTrue(engine.loadModel(model, sidecar, ExecutionProviderType.CPU).activated)
			assertTrue(first.closed)
			val second = backend.sessions.last()
			assertFalse(second.closed)
			engine.unloadModel()
			assertTrue(second.closed)
		}
	}

	@Test
	fun `AUTO tries providers in order and activates only after successful probe`() {
		val (model, sidecar) = probeFiles()
		val metadata = ModelArtifactValidator.validate(model, sidecar)
		val backend = FakeRuntime(metadata, setOf(ExecutionProviderType.TENSORRT, ExecutionProviderType.CUDA, ExecutionProviderType.CPU))
		backend.failures[ExecutionProviderType.TENSORRT] = ModelLoadErrorCode.PROBE_FAILED
		AIDriftEngine(runtimeFactory = { backend }).use { engine ->
			val result = engine.loadModel(model, sidecar, ExecutionProviderType.AUTO)
			assertTrue(result.activated)
			assertEquals(listOf(ExecutionProviderType.TENSORRT, ExecutionProviderType.CUDA), backend.createdProviders)
			assertEquals(ExecutionProviderType.CUDA, result.provider)
			assertEquals(ModelLoadErrorCode.PROBE_FAILED, result.attempts.single().errorCode)
			assertEquals(2, backend.sessions.last().probeRuns)
		}
	}

	@Test
	fun `forced provider failure never falls back and preserves previous active session`() {
		val (model, sidecar) = probeFiles()
		val metadata = ModelArtifactValidator.validate(model, sidecar)
		val backend = FakeRuntime(metadata, ExecutionProviderType.entries.filter { it != ExecutionProviderType.AUTO }.toSet())
		AIDriftEngine(runtimeFactory = { backend }).use { engine ->
			assertTrue(engine.loadModel(model, sidecar, ExecutionProviderType.CPU).activated)
			val active = backend.sessions.single()
			backend.failures[ExecutionProviderType.DIRECTML] = ModelLoadErrorCode.PROVIDER_UNAVAILABLE
			val failed = engine.loadModel(model, sidecar, ExecutionProviderType.DIRECTML)
			assertFalse(failed.activated)
			assertEquals(listOf(ExecutionProviderType.CPU, ExecutionProviderType.DIRECTML), backend.createdProviders)
			assertEquals(ExecutionProviderType.CPU, engine.currentProvider)
			assertFalse(active.closed)
		}
	}

	@Test
	fun `integrity failure is typed and does not create or replace a session`() {
		val (model, sidecar) = probeFiles()
		val metadata = ModelArtifactValidator.validate(model, sidecar)
		val backend = FakeRuntime(metadata, setOf(ExecutionProviderType.CPU))
		val tampered = Files.createTempFile("nekovr-probe-tampered", ".onnx")
		tampered.writeBytes(Files.readAllBytes(model) + byteArrayOf(0))
		AIDriftEngine(runtimeFactory = { backend }).use { engine ->
			assertTrue(engine.loadModel(model, sidecar, ExecutionProviderType.CPU).activated)
			val result = engine.loadModel(tampered, sidecar, ExecutionProviderType.CPU)
			assertFalse(result.activated)
			assertEquals(ModelLoadErrorCode.MODEL_INTEGRITY, result.failure?.code)
			assertEquals(listOf(ExecutionProviderType.CPU), backend.createdProviders)
			assertEquals(ExecutionProviderType.CPU, engine.currentProvider)
		}
		Files.deleteIfExists(tampered)
	}

	@Test
	fun `feature schema and runtime tensor contracts are validated before activation`() {
		val (model, sidecar) = probeFiles()
		val metadata = ModelArtifactValidator.validate(model, sidecar)
		val backend = FakeRuntime(metadata, setOf(ExecutionProviderType.CPU))
		AIDriftEngine(runtimeFactory = { backend }).use { engine ->
			val incompatible = engine.loadModel(model, sidecar, ExecutionProviderType.CPU, "0".repeat(64))
			assertEquals(ModelLoadErrorCode.FEATURE_SCHEMA_MISMATCH, incompatible.failure?.code)
			assertTrue(backend.createdProviders.isEmpty())
		}
		val missingInput = metadata.inputs.drop(1).associate { it.name to RuntimeTensorInfo(it.dtype, it.shape.map { value -> (value as? Number)?.toLong() ?: -1L }.toLongArray()) }
		val outputs = metadata.outputs.associate { it.name to RuntimeTensorInfo(it.dtype, it.shape.map { value -> (value as? Number)?.toLong() ?: -1L }.toLongArray()) }
		val error = assertFailsWith<ModelLoadException> { ModelArtifactValidator.validateTensorContract(metadata, missingInput, outputs) }
		assertEquals(ModelLoadErrorCode.TENSOR_CONTRACT_MISMATCH, error.code)
	}

	@Test
	fun `runtime package advertises only providers physically assigned to its flavor`() {
		assertTrue(OnnxRuntimePackage("cpu", "1.29.0").allows(ExecutionProviderType.CPU))
		assertFalse(OnnxRuntimePackage("cpu", "1.29.0").allows(ExecutionProviderType.CUDA))
		assertTrue(OnnxRuntimePackage("nvidia", "1.29.0").allows(ExecutionProviderType.TENSORRT))
		assertTrue(OnnxRuntimePackage("nvidia", "1.29.0").allows(ExecutionProviderType.CUDA))
		assertTrue(OnnxRuntimePackage("directml", "1.29.0").allows(ExecutionProviderType.DIRECTML))
	}

	@Test
	fun `repeated inference failures trip watchdog and expose typed fail-open health`() {
		val (model, sidecar) = probeFiles()
		val metadata = ModelArtifactValidator.validate(model, sidecar)
		val backend = FakeRuntime(metadata, setOf(ExecutionProviderType.CPU)).apply { inferenceFailure = true }
		val config = dev.slimevr.ai.AIModelConfig().apply {
			enabled = true
			watchdogFailureThreshold = 2
		}
		AIDriftEngine(config = config, runtimeFactory = { backend }).use { engine ->
			assertTrue(engine.loadModel(model, sidecar, ExecutionProviderType.CPU).activated)
			engine.configureMappings(listOf(TrackerSlotMapping(10, 1, 0)))
			fun submit(sequence: Long) = engine.submitInferenceSnapshot(
				InferenceSnapshot(
					sequence,
					System.nanoTime(),
					0.02f,
					listOf(InferenceTrackerSample(10, 0L, floatArrayOf(1f, 1f), booleanArrayOf(true, true))),
				),
			)
			assertTrue(submit(1))
			assertTrue(eventually { engine.runtimeStatus().metrics.processedInferences >= 1L })
			assertTrue(submit(2))
			assertTrue(eventually { engine.runtimeStatus().metrics.inferenceErrors >= 1L })
			assertTrue(submit(3))
			assertTrue(eventually { engine.runtimeStatus().metrics.inferenceErrors >= 2L })

			val correction = engine.correctionFor(10, io.github.axisangles.ktmath.Quaternion.IDENTITY, io.github.axisangles.ktmath.Vector3.NULL, 0L)
			assertFalse(correction.applied)
			assertEquals("WATCHDOG_TRIPPED", correction.rejectionReason)
			assertEquals(dev.slimevr.ai.AIRuntimeHealthState.WATCHDOG_TRIPPED, engine.runtimeStatus().health)
			assertEquals("INFERENCE_ERROR", engine.runtimeStatus().lastError?.code)
		}
	}

	@Test
	fun `runtime status exposes active model provider mapping confidence and worker metrics`() {
		val (model, sidecar) = probeFiles()
		val metadata = ModelArtifactValidator.validate(model, sidecar)
		val backend = FakeRuntime(metadata, setOf(ExecutionProviderType.CPU))
		val config = dev.slimevr.ai.AIModelConfig().apply {
			enabled = true
			confidenceThreshold = 0f
			maximumResultAgeMillis = 1_000L
		}
		AIDriftEngine(config = config, runtimeFactory = { backend }).use { engine ->
			assertTrue(engine.loadModel(model, sidecar, ExecutionProviderType.CPU).activated)
			engine.configureMappings(listOf(TrackerSlotMapping(10, 1, 0)))
			fun submit(sequence: Long) = engine.submitInferenceSnapshot(
				InferenceSnapshot(
					sequence,
					System.nanoTime(),
					0.02f,
					listOf(InferenceTrackerSample(10, 0L, floatArrayOf(1f, 1f), booleanArrayOf(true, true))),
				),
			)
			assertTrue(submit(1))
			assertTrue(eventually { engine.runtimeStatus().metrics.processedInferences >= 1L })
			assertTrue(submit(2))
			assertTrue(eventually { engine.latestInference(10, 0L) != null })
			engine.correctionFor(10, io.github.axisangles.ktmath.Quaternion.IDENTITY, io.github.axisangles.ktmath.Vector3.NULL, 0L)

			val status = engine.runtimeStatus()
			assertEquals(dev.slimevr.ai.AIModelLoadState.ACTIVE, status.loadState)
			assertEquals(dev.slimevr.ai.AIRuntimeHealthState.HEALTHY, status.health)
			assertEquals("nekovr-provider-probe", status.activeModel?.modelId)
			assertEquals(ExecutionProviderType.CPU, status.activeModel?.provider)
			assertTrue(status.metrics.processedInferences >= 2L)
			assertEquals(0f, status.metrics.confidenceMinimum)
			assertEquals(10, status.trackers.single().trackerId)
			assertTrue(status.trackers.single().correctionApplied)
		}
	}

	@Test
	fun `shadow mode observes accepted predictions but never changes tracking output`() {
		val (model, sidecar) = probeFiles()
		val metadata = ModelArtifactValidator.validate(model, sidecar)
		val backend = FakeRuntime(metadata, setOf(ExecutionProviderType.CPU))
		val config = dev.slimevr.ai.AIModelConfig().apply {
			enabled = true
			shadowMode = true
			confidenceThreshold = 0f
			maximumResultAgeMillis = 1_000L
		}
		AIDriftEngine(config = config, runtimeFactory = { backend }).use { engine ->
			assertTrue(engine.loadModel(model, sidecar, ExecutionProviderType.CPU).activated)
			engine.configureMappings(listOf(TrackerSlotMapping(10, 1, 0)))
			assertEquals(dev.slimevr.ai.LegacyDriftCompensationMode.COMPOSE, engine.legacyDriftCompensationMode(10))
				repeat(2) { sequence ->
				assertTrue(
					engine.submitInferenceSnapshot(
						InferenceSnapshot(
							sequence.toLong(), System.nanoTime(), 0.02f,
							listOf(InferenceTrackerSample(10, 7L, floatArrayOf(1f, 1f), booleanArrayOf(true, true))),
						),
					),
				)
				assertTrue(eventually { engine.runtimeStatus().metrics.processedInferences >= sequence + 1L })
			}
			assertTrue(eventually { engine.latestInference(10, 7L) != null })

			val result = engine.correctionFor(10, io.github.axisangles.ktmath.Quaternion.IDENTITY, io.github.axisangles.ktmath.Vector3.NULL, 7L)
			assertFalse(result.applied)
			assertEquals(io.github.axisangles.ktmath.Quaternion.IDENTITY, result.correction)
			assertEquals("SHADOW_MODE", result.rejectionReason)
			assertTrue(result.historyValid)
			assertFalse(engine.runtimeStatus().trackers.single().correctionApplied)

			engine.resetHistory(10, 8L)
			val afterReset = engine.correctionFor(10, io.github.axisangles.ktmath.Quaternion.IDENTITY, io.github.axisangles.ktmath.Vector3.NULL, 8L)
			assertFalse(afterReset.applied)
			assertEquals(io.github.axisangles.ktmath.Quaternion.IDENTITY, afterReset.correction)
		}
	}

	@Test
	fun `active correction is dynamically gated and rollback is immediately identity`() {
		val (model, sidecar) = probeFiles()
		val metadata = ModelArtifactValidator.validate(model, sidecar)
		val enabledByGate = AtomicBoolean(false)
		val config = dev.slimevr.ai.AIModelConfig().apply {
			enabled = true
			confidenceThreshold = 0f
			maximumResultAgeMillis = 1_000L
		}
		AIDriftEngine(
			config = config,
			runtimeFactory = { FakeRuntime(metadata, setOf(ExecutionProviderType.CPU)) },
			activeCorrectionAuthorization = {
				if (enabledByGate.get()) dev.slimevr.ai.ActiveCorrectionAuthorization(true, null)
				else dev.slimevr.ai.ActiveCorrectionAuthorization(false, "INFERENCE_READY_GATE_PENDING")
			},
		).use { engine ->
			assertTrue(engine.loadModel(model, sidecar, ExecutionProviderType.CPU).activated)
			engine.configureMappings(listOf(TrackerSlotMapping(10, 1, 0)))
			repeat(2) { sequence ->
				assertTrue(engine.submitInferenceSnapshot(InferenceSnapshot(sequence.toLong(), System.nanoTime(), 0.02f, listOf(InferenceTrackerSample(10, 0L, floatArrayOf(1f, 1f), booleanArrayOf(true, true))))))
				assertTrue(eventually { engine.runtimeStatus().metrics.processedInferences >= sequence + 1L })
			}
			assertTrue(eventually { engine.latestInference(10, 0L) != null })

			val gated = engine.correctionFor(10, io.github.axisangles.ktmath.Quaternion.IDENTITY, io.github.axisangles.ktmath.Vector3.NULL, 0L)
			assertFalse(gated.applied)
			assertEquals("INFERENCE_READY_GATE_PENDING", gated.rejectionReason)
			assertEquals(dev.slimevr.ai.LegacyDriftCompensationMode.COMPOSE, engine.legacyDriftCompensationMode(10))

			enabledByGate.set(true)
			val active = engine.correctionFor(10, io.github.axisangles.ktmath.Quaternion.IDENTITY, io.github.axisangles.ktmath.Vector3.NULL, 0L)
			assertTrue(active.applied)
			enabledByGate.set(false)
			val disabled = engine.correctionFor(10, io.github.axisangles.ktmath.Quaternion.IDENTITY, io.github.axisangles.ktmath.Vector3.NULL, 0L)
			assertFalse(disabled.applied)
			assertEquals(io.github.axisangles.ktmath.Quaternion.IDENTITY, disabled.correction)

			enabledByGate.set(true)
			engine.rollbackToIdentity()
			assertFalse(config.enabled)
			val rolledBack = engine.correctionFor(10, io.github.axisangles.ktmath.Quaternion.IDENTITY, io.github.axisangles.ktmath.Vector3.NULL, 0L)
			assertFalse(rolledBack.applied)
			assertEquals(io.github.axisangles.ktmath.Quaternion.IDENTITY, rolledBack.correction)
		}
	}

	private fun eventually(predicate: () -> Boolean): Boolean {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
		while (System.nanoTime() < deadline) {
			if (predicate()) return true
			Thread.yield()
		}
		return predicate()
	}

	private fun probeFiles(): Pair<Path, Path> {
		fun resource(name: String): Path = Path.of(requireNotNull(javaClass.getResource("/dev/slimevr/ai/probe/$name")).toURI())
		return resource("probe.onnx") to resource("probe.onnx.json")
	}

	private class FakeRuntime(
		metadata: ModelArtifactMetadata,
		override val availableProviders: Set<ExecutionProviderType>,
	) : OnnxRuntimeBackend {
		override val runtimePackage = OnnxRuntimePackage("nvidia", "fixture")
		val failures = mutableMapOf<ExecutionProviderType, ModelLoadErrorCode>()
		val createdProviders = mutableListOf<ExecutionProviderType>()
		val sessions = mutableListOf<FakeSession>()
		var inferenceFailure = false
		private val inputs = metadata.inputs.associate { it.name to it.runtimeInfo() }
		private val outputs = metadata.outputs.associate { it.name to it.runtimeInfo() }

		override fun createSession(modelPath: Path, provider: ExecutionProviderType): LoadedModelSession {
			createdProviders += provider
			if (provider !in availableProviders) throw ModelLoadException(ModelLoadErrorCode.PROVIDER_UNAVAILABLE, "$provider unavailable")
			val session = FakeSession(inputs, outputs, failures[provider]) { inferenceFailure }
			sessions += session
			return session
		}

		override fun close() = Unit

		private fun dev.slimevr.ai.ModelTensorContract.runtimeInfo() = RuntimeTensorInfo(
			dtype,
			shape.map { (it as? Number)?.toLong() ?: -1L }.toLongArray(),
		)
	}

	private class FakeSession(
		override val inputInfo: Map<String, RuntimeTensorInfo>,
		override val outputInfo: Map<String, RuntimeTensorInfo>,
		private val failure: ModelLoadErrorCode?,
		private val inferenceFailure: () -> Boolean,
	) : LoadedModelSession {
		var probeRuns = 0
		var closed = false

		override fun runProbe(metadata: ModelArtifactMetadata) {
			probeRuns++
			if (failure != null) throw ModelLoadException(failure, "probe failed")
		}

		override fun runInference(input: InferenceTensorBatch): InferenceTensorOutput {
			if (inferenceFailure()) throw IllegalStateException("fixture inference failure")
			return InferenceTensorOutput(FloatArray(input.slots * 3), FloatArray(input.slots), FloatArray(input.slots))
		}

		override fun close() {
			closed = true
		}
	}
}
