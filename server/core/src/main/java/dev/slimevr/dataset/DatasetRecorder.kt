package dev.slimevr.dataset

import dev.slimevr.VRServer
import io.eiren.util.logging.LogManager
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DatasetRecorder {

	var isRecording: Boolean = false
		private set

	var sessionStartTimeMs: Long = 0
		private set

	var frameCount: Long = 0
		private set

	private var compressor: ZstdStreamCompressor? = null
	private val resetEvents = CopyOnWriteArrayList<ResetEventMetadata>()
	private var lastFrameTimeMs: Long = 0
	private val frameBuffer = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)

	fun startRecording() {
		if (isRecording) return
		sessionStartTimeMs = System.currentTimeMillis()
		frameCount = 0
		lastFrameTimeMs = 0
		resetEvents.clear()
		compressor = ZstdStreamCompressor(compressionLevel = 3)
		isRecording = true
		LogManager.info("[Dataset] Started high-density motion dataset recording session at 50Hz")
	}

	fun recordFrame(
		hmdRotation: Quaternion,
		hmdPosition: Vector3,
		trackerRotations: Map<Int, Quaternion>,
		trackerAccelerations: Map<Int, Vector3>,
	) {
		if (!isRecording) return
		val now = System.currentTimeMillis()
		// Limit to 50 Hz (20ms)
		if (now - lastFrameTimeMs < 19) return
		lastFrameTimeMs = now

		val comp = compressor ?: return
		frameBuffer.clear()

		// Frame Header: Timestamp offset (uint32)
		val timeOffset = (now - sessionStartTimeMs).toInt()
		frameBuffer.putInt(timeOffset)

		// Ground Truth: HMD Orientation (FP16 x4) + Position (FP16 x3)
		FP16BinaryPacker.writeQuaternionFP16(frameBuffer, hmdRotation.x, hmdRotation.y, hmdRotation.z, hmdRotation.w)
		FP16BinaryPacker.writeVector3FP16(frameBuffer, hmdPosition.x, hmdPosition.y, hmdPosition.z)

		// Trackers: Count + Array of (Id: uint8, Quat: 4x FP16, Accel: 3x FP16)
		frameBuffer.put(trackerRotations.size.toByte())
		for ((id, quat) in trackerRotations) {
			frameBuffer.put(id.toByte())
			FP16BinaryPacker.writeQuaternionFP16(frameBuffer, quat.x, quat.y, quat.z, quat.w)
			val accel = trackerAccelerations[id] ?: Vector3.NULL
			FP16BinaryPacker.writeVector3FP16(frameBuffer, accel.x, accel.y, accel.z)
		}

		frameBuffer.flip()
		val rawBytes = ByteArray(frameBuffer.remaining())
		frameBuffer.get(rawBytes)
		comp.write(rawBytes)
		frameCount++
	}

	fun logResetEvent(trackerId: Int, resetType: String, yawDeltaDegrees: Float) {
		if (!isRecording) return
		val event = ResetEventMetadata(
			timestampMs = System.currentTimeMillis() - sessionStartTimeMs,
			resetType = resetType,
			trackerId = trackerId,
			yawDeltaDegrees = yawDeltaDegrees,
		)
		resetEvents.add(event)
		LogManager.info("[Dataset] Logged calibration reset event: $resetType for tracker #$trackerId (Delta: $yawDeltaDegrees°)")
	}

	fun stopAndExportZip(outputDirectory: File = File("datasets")): File? {
		if (!isRecording) return null
		isRecording = false
		val durationSec = (System.currentTimeMillis() - sessionStartTimeMs) / 1000.0f
		val compBytes = compressor?.finishAndGetCompressedBytes() ?: return null

		if (!outputDirectory.exists()) {
			outputDirectory.mkdirs()
		}

		val timestampStr = Instant.now().toString().replace(":", "-")
		val zipFile = File(outputDirectory, "nekovr_dataset_$timestampStr.zip")

		val trackersMeta = VRServer.instance.allTrackers.map { t ->
			TrackerMetadata(
				id = t.id,
				name = t.name,
				bodyPosition = t.trackerPosition?.designation ?: "UNKNOWN",
				imuType = t.trackerDataType?.name ?: "IMU",
			)
		}

		val manifest = DatasetManifest(
			recordedAtIso = Instant.now().toString(),
			durationSeconds = durationSec,
			frameCount = frameCount,
			trackers = trackersMeta,
			resetEvents = resetEvents.toList(),
		)

		FileOutputStream(zipFile).use { fos ->
			ZipOutputStream(fos).use { zos ->
				// 1. Write manifest.json
				zos.putNextEntry(ZipEntry("manifest.json"))
				zos.write(manifest.toJsonString().toByteArray(Charsets.UTF_8))
				zos.closeEntry()

				// 2. Write compressed binary telemetry
				zos.putNextEntry(ZipEntry("telemetry.zst"))
				zos.write(compBytes)
				zos.closeEntry()
			}
		}

		LogManager.info("[Dataset] Exported complete dataset session (${frameCount} frames, ${durationSec}s) to: ${zipFile.absolutePath}")
		return zipFile
	}
}
