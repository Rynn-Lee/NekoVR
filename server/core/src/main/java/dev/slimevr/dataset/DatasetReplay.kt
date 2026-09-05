package dev.slimevr.dataset

import com.github.luben.zstd.ZstdInputStream
import dev.slimevr.dataset.generated.DatasetV1Bindings
import dev.slimevr.dataset.generated.DatasetV1Reader
import java.io.BufferedInputStream
import java.nio.file.Path
import java.util.zip.ZipFile

data class CounterfactualSample(
	val frameIndex: Long,
	val sessionTrackerId: String,
	val raw: QuaternionSample,
	val recordedApplied: QuaternionSample,
	val candidateApplied: QuaternionSample,
)

/** Offline raw-stream replay; recorded final output is never used as candidate input. */
object DatasetReplay {
	fun replay(path: Path, candidate: (SessionFrame, TrackerFrameSample) -> QuaternionSample): List<CounterfactualSample> {
		val report = DatasetArchiveValidator().validate(path)
		require(report.valid) { "Cannot replay invalid archive: ${report.findings}" }
		return buildList {
			ZipFile(path.toFile()).use { zip ->
				val entry = zip.getEntry("telemetry.fbs.zst") ?: error("telemetry missing")
				ZstdInputStream(BufferedInputStream(zip.getInputStream(entry))).use { input ->
					while (true) {
						val bytes = DatasetArchiveValidator.readRecord(input) ?: break
						val record = DatasetV1Reader.read(bytes)
						if (record.type != DatasetV1Bindings.RECORD_FRAMES) continue
						for (frame in record.frames) for (tracker in frame.trackers) add(
							CounterfactualSample(frame.frameIndex, tracker.sessionTrackerId, tracker.rawOrientation, tracker.correction.appliedCorrection, candidate(frame, tracker)),
						)
					}
				}
			}
		}
	}
}
