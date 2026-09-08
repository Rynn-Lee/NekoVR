package dev.slimevr.ai

import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

data class TrackerSlotMapping(
	val trackerId: Int,
	val bodyRoleId: Int,
	val slot: Int,
)

data class InferenceTrackerSample(
	val trackerId: Int,
	val epoch: Long,
	val features: FloatArray,
	val channelValidity: BooleanArray,
)

data class InferenceSnapshot(
	val sequence: Long,
	val monotonicNanos: Long,
	val deltaTimeSeconds: Float,
	val samples: List<InferenceTrackerSample>,
)

data class InferenceTensorBatch(
	val time: Int,
	val slots: Int,
	val featureCount: Int,
	val features: FloatArray,
	val roleIds: LongArray,
	val slotMask: BooleanArray,
	val channelValidity: BooleanArray,
	val timeDeltasSeconds: FloatArray,
	val timeMask: BooleanArray,
)

data class InferenceTensorOutput(
	val correctionRotationVectors: FloatArray,
	val confidence: FloatArray,
	val driftRate: FloatArray,
)

data class TrackerInferenceOutput(
	val trackerId: Int,
	val bodyRoleId: Int,
	val slot: Int,
	val epoch: Long,
	val sequence: Long,
	val monotonicNanos: Long,
	val correctionRotationVector: FloatArray,
	val confidence: Float,
	val driftRate: Float,
	val latencyMicros: Long,
)

