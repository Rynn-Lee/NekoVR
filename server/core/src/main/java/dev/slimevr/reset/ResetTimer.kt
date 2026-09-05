package dev.slimevr.reset

import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.schedule
import kotlin.math.floor
import kotlin.math.min

class ResetTimerManager {
	val timer: Timer = Timer()
	val timers: ArrayList<TimerTask> = arrayListOf()
	var onCancel: (() -> Unit)? = null

	fun cancelTimers() {
		if (timers.isNotEmpty()) {
			val cancelCb = onCancel
			onCancel = null
			cancelCb?.invoke()
		}
		timers.forEach { it.cancel() }
		timers.clear()
	}
}

fun resetTimer(
	resetTimerManager: ResetTimerManager,
	delay: Long,
	onTick: (progress: Int) -> Unit,
	onComplete: () -> Unit,
	onCancel: (() -> Unit)? = null,
) {
	resetTimerManager.cancelTimers()
	resetTimerManager.onCancel = onCancel

	if (delay == 0L) {
		resetTimerManager.onCancel = null
		onComplete()
		return
	}

	val ticks: Int = floor(delay / 1000f).toInt()
	for (tick in 0..ticks) {
		if (tick * 1000L == delay) continue
		resetTimerManager.timers.add(
			resetTimerManager.timer.schedule(tick * 1000L) {
				onTick(tick * 1000)
			},
		)
	}
	resetTimerManager.timers.add(
		resetTimerManager.timer.schedule(delay) {
			resetTimerManager.onCancel = null
			onComplete()
		},
	)
}
