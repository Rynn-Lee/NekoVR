package dev.slimevr.unit

import dev.slimevr.ai.InferenceSnapshot
import dev.slimevr.ai.InferenceTensorBatch
import dev.slimevr.ai.InferenceTensorOutput
import dev.slimevr.ai.InferenceTrackerSample
import dev.slimevr.ai.LatestValueInferenceWorker
import dev.slimevr.ai.LoadedModelSession
import dev.slimevr.ai.ModelArtifactMetadata
import dev.slimevr.ai.ModelArtifactValidator
import dev.slimevr.ai.RuntimeTensorInfo
import dev.slimevr.ai.TrackerSlotMapping
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InferenceWorkerTests {
	@Test
	fun `worker builds normalized causal tensors with stable slots masks and epochs`() {
		val metadata = probeMetadata().copy(
			normalizationMean = floatArrayOf(1f, 10f),
			normalizationStandardDeviation = floatArrayOf(2f, 5f),
		)
		val session = RecordingSession(expectedRuns = 2)
		val worker = LatestValueInferenceWorker(
			session,
			metadata,
			listOf(TrackerSlotMapping(20, 2, 1), TrackerSlotMapping(10, 1, 0)),
		)
		worker.use {
			assertTrue(worker.submit(snapshot(1, sample(10, 0, 3f, 15f), sample(20, 0, 5f, 20f))))
			assertTrue(eventually { worker.processedSnapshotCount == 1L })
			assertTrue(worker.submit(snapshot(2, sample(10, 0, 7f, 25f), sample(20, 0, 9f, 30f))))
			assertTrue(session.awaitRuns(1))
			val first = session.inputs.first()
			assertEquals(2, first.time)
			assertContentEquals(longArrayOf(1, 2), first.roleIds)
			assertContentEquals(booleanArrayOf(true, true), first.slotMask)
			assertContentEquals(floatArrayOf(1f, 1f, 2f, 2f, 3f, 3f, 4f, 4f), first.features)
			assertTrue(first.channelValidity.all { it })
			assertEquals(2L, assertNotNull(worker.latest(10, 0)).sequence)

			worker.resetHistory(10, 1)
			assertTrue(worker.submit(snapshot(3, sample(10, 1, 11f, 35f), sample(20, 0, 13f, 40f))))
			assertTrue(eventually { worker.processedSnapshotCount == 3L })
			assertEquals(1, session.inputs.size)
			assertTrue(worker.submit(snapshot(4, sample(10, 1, 15f, 45f), sample(20, 0, 17f, 50f))))
			assertTrue(session.awaitRuns(2))
			val afterReset = session.inputs.last()
			assertTrue(afterReset.channelValidity.all { it })
			assertNull(worker.latest(10, 0))
			assertTrue(eventually { worker.latest(10, 1)?.sequence == 4L })

			worker.configureMappings(listOf(TrackerSlotMapping(10, 1, 1), TrackerSlotMapping(20, 2, 0)))
			assertTrue(worker.mappings().map { it.trackerId } == listOf(20, 10))
			assertNull(worker.latest(10, 1))
		}
		assertFalse(worker.isAlive)
		assertFalse(session.closed)
	}

	@Test
	fun `configured context controls emitted history and changes invalidate prior frames`() {
		val metadata = probeMetadata().copy(minimumContext = 1, maximumContext = 4)
		val session = RecordingSession(expectedRuns = 2)
		LatestValueInferenceWorker(session, metadata, listOf(TrackerSlotMapping(10, 1, 0)), initialContextFrames = 1).use { worker ->
			worker.submit(snapshot(1, sample(10, 0, 1f, 2f)))
			assertTrue(session.awaitRuns(1))
			assertEquals(1, session.inputs.single().time)
			worker.configureContextFrames(3)
			assertNull(worker.latest(10, 0))
			worker.submit(snapshot(2, sample(10, 0, 2f, 3f)))
			assertTrue(eventually { worker.processedSnapshotCount >= 2L })
			worker.submit(snapshot(3, sample(10, 0, 3f, 4f)))
			assertTrue(eventually { worker.processedSnapshotCount >= 3L })
			assertEquals(1, session.inputs.size)
			worker.submit(snapshot(4, sample(10, 0, 4f, 5f)))
			assertTrue(session.awaitRuns(2))
			assertEquals(3, session.inputs.last().time)
			assertEquals(3, worker.configuredContextFrames())
		}
	}

	@Test
	fun `one-element queue coalesces backlog to the latest snapshot`() {
		val metadata = probeMetadata().copy(minimumContext = 1)
		val session = RecordingSession(expectedRuns = 2, blockFirstRun = true)
		LatestValueInferenceWorker(session, metadata, listOf(TrackerSlotMapping(10, 1, 0))).use { worker ->
			worker.submit(snapshot(1, sample(10, 0, 1f, 1f)))
			assertTrue(session.firstRunStarted.await(2, TimeUnit.SECONDS))
			worker.submit(snapshot(2, sample(10, 0, 2f, 2f)))
			worker.submit(snapshot(3, sample(10, 0, 3f, 3f)))
			assertEquals(1L, worker.droppedSnapshotCount)
			session.releaseFirstRun.countDown()
			assertTrue(session.awaitRuns(2))
			assertTrue(eventually { worker.latest(10, 0)?.sequence == 3L })
			assertEquals(2, session.inputs.size)
		}
	}

	private fun probeMetadata(): ModelArtifactMetadata {
		fun resource(name: String): Path = Path.of(requireNotNull(javaClass.getResource("/dev/slimevr/ai/probe/$name")).toURI())
		return ModelArtifactValidator.validate(resource("probe.onnx"), resource("probe.onnx.json"))
	}

	private fun sample(trackerId: Int, epoch: Long, first: Float, second: Float) = InferenceTrackerSample(
		trackerId, epoch, floatArrayOf(first, second), booleanArrayOf(true, true),
	)

	private fun snapshot(sequence: Long, vararg samples: InferenceTrackerSample) = InferenceSnapshot(
		sequence, sequence * 20_000_000L, 0.02f, samples.toList(),
	)

	private fun eventually(predicate: () -> Boolean): Boolean {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
		while (System.nanoTime() < deadline) {
			if (predicate()) return true
			Thread.yield()
		}
		return predicate()
	}

	private class RecordingSession(private val expectedRuns: Int, private val blockFirstRun: Boolean = false) : LoadedModelSession {
		override val inputInfo: Map<String, RuntimeTensorInfo> = emptyMap()
		override val outputInfo: Map<String, RuntimeTensorInfo> = emptyMap()
		val inputs = CopyOnWriteArrayList<InferenceTensorBatch>()
		val firstRunStarted = CountDownLatch(1)
		val releaseFirstRun = CountDownLatch(1)
		private val runs = CountDownLatch(expectedRuns)
		@Volatile var closed = false

		override fun runProbe(metadata: ModelArtifactMetadata) = Unit

		override fun runInference(input: InferenceTensorBatch): InferenceTensorOutput {
			inputs += input
			if (inputs.size == 1) {
				firstRunStarted.countDown()
				if (blockFirstRun) assertTrue(releaseFirstRun.await(2, TimeUnit.SECONDS))
			}
			runs.countDown()
			return InferenceTensorOutput(
				FloatArray(input.slots * 3) { it.toFloat() },
				FloatArray(input.slots) { 0.5f + it },
				FloatArray(input.slots) { 0.01f * it },
			)
		}

		fun awaitRuns(expectedCompleted: Int): Boolean {
			val targetRemaining = (expectedRuns - expectedCompleted).coerceAtLeast(0).toLong()
			val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
			while (runs.count > targetRemaining && System.nanoTime() < deadline) Thread.yield()
			return runs.count <= targetRemaining
		}

		override fun close() {
			closed = true
		}
	}
}
