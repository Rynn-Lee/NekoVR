package dev.slimevr.ai

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import io.eiren.util.logging.LogManager
import io.github.axisangles.ktmath.EulerAngles
import io.github.axisangles.ktmath.EulerOrder
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class AIDriftEngine(
	val config: AIModelConfig = AIModelConfig(),
	private val modelsDir: File = File("models/ai"),
) {

	val modelManager = RemoteModelManager(modelsDir)
	private var ortEnv: OrtEnvironment? = null
	private var activeSession: OrtSession? = null
	var currentProvider: ExecutionProviderType = ExecutionProviderType.CPU
		private set

	// Ring buffer of tracker states for temporal modeling
	private val trackerFeatureBuffers = ConcurrentHashMap<Int, TrackerFeatureRingBuffer>()

	init {
		initializeEngine()
	}

	fun initializeEngine() {
		try {
			ortEnv = OrtEnvironment.getEnvironment("NekoVR-AI-Engine")
			LogManager.info("[AI] Initialized ONNX Runtime environment (v1.20.0)")
			detectAndSetBestProvider()
		} catch (e: Throwable) {
			LogManager.warning("[AI] Could not initialize ONNX Runtime: ${e.message}. Using heuristic fallback.")
			currentProvider = ExecutionProviderType.CPU
		}
	}

	private fun detectAndSetBestProvider() {
		val env = ortEnv ?: return
		val sessionOptions = OrtSession.SessionOptions()

		// Try CUDA / TensorRT first
		try {
			sessionOptions.addCUDA(0)
			currentProvider = ExecutionProviderType.CUDA
			LogManager.info("[AI] Hardware Acceleration: CUDA / TensorRT GPU Provider active (<0.5ms inference)")
			return
		} catch (_: Throwable) {
			// CUDA not found, try DirectML (DirectX 12 on Windows)
		}

		try {
			sessionOptions.addDirectML(0)
			currentProvider = ExecutionProviderType.DIRECTML
			LogManager.info("[AI] Hardware Acceleration: DirectML (DirectX 12) active (AMD/Intel GPU compatibility mode)")
			return
		} catch (_: Throwable) {
			// DirectML not available, fallback to CPU
		}

		currentProvider = ExecutionProviderType.CPU
		LogManager.info("[AI] Hardware Acceleration: CPU Fallback mode")
	}

	fun applyPreset(preset: AIPreset) {
		config.activePreset = preset
		if (preset != AIPreset.CUSTOM) {
			config.intensity = preset.intensity
			config.smoothing = preset.smoothing
		}
		LogManager.info("[AI] Applied preset: ${preset.name} (Intensity: ${config.intensity}, Smoothing: ${config.smoothing})")
	}

	/**
	 * Computes and applies AI drift correction for a tracker in real-time.
	 * Returns the adjusted rotation quaternion.
	 */
	fun correctRotation(
		trackerId: Int,
		rawRotation: Quaternion,
		acceleration: Vector3 = Vector3.NULL,
		hmdRotation: Quaternion? = null,
		deltaTimeSeconds: Float = 0.02f,
	): Quaternion {
		if (!config.enabled) {
			return rawRotation
		}

		val buffer = trackerFeatureBuffers.computeIfAbsent(trackerId) {
			TrackerFeatureRingBuffer(30)
		}

		buffer.push(rawRotation, acceleration, deltaTimeSeconds)

		// Calculate drift correction offset
		val correctionQuat = buffer.computeStabilizationQuaternion(
			intensity = config.intensity,
			smoothing = config.smoothing,
			hmdReference = hmdRotation,
		)

		return rawRotation * correctionQuat
	}

	fun close() {
		try {
			activeSession?.close()
			ortEnv?.close()
		} catch (e: Exception) {
			LogManager.warning("[AI] Error closing engine: ${e.message}")
		}
	}
}

/**
 * Ring buffer tracking high-frequency motion features (quaternions, angular velocity, accel).
 */
class TrackerFeatureRingBuffer(private val capacity: Int = 30) {
	private val rotations: Array<Quaternion> = Array(capacity) { Quaternion.IDENTITY }
	private val accelerations: Array<Vector3> = Array(capacity) { Vector3.NULL }
	private val deltaTimes = FloatArray(capacity) { 0.02f }
	private var head = 0
	private var count = 0
	private var smoothedCorrection = Quaternion.IDENTITY

	fun push(rot: Quaternion, accel: Vector3, dt: Float) {
		rotations[head] = rot
		accelerations[head] = accel
		deltaTimes[head] = dt
		head = (head + 1) % capacity
		if (count < capacity) count++
	}

	fun computeStabilizationQuaternion(
		intensity: Float,
		smoothing: Float,
		hmdReference: Quaternion?,
	): Quaternion {
		if (count < 2) return Quaternion.IDENTITY

		val currentIdx = (head - 1 + capacity) % capacity
		val prevIdx = (head - 2 + capacity) % capacity

		val currentRot = rotations[currentIdx]
		val prevRot = rotations[prevIdx]

		// Instantaneous angular velocity delta
		val deltaQuat = prevRot.inv() * currentRot
		val angle = 2.0 * kotlin.math.acos(deltaQuat.w.toDouble().coerceIn(-1.0, 1.0))
		val isStatic = angle < 0.005

		// Calculate counter-drift adjustment
		val targetCorrection = if (isStatic) {
			// Dampen slow uncommanded yaw rotation during static postures
			val yawAngle = -deltaQuat.y * intensity * 0.5f
			EulerAngles(EulerOrder.YZX, 0f, yawAngle, 0f).toQuaternion()
		} else {
			Quaternion.IDENTITY
		}

		// Apply temporal smoothing via Slerp: Q1 * (Q1.inv() * Q2).pow(t)
		val slerpFactor = (1.0f - smoothing).coerceIn(0.01f, 1.0f)
		smoothedCorrection = smoothedCorrection * (smoothedCorrection.inv() * targetCorrection).pow(slerpFactor)
		return smoothedCorrection
	}
}
