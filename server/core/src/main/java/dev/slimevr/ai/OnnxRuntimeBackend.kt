package dev.slimevr.ai

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtProvider
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.file.Path
import java.util.Properties

data class OnnxRuntimePackage(
	val flavor: String,
	val version: String,
) {
	fun allows(provider: ExecutionProviderType): Boolean = when (provider) {
		ExecutionProviderType.AUTO -> false
		ExecutionProviderType.CPU -> true
		ExecutionProviderType.CUDA, ExecutionProviderType.TENSORRT -> flavor == "nvidia"
		ExecutionProviderType.DIRECTML -> flavor == "directml"
	}

	companion object {
		fun load(): OnnxRuntimePackage {
			val properties = Properties()
			OnnxRuntimePackage::class.java.getResourceAsStream("onnx-runtime.properties")?.use(properties::load)
				?: throw ModelLoadException(ModelLoadErrorCode.RUNTIME_UNAVAILABLE, "ONNX Runtime package descriptor is missing")
			return OnnxRuntimePackage(properties.getProperty("flavor"), properties.getProperty("version"))
		}
	}
}

data class RuntimeTensorInfo(val dtype: String, val shape: LongArray)

interface LoadedModelSession : AutoCloseable {
	val inputInfo: Map<String, RuntimeTensorInfo>
	val outputInfo: Map<String, RuntimeTensorInfo>
	fun runProbe(metadata: ModelArtifactMetadata)
	fun runInference(input: InferenceTensorBatch): InferenceTensorOutput
}

interface OnnxRuntimeBackend : AutoCloseable {
	val runtimePackage: OnnxRuntimePackage
	val availableProviders: Set<ExecutionProviderType>
	fun createSession(modelPath: Path, provider: ExecutionProviderType): LoadedModelSession
}

class JavaOnnxRuntimeBackend : OnnxRuntimeBackend {
	private val environment = OrtEnvironment.getEnvironment("NekoVR-AI-Engine")
	override val runtimePackage: OnnxRuntimePackage = OnnxRuntimePackage.load()
	override val availableProviders: Set<ExecutionProviderType> = OrtEnvironment.getAvailableProviders().mapNotNullTo(mutableSetOf()) {
		when (it) {
			OrtProvider.CPU -> ExecutionProviderType.CPU
			OrtProvider.CUDA -> ExecutionProviderType.CUDA
			OrtProvider.TENSOR_RT -> ExecutionProviderType.TENSORRT
			OrtProvider.DIRECT_ML -> ExecutionProviderType.DIRECTML
			else -> null
		}
	}

	override fun createSession(modelPath: Path, provider: ExecutionProviderType): LoadedModelSession {
		if (!runtimePackage.allows(provider)) {
			throw ModelLoadException(ModelLoadErrorCode.PROVIDER_NOT_PACKAGED, "$provider is not included in ${runtimePackage.flavor} runtime package")
		}
		if (provider !in availableProviders) {
			throw ModelLoadException(ModelLoadErrorCode.PROVIDER_UNAVAILABLE, "$provider is not available in the loaded native runtime")
		}
		val options = OrtSession.SessionOptions()
		try {
			when (provider) {
				ExecutionProviderType.TENSORRT -> options.addTensorrt(0)
				ExecutionProviderType.CUDA -> options.addCUDA(0)
				ExecutionProviderType.DIRECTML -> {
					options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
					options.setMemoryPatternOptimization(false)
					options.addDirectML(0)
				}
				ExecutionProviderType.CPU -> Unit
				ExecutionProviderType.AUTO -> throw IllegalArgumentException("AUTO is a selection policy, not an execution provider")
			}
			return JavaLoadedModelSession(environment, environment.createSession(modelPath.toString(), options))
		} catch (error: ModelLoadException) {
			throw error
		} catch (error: Throwable) {
			throw ModelLoadException(ModelLoadErrorCode.SESSION_CREATION_FAILED, "Unable to create $provider ONNX session", error)
		} finally {
			options.close()
		}
	}

	override fun close() {
		environment.close()
	}
}

