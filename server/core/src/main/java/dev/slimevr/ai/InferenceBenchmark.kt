package dev.slimevr.ai

import com.sun.management.OperatingSystemMXBean
import kotlinx.serialization.Serializable
import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

@Serializable
data class BenchmarkPercentiles(
	val p50Micros: Long,
	val p95Micros: Long,
	val p99Micros: Long,
	val maximumMicros: Long,
	val samples: Int,
) {
	companion object {
		fun fromMicros(values: Collection<Long>): BenchmarkPercentiles {
			if (values.isEmpty()) return BenchmarkPercentiles(0, 0, 0, 0, 0)
			val sorted = values.sorted()
			fun percentile(percent: Int): Long = sorted[(ceil(percent / 100.0 * sorted.size).toInt() - 1).coerceIn(sorted.indices)]
			return BenchmarkPercentiles(percentile(50), percentile(95), percentile(99), sorted.last(), sorted.size)
		}
	}
}

@Serializable
data class BenchmarkUtilization(
	val processCpuPercent: BenchmarkDoublePercentiles,
	val systemCpuPercent: BenchmarkDoublePercentiles,
	val gpuPercent: BenchmarkDoublePercentiles?,
	val gpuMemoryBaselineBytes: Long?,
	val gpuMemoryPeakBytes: Long?,
	val gpuMemoryIncrementalBytes: Long?,
	val gpuSource: String?,
	val gpuUnavailableReason: String?,
)

@Serializable
data class BenchmarkRuntimePercentiles(
	val p50Micros: Long,
	val p95Micros: Long,
	val p99Micros: Long,
)

@Serializable
data class BenchmarkDoublePercentiles(
	val p50: Double,
	val p95: Double,
	val p99: Double,
	val maximum: Double,
	val samples: Int,
) {
	companion object {
		fun from(values: Collection<Double>): BenchmarkDoublePercentiles {
			if (values.isEmpty()) return BenchmarkDoublePercentiles(0.0, 0.0, 0.0, 0.0, 0)
			val sorted = values.sorted()
			fun percentile(percent: Int): Double = sorted[(ceil(percent / 100.0 * sorted.size).toInt() - 1).coerceIn(sorted.indices)]
			return BenchmarkDoublePercentiles(percentile(50), percentile(95), percentile(99), sorted.last(), sorted.size)
		}
	}
}

@Serializable
data class BenchmarkMemory(
	val heapBaselineBytes: Long,
	val heapPeakBytes: Long,
	val heapIncrementalBytes: Long,
	val nonHeapPeakBytes: Long,
	val committedVirtualMemoryPeakBytes: Long?,
)

@Serializable
data class InferenceBenchmarkScenario(
	val trackerCount: Int,
	val contextSize: Int,
	val requestedSubmissions: Int,
	val processedInferences: Long,
	val queueDrops: Long,
	val queueMaximumDepth: Int,
	val queueFinalDepth: Int,
	val queueDrained: Boolean,
	val serverTickBlocking: BenchmarkPercentiles,
	val inferenceLatency: BenchmarkPercentiles,
	val queueWaitLatency: BenchmarkRuntimePercentiles,
	val utilization: BenchmarkUtilization,
	val memory: BenchmarkMemory,
)

@Serializable
data class InferenceBenchmarkReport(
	val format: String = "nekovr-inference-benchmark-v1",
	val benchmarkHostId: String = "unspecified",
	val createdAtUtc: String,
	val modelId: String,
	val modelVersion: String,
	val modelSha256: String,
	val performanceTier: String,
	val provider: String,
	val runtimeFlavor: String,
	val runtimeVersion: String,
	val os: String,
	val architecture: String,
	val jvm: String,
	val availableProcessors: Int,
	val warmupInferences: Int,
	val submissionRateHz: Double,
	val scenarios: List<InferenceBenchmarkScenario>,
)

data class InferenceBenchmarkConfig(
	val trackerCounts: List<Int>,
	val contextSizes: List<Int>,
	val iterations: Int = 500,
	val warmupInferences: Int = 30,
	val submissionRateHz: Double = 100.0,
	val resourceSamplePeriodMillis: Long = 100,
	val drainTimeoutMillis: Long = 10_000,
	val benchmarkHostId: String = "unspecified",
)