/** A single-consumer worker whose one-element queue always retains the newest immutable snapshot. */
class LatestValueInferenceWorker(
	private val session: LoadedModelSession,
	private val metadata: ModelArtifactMetadata,
	initialMappings: List<TrackerSlotMapping> = emptyList(),
) : AutoCloseable {
	private data class MappingState(val version: Long, val byTracker: Map<Int, TrackerSlotMapping>)
	private data class QueuedSnapshot(val mappingVersion: Long, val snapshot: InferenceSnapshot)
	private data class HistoryFrame(
		val deltaTimeSeconds: Float,
		val samples: Map<Int, InferenceTrackerSample>,
	)

	private val running = AtomicBoolean(true)
	private val queue = ArrayBlockingQueue<QueuedSnapshot>(1)
	private val submitLock = Any()
	private val mappingState = AtomicReference(MappingState(0L, emptyMap()))
	private val epochs = ConcurrentHashMap<Int, Long>()
	private val latestOutputs = AtomicReference<Map<Int, TrackerInferenceOutput>>(emptyMap())
	private val droppedSnapshots = AtomicLong()
	private val processedSnapshots = AtomicLong()
	private val inferenceErrors = AtomicLong()
	private val consecutiveInferenceErrors = AtomicLong()
	private val latencySamplesMicros = ArrayDeque<Long>(256)
	private val queueWaitSamplesMicros = ArrayDeque<Long>(256)
	private val startedNanos = System.nanoTime()
	private val lastSuccessfulInferenceNanos = AtomicLong()
	private val lastInferenceError = AtomicReference<String?>(null)
	private val thread: Thread

	init {
		configureMappings(initialMappings)
		thread = Thread(::runLoop, "NekoVR-AI-Inference").apply {
			isDaemon = true
			start()
		}
	}

	val droppedSnapshotCount: Long
		get() = droppedSnapshots.get()
	val processedSnapshotCount: Long
		get() = processedSnapshots.get()
	val inferenceErrorCount: Long
		get() = inferenceErrors.get()
	val consecutiveInferenceErrorCount: Long
		get() = consecutiveInferenceErrors.get()
	val queueDepth: Int
		get() = queue.size
	val lastError: String?
		get() = lastInferenceError.get()
	val isAlive: Boolean
		get() = thread.isAlive
	val inferenceRateHz: Double
		get() {
			val elapsedSeconds = (System.nanoTime() - startedNanos).coerceAtLeast(1L) / 1_000_000_000.0
			return processedSnapshots.get() / elapsedSeconds
		}

	fun latencyPercentiles(): InferenceLatencyPercentiles = synchronized(latencySamplesMicros) {
		percentiles(latencySamplesMicros)
	}

	fun queueWaitLatencyPercentiles(): InferenceLatencyPercentiles = synchronized(queueWaitSamplesMicros) {
		percentiles(queueWaitSamplesMicros)
	}

	internal fun resetLatencyMetrics() {
		synchronized(latencySamplesMicros) { latencySamplesMicros.clear() }
		synchronized(queueWaitSamplesMicros) { queueWaitSamplesMicros.clear() }
	}

	fun latestOutputs(): Map<Int, TrackerInferenceOutput> = latestOutputs.get()

	fun mappings(): List<TrackerSlotMapping> = mappingState.get().byTracker.values.sortedBy { it.slot }

	fun configureMappings(mappings: List<TrackerSlotMapping>) {
		validateMappings(mappings)
		while (true) {
			val current = mappingState.get()
			val replacement = MappingState(current.version + 1, mappings.associateBy { it.trackerId })
			if (mappingState.compareAndSet(current, replacement)) {
				queue.clear()
				latestOutputs.set(emptyMap())
				return
			}
		}
	}

	fun resetHistory(trackerId: Int, epoch: Long) {
		epochs.compute(trackerId) { _, current -> maxOf(current ?: Long.MIN_VALUE, epoch) }
		latestOutputs.updateAndGet { outputs -> outputs - trackerId }
	}

	fun submit(snapshot: InferenceSnapshot): Boolean {
		if (!running.get()) return false
		require(snapshot.deltaTimeSeconds.isFinite() && snapshot.deltaTimeSeconds >= 0f) { "deltaTimeSeconds must be finite and non-negative" }
		require(snapshot.samples.map { it.trackerId }.toSet().size == snapshot.samples.size) { "Snapshot tracker IDs must be unique" }
		val mapping = mappingState.get()
		val queued = QueuedSnapshot(mapping.version, snapshot.copy(samples = snapshot.samples.map(::copySample)))
		return synchronized(submitLock) {
			if (queue.offer(queued)) return@synchronized true
			if (queue.poll() != null) droppedSnapshots.incrementAndGet()
			queue.offer(queued)
		}
	}

	fun latest(trackerId: Int, epoch: Long): TrackerInferenceOutput? = latestOutputs.get()[trackerId]?.takeIf { it.epoch == epoch }

	private fun runLoop() {
		val history = ArrayDeque<HistoryFrame>(metadata.maximumContext)
		var historyMappingVersion = -1L
		while (running.get()) {
			try {
				val queued = queue.take()
				recordLatency(queueWaitSamplesMicros, (System.nanoTime() - queued.snapshot.monotonicNanos).coerceAtLeast(0L) / 1_000L)
				val mapping = mappingState.get()
				if (queued.mappingVersion != mapping.version) continue
				if (historyMappingVersion != mapping.version) {
					history.clear()
					historyMappingVersion = mapping.version
				}
				val samples = queued.snapshot.samples.associateBy { it.trackerId }
				for (sample in samples.values) epochs.compute(sample.trackerId) { _, current -> maxOf(current ?: sample.epoch, sample.epoch) }
				history.addLast(HistoryFrame(queued.snapshot.deltaTimeSeconds, samples))
				processedSnapshots.incrementAndGet()
				while (history.size > metadata.maximumContext) history.removeFirst()
				if (history.size < metadata.minimumContext || mapping.byTracker.size < metadata.minimumSlots) continue
				val contextReadyTrackers = mapping.byTracker.values.filter { slotMapping ->
					history.count { frame ->
						val sample = frame.samples[slotMapping.trackerId]
						val epoch = epochs[slotMapping.trackerId]
						sample != null && sample.epoch == epoch && sample.features.indices.any { sample.channelValidity[it] && sample.features[it].isFinite() }
					} >= metadata.minimumContext
				}.mapTo(mutableSetOf()) { it.trackerId }
				if (contextReadyTrackers.size < metadata.minimumSlots) continue
				val input = buildInput(history, mapping)
				val started = System.nanoTime()
				val output = session.runInference(input)
				val latencyMicros = (System.nanoTime() - started) / 1_000L
				recordLatency(latencySamplesMicros, latencyMicros)
				consecutiveInferenceErrors.set(0L)
				lastInferenceError.set(null)
				lastSuccessfulInferenceNanos.set(System.nanoTime())
				if (mappingState.get().version != mapping.version) continue
				val newestSamples = history.last().samples
				val results = mapping.byTracker.values.mapNotNull { slotMapping ->
					if (slotMapping.trackerId !in contextReadyTrackers) return@mapNotNull null
					val epoch = epochs[slotMapping.trackerId] ?: 0L
					val sample = newestSamples[slotMapping.trackerId]?.takeIf { it.epoch == epoch } ?: return@mapNotNull null
					slotMapping.trackerId to TrackerInferenceOutput(
						trackerId = slotMapping.trackerId, bodyRoleId = slotMapping.bodyRoleId, slot = slotMapping.slot,
						epoch = epoch, sequence = queued.snapshot.sequence, monotonicNanos = queued.snapshot.monotonicNanos,
						correctionRotationVector = output.correctionRotationVectors.copyOfRange(slotMapping.slot * 3, slotMapping.slot * 3 + 3),
						confidence = output.confidence[slotMapping.slot], driftRate = output.driftRate[slotMapping.slot],
						latencyMicros = latencyMicros,
					)
				}.toMap()
				latestOutputs.set(results)
			} catch (_: InterruptedException) {
				if (!running.get()) return
			} catch (error: Throwable) {
				inferenceErrors.incrementAndGet()
				consecutiveInferenceErrors.incrementAndGet()
				lastInferenceError.set(error.message ?: error::class.java.simpleName)
				latestOutputs.set(emptyMap())
			}
		}
	}

	private fun buildInput(history: ArrayDeque<HistoryFrame>, mapping: MappingState): InferenceTensorBatch {
		val time = min(history.size, metadata.maximumContext)
		val slots = metadata.maximumSlots
		val featureCount = metadata.featureCount
		val features = FloatArray(time * slots * featureCount)
		val validity = BooleanArray(features.size)
		val deltas = FloatArray(time)
		val frames = history.toList().takeLast(time)
		for ((timeIndex, frame) in frames.withIndex()) {
			deltas[timeIndex] = frame.deltaTimeSeconds
			for (slotMapping in mapping.byTracker.values) {
				val sample = frame.samples[slotMapping.trackerId] ?: continue
				val currentEpoch = epochs[slotMapping.trackerId] ?: sample.epoch
				if (sample.epoch != currentEpoch) continue
				for (feature in 0 until featureCount) {
					val index = (timeIndex * slots + slotMapping.slot) * featureCount + feature
					val raw = sample.features[feature]
					val normalized = (raw - metadata.normalizationMean[feature]) / metadata.normalizationStandardDeviation[feature]
					val valid = sample.channelValidity[feature] && raw.isFinite() && normalized.isFinite()
					validity[index] = valid
					features[index] = if (valid) normalized else 0f
				}
			}
		}
		val bySlot = mapping.byTracker.values.associateBy { it.slot }
		return InferenceTensorBatch(
			time = time, slots = slots, featureCount = featureCount, features = features,
			roleIds = LongArray(slots) { bySlot[it]?.bodyRoleId?.toLong() ?: 0L },
			slotMask = BooleanArray(slots) { it in bySlot }, channelValidity = validity,
			timeDeltasSeconds = deltas, timeMask = BooleanArray(time) { true },
		)
	}

	private fun validateMappings(mappings: List<TrackerSlotMapping>) {
		require(mappings.size <= metadata.maximumSlots) { "Mapping exceeds model slot capacity" }
		require(mappings.map { it.trackerId }.toSet().size == mappings.size) { "Tracker IDs must be unique" }
		require(mappings.map { it.slot }.toSet().size == mappings.size) { "Slots must be unique" }
		for (mapping in mappings) {
			require(mapping.slot in 0 until metadata.maximumSlots) { "Slot ${mapping.slot} is outside model bounds" }
			require(mapping.bodyRoleId in metadata.supportedRoles) { "Body role ${mapping.bodyRoleId} is unsupported" }
		}
	}

	private fun copySample(sample: InferenceTrackerSample) = sample.copy(
		features = sample.features.copyOf(),
		channelValidity = sample.channelValidity.copyOf(),
	).also {
		require(it.features.size == metadata.featureCount && it.channelValidity.size == metadata.featureCount) { "Sample feature width differs from model metadata" }
	}

	private fun recordLatency(samples: ArrayDeque<Long>, value: Long) = synchronized(samples) {
		if (samples.size == 256) samples.removeFirst()
		samples.addLast(value)
	}

	private fun percentiles(samples: ArrayDeque<Long>): InferenceLatencyPercentiles {
		if (samples.isEmpty()) return InferenceLatencyPercentiles()
		val sorted = samples.sorted()
		fun percentile(value: Double): Long = sorted[((sorted.lastIndex * value).toInt()).coerceIn(0, sorted.lastIndex)]
		return InferenceLatencyPercentiles(percentile(0.50), percentile(0.95), percentile(0.99))
	}

	override fun close() {
		if (!running.compareAndSet(true, false)) return
		queue.clear()
		thread.interrupt()
		thread.join()
		latestOutputs.set(emptyMap())
	}
}

data class InferenceLatencyPercentiles(
	val p50Micros: Long = 0L,
	val p95Micros: Long = 0L,
	val p99Micros: Long = 0L,
)
