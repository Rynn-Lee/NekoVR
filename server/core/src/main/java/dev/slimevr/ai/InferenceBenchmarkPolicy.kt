package dev.slimevr.ai

import kotlinx.serialization.Serializable

@Serializable
data class InferenceBenchmarkPolicy(
	val format: String = "nekovr-inference-benchmark-policy-v1",
	val trackerCount: Int = 10,
	val contextSize: Int = 60,
	val maximumCpuP95Micros: Long = 4_000,
	val maximumGpuP95Micros: Long = 1_000,
	val maximumIncrementalGpuMemoryBytes: Long = 128L * 1024L * 1024L,
	val requireQueueDrain: Boolean = true,
	val maximumQueueDropRatio: Double? = null,
	val requiredHostId: String? = null,
)

@Serializable
data class InferenceBenchmarkFinding(
	val code: String,
	val message: String,
)

@Serializable
data class InferenceBenchmarkGateReport(
	val format: String = "nekovr-inference-benchmark-gate-v1",
	val passed: Boolean,
	val provider: String,
	val modelSha256: String,
	val trackerCount: Int,
	val contextSize: Int,
	val findings: List<InferenceBenchmarkFinding>,
)

object InferenceBenchmarkGate {
	fun evaluate(report: InferenceBenchmarkReport, policy: InferenceBenchmarkPolicy = InferenceBenchmarkPolicy()): InferenceBenchmarkGateReport {
		val findings = mutableListOf<InferenceBenchmarkFinding>()
		if (report.performanceTier != "small") findings += finding("NOT_SMALL_TIER", "Model performance tier is ${report.performanceTier}, expected small")
		if (policy.requiredHostId != null && report.benchmarkHostId != policy.requiredHostId) {
			findings += finding("WRONG_REFERENCE_HOST", "Evidence host ${report.benchmarkHostId} is not ${policy.requiredHostId}")
		}
		val scenario = report.scenarios.singleOrNull { it.trackerCount == policy.trackerCount && it.contextSize == policy.contextSize }
		if (scenario == null) {
			findings += finding("MISSING_REFERENCE_SCENARIO", "Report does not contain ${policy.trackerCount}-tracker/${policy.contextSize}-frame evidence")
		} else {
			val gpu = report.provider in setOf(ExecutionProviderType.CUDA.name, ExecutionProviderType.TENSORRT.name, ExecutionProviderType.DIRECTML.name)
			val latencyLimit = if (gpu) policy.maximumGpuP95Micros else policy.maximumCpuP95Micros
			if (scenario.inferenceLatency.p95Micros > latencyLimit) {
				findings += finding("INFERENCE_P95_EXCEEDED", "p95 ${scenario.inferenceLatency.p95Micros} us exceeds $latencyLimit us")
			}
			if (gpu) {
				val memory = scenario.utilization.gpuMemoryIncrementalBytes
				if (memory == null) findings += finding("GPU_MEMORY_UNAVAILABLE", "Incremental GPU memory evidence is required")
				else if (memory > policy.maximumIncrementalGpuMemoryBytes) {
					findings += finding("GPU_MEMORY_EXCEEDED", "Incremental GPU memory $memory exceeds ${policy.maximumIncrementalGpuMemoryBytes}")
				}
			}
			if (policy.requireQueueDrain && (!scenario.queueDrained || scenario.queueFinalDepth != 0)) {
				findings += finding("QUEUE_NOT_DRAINED", "Latest-value queue did not return to zero")
			}
			val dropRatioLimit = policy.maximumQueueDropRatio
			val dropRatio = scenario.queueDrops.toDouble() / scenario.requestedSubmissions.coerceAtLeast(1)
			if (dropRatioLimit != null && dropRatio > dropRatioLimit) {
				findings += finding("QUEUE_UNSTABLE", "Queue drop ratio $dropRatio exceeds $dropRatioLimit")
			}
		}
		return InferenceBenchmarkGateReport(
			passed = findings.isEmpty(), provider = report.provider, modelSha256 = report.modelSha256,
			trackerCount = policy.trackerCount, contextSize = policy.contextSize, findings = findings,
		)
	}

	private fun finding(code: String, message: String) = InferenceBenchmarkFinding(code, message)
}
