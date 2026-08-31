package dev.slimevr.dataset

import com.github.luben.zstd.ZstdOutputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream

class ZstdStreamCompressor(compressionLevel: Int = 3) {

	private val byteArrayOutputStream = ByteArrayOutputStream(64 * 1024)
	private val zstdStream = ZstdOutputStream(byteArrayOutputStream, compressionLevel)

	fun write(data: ByteArray, offset: Int = 0, length: Int = data.size) {
		zstdStream.write(data, offset, length)
	}

	fun finishAndGetCompressedBytes(): ByteArray {
		zstdStream.flush()
		zstdStream.close()
		return byteArrayOutputStream.toByteArray()
	}

	fun reset() {
		byteArrayOutputStream.reset()
	}
}
