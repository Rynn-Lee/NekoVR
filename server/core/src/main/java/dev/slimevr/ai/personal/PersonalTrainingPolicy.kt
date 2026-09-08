package dev.slimevr.ai.personal

import kotlinx.serialization.Serializable

@Serializable
enum class PersonalTrainingPreset { QUICK, BALANCED, THOROUGH }
@Serializable
enum class ActiveVrPolicy { PAUSE, THROTTLE, CONTINUE }
enum class ResourceAction { RUN, THROTTLE, CHECKPOINT_AND_PAUSE }

@Serializable
data class PersonalHardwareProbe(val logicalCpuThreads: Int, val availableRamMiB: Int, val cudaTrainingAvailable: Boolean) {
	companion object {
		fun detect(cudaTrainingProbe: () -> Boolean = { false }): PersonalHardwareProbe = PersonalHardwareProbe(
			logicalCpuThreads = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
			availableRamMiB = (Runtime.getRuntime().maxMemory() / (1024 * 1024)).coerceAtLeast(256).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
			cudaTrainingAvailable = runCatching(cudaTrainingProbe).getOrDefault(false),
		)
	}
}
@Serializable
data class PersonalCoverageSummary(val usableSeconds: Double, val usableWindows: Long, val resetLabels: Long, val cleanSeconds: Double)
@Serializable
data class PersonalTrainingPlan(val preset: PersonalTrainingPreset, val epochs: Int, val windowBudget: Long, val patience: Int, val cpuThreads: Int, val provider: String)

object PersonalTrainingPlans {
	fun derive(preset: PersonalTrainingPreset, coverage: PersonalCoverageSummary, hardware: PersonalHardwareProbe): PersonalTrainingPlan {
		require(coverage.usableSeconds >= 0 && coverage.usableWindows > 0 && hardware.logicalCpuThreads > 0 && hardware.availableRamMiB > 0)
		val (maximumEpochs, multiplier, maximumPatience) = when (preset) {
			PersonalTrainingPreset.QUICK -> Triple(12, 0.35, 3)
			PersonalTrainingPreset.BALANCED -> Triple(36, 0.7, 7)
			PersonalTrainingPreset.THOROUGH -> Triple(80, 1.0, 12)
		}
		val dataScale = when {
			coverage.usableSeconds < 60.0 -> 0.5
			coverage.usableSeconds < 300.0 -> 0.75
			else -> 1.0
		}
		val epochs = (maximumEpochs * dataScale).toInt().coerceAtLeast(4)
		val patience = (maximumPatience * dataScale).toInt().coerceAtLeast(2)
		val memoryWindowLimit = (hardware.availableRamMiB.toLong() * 2).coerceAtLeast(1)
		val windows = (coverage.usableWindows * multiplier).toLong()
			.coerceAtLeast(1)
			.coerceAtMost(minOf(coverage.usableWindows, memoryWindowLimit))
		val threads = (hardware.logicalCpuThreads / 2).coerceIn(1, 8)
		return PersonalTrainingPlan(preset, epochs, windows, patience.coerceAtMost(epochs / 2), threads, if (hardware.cudaTrainingAvailable) "AUTO" else "CPU")
	}
}

@Serializable
data class PersonalResourceBudget(
	val cpuThreads: Int,
	val gpuMemoryMiB: Int,
	val gpuUtilizationPercent: Int,
	val ramMiB: Int,
	val diskMiB: Int,
	val ioMiBPerSecond: Float,
	val activeVrPolicy: ActiveVrPolicy = ActiveVrPolicy.PAUSE,
) {
	init {
		require(cpuThreads in 1..64 && gpuMemoryMiB >= 0 && gpuUtilizationPercent in 0..100)
		require(ramMiB > 0 && diskMiB > 0 && ioMiBPerSecond.isFinite() && ioMiBPerSecond > 0f)
	}
}

@Serializable
data class PersonalResourceUsage(val cpuThreads: Int, val gpuMemoryMiB: Int, val gpuUtilizationPercent: Int, val ramMiB: Int, val diskMiB: Int, val ioMiBPerSecond: Float, val activeVr: Boolean)

object PersonalResourceGovernor {
	fun decide(budget: PersonalResourceBudget, usage: PersonalResourceUsage): ResourceAction {
		val overHardLimit = usage.cpuThreads > budget.cpuThreads || usage.gpuMemoryMiB > budget.gpuMemoryMiB ||
			usage.ramMiB > budget.ramMiB || usage.diskMiB > budget.diskMiB || usage.ioMiBPerSecond > budget.ioMiBPerSecond
		if (overHardLimit) return ResourceAction.CHECKPOINT_AND_PAUSE
		if (!usage.activeVr) return if (usage.gpuUtilizationPercent > budget.gpuUtilizationPercent) ResourceAction.THROTTLE else ResourceAction.RUN
		return when (budget.activeVrPolicy) {
			ActiveVrPolicy.PAUSE -> ResourceAction.CHECKPOINT_AND_PAUSE
			ActiveVrPolicy.THROTTLE -> ResourceAction.THROTTLE
			ActiveVrPolicy.CONTINUE -> if (usage.gpuUtilizationPercent > budget.gpuUtilizationPercent) ResourceAction.THROTTLE else ResourceAction.RUN
		}
	}
}