private class JavaLoadedModelSession(
	private val environment: OrtEnvironment,
	private val session: OrtSession,
) : LoadedModelSession {
	override val inputInfo: Map<String, RuntimeTensorInfo> = session.inputInfo.mapValues { (_, value) -> tensorInfo(value.info) }
	override val outputInfo: Map<String, RuntimeTensorInfo> = session.outputInfo.mapValues { (_, value) -> tensorInfo(value.info) }

	override fun runProbe(metadata: ModelArtifactMetadata) {
		val features = metadata.inputs.firstOrNull { it.name == "features" }
			?: throw ModelLoadException(ModelLoadErrorCode.TENSOR_CONTRACT_MISMATCH, "features input is missing")
		val featureCount = (features.shape.lastOrNull() as? Number)?.toInt()
			?: throw ModelLoadException(ModelLoadErrorCode.TENSOR_CONTRACT_MISMATCH, "features width must be fixed")
		val time = metadata.minimumContext
		val slots = metadata.maximumSlots
		val activeSlots = metadata.minimumSlots
		val inputs = linkedMapOf<String, OnnxTensor>()
		try {
			inputs["features"] = OnnxTensor.createTensor(environment, FloatBuffer.wrap(FloatArray(time * slots * featureCount)), longArrayOf(1, time.toLong(), slots.toLong(), featureCount.toLong()))
			inputs["role_ids"] = OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(slots) { if (it < activeSlots) 1L else 0L }), longArrayOf(1, slots.toLong()))
			inputs["slot_mask"] = boolTensor(BooleanArray(slots) { it < activeSlots }, longArrayOf(1, slots.toLong()))
			inputs["channel_validity"] = boolTensor(BooleanArray(time * slots * featureCount) { index -> (index / featureCount) % slots < activeSlots }, longArrayOf(1, time.toLong(), slots.toLong(), featureCount.toLong()))
			inputs["time_deltas_s"] = OnnxTensor.createTensor(environment, FloatBuffer.wrap(FloatArray(time) { 0.02f }), longArrayOf(1, time.toLong()))
			inputs["time_mask"] = boolTensor(BooleanArray(time) { true }, longArrayOf(1, time.toLong()))
			session.run(inputs).use { result ->
				for (output in metadata.outputs) {
					val tensor = result.get(output.name).orElseThrow {
						ModelLoadException(ModelLoadErrorCode.PROBE_FAILED, "Probe output ${output.name} is missing")
					} as? OnnxTensor ?: throw ModelLoadException(ModelLoadErrorCode.PROBE_FAILED, "Probe output ${output.name} is not a tensor")
					val values = tensor.floatBuffer ?: throw ModelLoadException(ModelLoadErrorCode.PROBE_FAILED, "Probe output ${output.name} is not float32")
					while (values.hasRemaining()) {
						if (!values.get().isFinite()) throw ModelLoadException(ModelLoadErrorCode.PROBE_FAILED, "Probe output ${output.name} is non-finite")
					}
				}
			}
		} catch (error: ModelLoadException) {
			throw error
		} catch (error: Throwable) {
			throw ModelLoadException(ModelLoadErrorCode.PROBE_FAILED, "ONNX probe inference failed", error)
		} finally {
			inputs.values.forEach { it.close() }
		}
	}

	override fun runInference(input: InferenceTensorBatch): InferenceTensorOutput {
		val inputs = linkedMapOf<String, OnnxTensor>()
		try {
			inputs["features"] = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input.features), longArrayOf(1, input.time.toLong(), input.slots.toLong(), input.featureCount.toLong()))
			inputs["role_ids"] = OnnxTensor.createTensor(environment, LongBuffer.wrap(input.roleIds), longArrayOf(1, input.slots.toLong()))
			inputs["slot_mask"] = boolTensor(input.slotMask, longArrayOf(1, input.slots.toLong()))
			inputs["channel_validity"] = boolTensor(input.channelValidity, longArrayOf(1, input.time.toLong(), input.slots.toLong(), input.featureCount.toLong()))
			inputs["time_deltas_s"] = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input.timeDeltasSeconds), longArrayOf(1, input.time.toLong()))
			inputs["time_mask"] = boolTensor(input.timeMask, longArrayOf(1, input.time.toLong()))
			session.run(inputs).use { result ->
				fun floats(name: String, expectedSize: Int): FloatArray {
					val tensor = result.get(name).orElseThrow { IllegalStateException("Inference output $name is missing") } as? OnnxTensor
						?: throw IllegalStateException("Inference output $name is not a tensor")
					val buffer = tensor.floatBuffer ?: throw IllegalStateException("Inference output $name is not float32")
					if (buffer.remaining() != expectedSize) throw IllegalStateException("Inference output $name has an unexpected size")
					return FloatArray(expectedSize).also(buffer::get)
				}
				return InferenceTensorOutput(
					correctionRotationVectors = floats("correction_rotation_vectors", input.slots * 3),
					confidence = floats("confidence", input.slots),
					driftRate = floats("drift_rate", input.slots),
				)
			}
		} finally {
			inputs.values.forEach { it.close() }
		}
	}

	private fun boolTensor(values: BooleanArray, shape: LongArray): OnnxTensor {
		val buffer = ByteBuffer.allocateDirect(values.size).order(ByteOrder.nativeOrder())
		values.forEach { buffer.put(if (it) 1 else 0) }
		buffer.flip()
		return OnnxTensor.createTensor(environment, buffer, shape, OnnxJavaType.BOOL)
	}

	private fun tensorInfo(value: ai.onnxruntime.ValueInfo): RuntimeTensorInfo {
		val info = value as? ai.onnxruntime.TensorInfo
			?: throw ModelLoadException(ModelLoadErrorCode.TENSOR_CONTRACT_MISMATCH, "ONNX value is not a tensor")
		val dtype = when (info.type) {
			OnnxJavaType.FLOAT -> "float32"
			OnnxJavaType.INT64 -> "int64"
			OnnxJavaType.BOOL -> "bool"
			else -> info.type.toString().lowercase()
		}
		return RuntimeTensorInfo(dtype, info.shape)
	}

	override fun close() {
		session.close()
	}
}