data class BenchmarkResourceSnapshot(
	val processCpuPercent: Double?,
	val systemCpuPercent: Double?,
	val heapUsedBytes: Long,
	val nonHeapUsedBytes: Long,
	val committedVirtualMemoryBytes: Long?,
	val gpuPercent: Double? = null,
	val gpuMemoryBytes: Long? = null,
)

interface BenchmarkResourceSampler {
	val gpuSource: String?
	val gpuUnavailableReason: String?
	fun sample(): BenchmarkResourceSnapshot
}

class HostBenchmarkResourceSampler private constructor(
	private val gpuSampler: NvidiaSmiSampler?,
	override val gpuUnavailableReason: String?,
) : BenchmarkResourceSampler {
	private val osBean = ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean
	private val memoryBean = ManagementFactory.getMemoryMXBean()
	override val gpuSource: String? = gpuSampler?.source

	override fun sample(): BenchmarkResourceSnapshot {
		val gpu = gpuSampler?.sample()
		return BenchmarkResourceSnapshot(
			processCpuPercent = osBean?.processCpuLoad?.takeIf { it >= 0.0 }?.times(100.0),
			systemCpuPercent = osBean?.cpuLoad?.takeIf { it >= 0.0 }?.times(100.0),
			heapUsedBytes = memoryBean.heapMemoryUsage.used,
			nonHeapUsedBytes = memoryBean.nonHeapMemoryUsage.used,
			committedVirtualMemoryBytes = osBean?.committedVirtualMemorySize?.takeIf { it >= 0L },
			gpuPercent = gpu?.first,
			gpuMemoryBytes = gpu?.second,
		)
	}

	companion object {
		fun create(provider: ExecutionProviderType): HostBenchmarkResourceSampler {
			if (provider == ExecutionProviderType.CPU) return HostBenchmarkResourceSampler(null, "CPU provider does not execute inference on a GPU")
			if (provider == ExecutionProviderType.DIRECTML) {
				return HostBenchmarkResourceSampler(null, "DirectML GPU counters require an external platform sampler")
			}
			val gpu = NvidiaSmiSampler.createOrNull()
			return HostBenchmarkResourceSampler(gpu, if (gpu == null) "nvidia-smi is unavailable; GPU utilization was not sampled" else null)
		}
	}
}

private class NvidiaSmiSampler private constructor(val source: String) {
	private val processId = ProcessHandle.current().pid()
	fun sample(): Pair<Double, Long>? = run(
		"--query-gpu=utilization.gpu",
		"--format=csv,noheader,nounits",
	)?.lineSequence()?.firstOrNull()?.trim()?.toDoubleOrNull()?.let { utilization ->
			val processMemoryMiB = run("--query-compute-apps=pid,used_memory", "--format=csv,noheader,nounits")
				?.lineSequence()?.mapNotNull { line ->
					val processFields = line.split(',')
					if (processFields.size < 2 || processFields[0].trim().toLongOrNull() != processId) null else processFields[1].trim().toLongOrNull()
				}?.sum() ?: 0L
		utilization to processMemoryMiB * 1024L * 1024L
	}

	companion object {
		fun createOrNull(): NvidiaSmiSampler? {
			val name = run("--query-gpu=name", "--format=csv,noheader")?.lineSequence()?.firstOrNull()?.trim()
			return name?.takeIf(String::isNotBlank)?.let { NvidiaSmiSampler("nvidia-smi device utilization and process GPU memory: $it") }
		}

		private fun run(vararg arguments: String): String? = try {
			val process = ProcessBuilder(listOf("nvidia-smi") + arguments).redirectErrorStream(true).start()
			if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) {
				process.destroyForcibly()
				null
			} else {
				process.inputStream.bufferedReader().use { it.readText() }
			}
		} catch (_: Exception) {
			null
		}
	}
}

