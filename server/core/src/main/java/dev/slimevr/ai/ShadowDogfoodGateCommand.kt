package dev.slimevr.ai

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

object ShadowDogfoodGateCommand {
	private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false }

	@JvmStatic
	fun main(arguments: Array<String>) {
		require(arguments.size == 2) { "Usage: SHADOW-DOGFOOD.json GATE-REPORT.json" }
		val source = json.decodeFromString<ShadowDogfoodReport>(Files.readString(Path.of(arguments[0])))
		val result = ShadowDogfoodGate.evaluate(source)
		val output = Path.of(arguments[1]).toAbsolutePath().normalize()
		output.parent?.let(Files::createDirectories)
		Files.writeString(output, json.encodeToString(result) + "\n")
		check(result.passed) { result.findings.joinToString { "${it.code}: ${it.detail}" } }
		println("SHADOW_DOGFOOD_GATE_OK model=${result.modelSha256} output=$output")
	}
}
