package dev.slimevr.ai

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

object InferenceBenchmarkGateCommand {
	private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false }

	@JvmStatic
	fun main(arguments: Array<String>) {
		require(arguments.size in 2..3) { "Usage: REPORT.json GATE-REPORT.json [REQUIRED-HOST-ID]" }
		val benchmark = json.decodeFromString<InferenceBenchmarkReport>(Files.readString(Path.of(arguments[0])))
		val result = InferenceBenchmarkGate.evaluate(
			benchmark,
			InferenceBenchmarkPolicy(requiredHostId = arguments.getOrNull(2)),
		)
		val output = Path.of(arguments[1]).toAbsolutePath().normalize()
		output.parent?.let(Files::createDirectories)
		Files.writeString(output, json.encodeToString(result) + "\n")
		check(result.passed) { result.findings.joinToString { "${it.code}: ${it.message}" } }
		println("AI_INFERENCE_BENCHMARK_GATE_OK provider=${result.provider} output=$output")
	}
}