class InferenceBenchmarkRunner(
	private val metadata: ModelArtifactMetadata,
	private val sessionFactory: () -> LoadedModelSession,
	private val runtimePackage: OnnxRuntimePackage,
	private val provider: ExecutionProviderType,
	private val resourceSampler: BenchmarkResourceSampler = HostBenchmarkResourceSampler.create(provider),
) {
	fun run(config: InferenceBenchmarkConfig): InferenceBenchmarkReport {
		require(config.iterations > 0 && config.warmupInferences >= 0)
		require(config.submissionRateHz.isFinite() && config.submissionRateHz >= 0.0 && config.resourceSamplePeriodMillis > 0 && config.drainTimeoutMillis > 0)
		require(config.trackerCounts.isNotEmpty() && config.contextSizes.isNotEmpty())
		require(metadata.supportedRoles.isNotEmpty()) { "Model must declare at least one supported body role" }
		config.trackerCounts.forEach { require(it in metadata.minimumSlots..metadata.maximumSlots) { "Tracker count $it is outside model bounds" } }
		config.contextSizes.forEach { require(it in metadata.minimumContext..metadata.maximumContext) { "Context size $it is outside model bounds" } }
		val scenarios = config.trackerCounts.distinct().sorted().flatMap { trackers ->
			config.contextSizes.distinct().sorted().map { context -> runScenario(trackers, context, config) }
		}
		return InferenceBenchmarkReport(
			benchmarkHostId = config.benchmarkHostId, createdAtUtc = Instant.now().toString(),
			modelId = metadata.modelId, modelVersion = metadata.modelVersion,
			modelSha256 = metadata.modelSha256, performanceTier = metadata.performanceTier,
			provider = provider.name, runtimeFlavor = runtimePackage.flavor,
			runtimeVersion = runtimePackage.version, os = System.getProperty("os.name"), architecture = System.getProperty("os.arch"),
			jvm = "${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}",
			availableProcessors = Runtime.getRuntime().availableProcessors(), warmupInferences = config.warmupInferences,
			submissionRateHz = config.submissionRateHz, scenarios = scenarios,
		)
	}

	private fun runScenario(trackers: Int, context: Int, config: InferenceBenchmarkConfig): InferenceBenchmarkScenario {
		val scenarioMetadata = metadata.copy(minimumSlots = trackers, minimumContext = context, maximumContext = context)
		val session = MeasuringSession(sessionFactory())
		ModelArtifactValidator.validateTensorContract(scenarioMetadata, session.inputInfo, session.outputInfo)
		session.runProbe(scenarioMetadata)
		val mappings = List(trackers) { index ->
			TrackerSlotMapping(index + 1, metadata.supportedRoles.sorted()[index % metadata.supportedRoles.size], index)
		}
		LatestValueInferenceWorker(session, scenarioMetadata, mappings).use { worker ->
			var sequence = 0L
			val samples = List(trackers) { index ->
				InferenceTrackerSample(index + 1, 0L, FloatArray(metadata.featureCount), BooleanArray(metadata.featureCount) { true })
			}
			val warmupSubmissions = context - 1 + config.warmupInferences
			repeat(warmupSubmissions) {
				val processedBefore = worker.processedSnapshotCount
				worker.submit(InferenceSnapshot(++sequence, System.nanoTime(), 0.02f, samples))
				check(await(config.drainTimeoutMillis) { worker.processedSnapshotCount > processedBefore }) { "Inference warm-up timed out" }
			}
			check(await(config.drainTimeoutMillis) { session.latencies.size >= config.warmupInferences }) { "Inference warm-up did not complete" }
			session.latencies.clear()
			worker.resetLatencyMetrics()
			val processedBefore = worker.processedSnapshotCount
			val dropsBefore = worker.droppedSnapshotCount
			val baseline = resourceSampler.sample()
			val resourceSamples = CopyOnWriteArrayList<BenchmarkResourceSnapshot>().apply { add(baseline) }
			val monitoring = java.util.concurrent.atomic.AtomicBoolean(true)
			val monitor = Thread({
				while (monitoring.get()) {
					resourceSamples += resourceSampler.sample()
					try {
						Thread.sleep(config.resourceSamplePeriodMillis)
					} catch (_: InterruptedException) {
						return@Thread
					}
				}
			}, "NekoVR-AI-Benchmark-Resources").apply { isDaemon = true; start() }
			val submitMicros = ArrayList<Long>(config.iterations)
			var maximumDepth = worker.queueDepth
			val periodNanos = if (config.submissionRateHz > 0.0) (1_000_000_000.0 / config.submissionRateHz).toLong() else 0L
			var deadline = System.nanoTime()
			try {
				repeat(config.iterations) {
					val started = System.nanoTime()
					worker.submit(InferenceSnapshot(++sequence, started, 0.02f, samples))
					submitMicros += (System.nanoTime() - started) / 1_000L
					maximumDepth = maxOf(maximumDepth, worker.queueDepth)
					if (periodNanos > 0) {
						deadline += periodNanos
						val remaining = deadline - System.nanoTime()
						if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining)
					}
				}
				val drained = await(config.drainTimeoutMillis) { worker.queueDepth == 0 && session.isIdleForMillis(25) }
				resourceSamples += resourceSampler.sample()
				return InferenceBenchmarkScenario(
					trackerCount = trackers, contextSize = context, requestedSubmissions = config.iterations,
					processedInferences = worker.processedSnapshotCount - processedBefore,
					queueDrops = worker.droppedSnapshotCount - dropsBefore, queueMaximumDepth = maximumDepth,
					queueFinalDepth = worker.queueDepth, queueDrained = drained,
					serverTickBlocking = BenchmarkPercentiles.fromMicros(submitMicros),
					inferenceLatency = BenchmarkPercentiles.fromMicros(session.latencies),
					queueWaitLatency = worker.queueWaitLatencyPercentiles().let {
						BenchmarkRuntimePercentiles(it.p50Micros, it.p95Micros, it.p99Micros)
					},
					utilization = utilization(resourceSamples), memory = memory(baseline, resourceSamples),
				)
			} finally {
				monitoring.set(false)
				monitor.interrupt()
				monitor.join()
			}
		}
	}

	private fun utilization(samples: List<BenchmarkResourceSnapshot>): BenchmarkUtilization {
		fun percentages(values: List<Double?>) = BenchmarkDoublePercentiles.from(values.filterNotNull())
		val gpuValues = samples.map { it.gpuPercent }.filterNotNull()
		val gpuMemoryBaseline = samples.firstOrNull()?.gpuMemoryBytes
		val gpuMemoryPeak = samples.mapNotNull { it.gpuMemoryBytes }.maxOrNull()
		return BenchmarkUtilization(
			processCpuPercent = percentages(samples.map { it.processCpuPercent }),
			systemCpuPercent = percentages(samples.map { it.systemCpuPercent }),
			gpuPercent = gpuValues.takeIf { it.isNotEmpty() }?.let { BenchmarkDoublePercentiles.from(it) },
			gpuMemoryBaselineBytes = gpuMemoryBaseline, gpuMemoryPeakBytes = gpuMemoryPeak,
			gpuMemoryIncrementalBytes = if (gpuMemoryBaseline != null && gpuMemoryPeak != null) (gpuMemoryPeak - gpuMemoryBaseline).coerceAtLeast(0) else null,
			gpuSource = resourceSampler.gpuSource, gpuUnavailableReason = resourceSampler.gpuUnavailableReason,
		)
	}

	private fun memory(baseline: BenchmarkResourceSnapshot, samples: List<BenchmarkResourceSnapshot>): BenchmarkMemory {
		val heapPeak = samples.maxOf { it.heapUsedBytes }
		return BenchmarkMemory(
			heapBaselineBytes = baseline.heapUsedBytes, heapPeakBytes = heapPeak,
			heapIncrementalBytes = (heapPeak - baseline.heapUsedBytes).coerceAtLeast(0),
			nonHeapPeakBytes = samples.maxOf { it.nonHeapUsedBytes },
			committedVirtualMemoryPeakBytes = samples.mapNotNull { it.committedVirtualMemoryBytes }.maxOrNull(),
		)
	}

	private fun await(timeoutMillis: Long, predicate: () -> Boolean): Boolean {
		val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
		while (System.nanoTime() < deadline) {
			if (predicate()) return true
			Thread.sleep(1)
		}
		return predicate()
	}

	private class MeasuringSession(private val delegate: LoadedModelSession) : LoadedModelSession {
		override val inputInfo = delegate.inputInfo
		override val outputInfo = delegate.outputInfo
		val latencies = CopyOnWriteArrayList<Long>()
		private val inFlight = AtomicInteger()
		@Volatile private var lastCompletionNanos = System.nanoTime()
		override fun runProbe(metadata: ModelArtifactMetadata) = delegate.runProbe(metadata)
		override fun runInference(input: InferenceTensorBatch): InferenceTensorOutput {
			val started = System.nanoTime()
			inFlight.incrementAndGet()
			return try {
				delegate.runInference(input)
			} finally {
				latencies += (System.nanoTime() - started) / 1_000L
				lastCompletionNanos = System.nanoTime()
				inFlight.decrementAndGet()
			}
		}
		fun isIdleForMillis(millis: Long) = inFlight.get() == 0 && System.nanoTime() - lastCompletionNanos >= TimeUnit.MILLISECONDS.toNanos(millis)
		override fun close() = delegate.close()
	}
}
