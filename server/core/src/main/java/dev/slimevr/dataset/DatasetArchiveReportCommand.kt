package dev.slimevr.dataset

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Serializable
private data class DatasetArchiveReports(val reports: List<DatasetValidationReport>)

/** Machine-readable entry point used by the dataset-ready validation command. */
fun main(arguments: Array<String>) {
	val args = arguments.toList()
	val outputIndex = args.indexOf("--output")
	require(outputIndex >= 0 && outputIndex + 1 < args.size) { "Usage: --output REPORT.json ARCHIVE.nvrdata [...]" }
	val output = Path.of(args[outputIndex + 1]).toAbsolutePath().normalize()
	val archives = args.filterIndexed { index, _ -> index != outputIndex && index != outputIndex + 1 }.map { Path.of(it) }
	require(archives.isNotEmpty()) { "At least one pilot archive is required" }
	val reports = DatasetArchiveReports(archives.map { DatasetArchiveValidator().validate(it) })
	output.parent?.let(Files::createDirectories)
	val temporary = output.resolveSibling("${output.fileName}.tmp")
	Files.writeString(temporary, Json { prettyPrint = true; encodeDefaults = true }.encodeToString(reports) + "\n")
	try {
		Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
	} catch (_: AtomicMoveNotSupportedException) {
		Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
	}
}
