package dev.slimevr.reset

import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.schedule
import kotlin.math.floor
import kotlin.math.min

class ResetTimerManager {
	private val timer: Timer = Timer()
	private val lock = Any()
	private val timers: ArrayList<TimerTask> = arrayListOf()
	private var generation = 0L
	private var active = false
	private var onCancel: (() -> Unit)? = null

	fun cancelTimers() {
		val (tasks, cancelCallback) = synchronized(lock) {
			if (!active) return
			active = false
			generation++
			val pending = timers.toList()
			timers.clear()
			val callback = onCancel
			onCancel = null
			pending to callback
		}
		tasks.forEach { it.cancel() }
		cancelCallback?.invoke()
	}

	internal fun begin(cancelCallback: (() -> Unit)?): Long {
		val (previousTasks, previousCancel, nextGeneration) = synchronized(lock) {
			val pending = if (active) timers.toList() else emptyList()
			val callback = if (active) onCancel else null
			timers.clear()
			generation++
			active = true
			onCancel = cancelCallback
			Triple(pending, callback, generation)
		}
		previousTasks.forEach { it.cancel() }
		previousCancel?.invoke()
		return nextGeneration
	}

	internal fun schedule(generation: Long, delay: Long, callback: () -> Unit) {
		val task = timer.schedule(delay) { callback() }
		val retained = synchronized(lock) {
			if (active && this.generation == generation) {
				timers += task
				true
			} else {
				false
			}
		}
		if (!retained) task.cancel()
	}

	internal fun tick(generation: Long, callback: () -> Unit) {
		if (synchronized(lock) { active && this.generation == generation }) callback()
	}

	internal fun complete(generation: Long, callback: () -> Unit) {
		val tasks = synchronized(lock) {
			if (!active || this.generation != generation) return
			active = false
			this.generation++
			onCancel = null
			timers.toList().also { timers.clear() }
		}
		tasks.forEach { it.cancel() }
		callback()
	}
}

fun resetTimer(
	resetTimerManager: ResetTimerManager,
	delay: Long,
	onTick: (progress: Int) -> Unit,
	onComplete: () -> Unit,
	onCancel: (() -> Unit)? = null,
) {
	val generation = resetTimerManager.begin(onCancel)

	if (delay == 0L) {
		resetTimerManager.complete(generation, onComplete)
		return
	}

	val ticks: Int = floor(delay / 1000f).toInt()
	for (tick in 0..ticks) {
		if (tick * 1000L == delay) continue
		resetTimerManager.schedule(generation, tick * 1000L) {
			resetTimerManager.tick(generation) { onTick(tick * 1000) }
		}
	}
	resetTimerManager.schedule(generation, delay) {
		resetTimerManager.complete(generation, onComplete)
	}
}
