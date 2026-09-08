package dev.slimevr.unit

import dev.slimevr.ai.BenchmarkPercentiles
import dev.slimevr.ai.BenchmarkResourceSampler
import dev.slimevr.ai.BenchmarkResourceSnapshot
import dev.slimevr.ai.ExecutionProviderType
import dev.slimevr.ai.InferenceBenchmarkConfig
import dev.slimevr.ai.InferenceBenchmarkGate
import dev.slimevr.ai.InferenceBenchmarkPolicy
import dev.slimevr.ai.InferenceBenchmarkRunner
import dev.slimevr.ai.InferenceTensorBatch
import dev.slimevr.ai.InferenceTensorOutput
import dev.slimevr.ai.LoadedModelSession
import dev.slimevr.ai.ModelArtifactMetadata
import dev.slimevr.ai.OnnxRuntimePackage
import dev.slimevr.ai.RuntimeTensorInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InferenceBenchmarkTests {
	@Test
	fun `percentiles use nearest rank and retain maximum`() {
		val distribution = BenchmarkPercentiles.fromMicros((1L..100L).toList())
		assertEquals(50, distribution.p50Micros)
		assertEquals(95, distribution.p95Micros)
		assertEquals(99, distribution.p99Micros)
		assertEquals(100, distribution.maximumMicros)
		assertEquals(100, distribution.samples)
	}

	@Test
	fun `runner covers tracker and context matrix with queue and resource evidence`() {
		val metadata = ModelArtifactMetadata(
			modelId = "benchmark-fixture", modelVersion = "1", modelSha256 = "a".repeat(64), modelSizeBytes = 1,
			featureSchemaSha256 = "b".repeat(64), inputs = emptyList(), outputs = emptyList(),
			minimumSlots = 1, maximumSlots = 2, minimumContext = 2, maximumContext = 3,
			normalizationMean = floatArrayOf(0f, 0f), normalizationStandardDeviation = floatArrayOf(1f, 1f),
			supportedRoles = setOf(1, 2), opset = 18,
		)
		val report = InferenceBenchmarkRunner(
			metadata = metadata, sessionFactory = { FixtureSession() }, runtimePackage = OnnxRuntimePackage("test", "1"),
			provider = ExecutionProviderType.CPU, resourceSampler = FixtureResources(),
		).run(
			InferenceBenchmarkConfig(
				trackerCounts = listOf(1, 2), contextSizes = listOf(2, 3), iterations = 20,
				warmupInferences = 2, submissionRateHz = 0.0, resourceSamplePeriodMillis = 1,
			),
		)
		assertEquals(4, report.scenarios.size)
		assertEquals(setOf(1, 2), report.scenarios.map { it.trackerCount }.toSet())
		assertEquals(setOf(2, 3), report.scenarios.map { it.contextSize }.toSet())
		assertTrue(report.scenarios.all { it.queueMaximumDepth <= 1 && it.queueFinalDepth == 0 && it.queueDrained })
		assertTrue(report.scenarios.all { it.serverTickBlocking.samples == 20 && it.inferenceLatency.samples > 0 })
		assertTrue(report.scenarios.all { it.utilization.processCpuPercent.p50 == 25.0 })
		val reference = report.copy(
			benchmarkHostId = "weak-cpu", performanceTier = "small",
			scenarios = listOf(report.scenarios.first { it.trackerCount == 2 && it.contextSize == 3 }),
		)
		val policy = InferenceBenchmarkPolicy(trackerCount = 2, contextSize = 3, requiredHostId = "weak-cpu", maximumQueueDropRatio = 1.0)
		assertTrue(InferenceBenchmarkGate.evaluate(reference, policy).passed)
		val failed = InferenceBenchmarkGate.evaluate(
			reference.copy(provider = ExecutionProviderType.CUDA.name),
			policy.copy(maximumGpuP95Micros = -1, maximumIncrementalGpuMemoryBytes = -1),
		)
		assertEquals(setOf("INFERENCE_P95_EXCEEDED", "GPU_MEMORY_EXCEEDED"), failed.findings.map { it.code }.toSet())
	}

	private class FixtureSession : LoadedModelSession {
		override val inputInfo: Map<String, RuntimeTensorInfo> = emptyMap()
		override val outputInfo: Map<String, RuntimeTensorInfo> = emptyMap()
		override fun runProbe(metadata: ModelArtifactMetadata) = Unit
		override fun runInference(input: InferenceTensorBatch) = InferenceTensorOutput(
			correctionRotationVectors = FloatArray(input.slots * 3), confidence = FloatArray(input.slots), driftRate = FloatArray(input.slots),
		)
		override fun close() = Unit
	}

	private class FixtureResources : BenchmarkResourceSampler {
		override val gpuSource = "fixture-gpu"
		override val gpuUnavailableReason: String? = null
		override fun sample() = BenchmarkResourceSnapshot(
			processCpuPercent = 25.0, systemCpuPercent = 40.0, heapUsedBytes = 1024,
			nonHeapUsedBytes = 2048, committedVirtualMemoryBytes = 4096, gpuPercent = 50.0, gpuMemoryBytes = 8192,
		)
	}
}
