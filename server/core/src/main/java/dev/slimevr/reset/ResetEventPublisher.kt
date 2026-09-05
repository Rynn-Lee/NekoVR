package dev.slimevr.reset

import java.util.concurrent.CopyOnWriteArrayList

fun interface ResetEventListener {
	fun onResetEvent(event: ResetEvent)
}

interface ResetEventPublisher {
	fun publish(event: ResetEvent)
	fun addListener(listener: ResetEventListener)
	fun removeListener(listener: ResetEventListener)
}

class DefaultResetEventPublisher : ResetEventPublisher {
	private val listeners = CopyOnWriteArrayList<ResetEventListener>()

	override fun publish(event: ResetEvent) {
		for (listener in listeners) {
			try {
				listener.onResetEvent(event)
			} catch (e: Throwable) {
				io.eiren.util.logging.LogManager.warning("[ResetEventPublisher] Error notifying reset listener: ${e.message}")
			}
		}
	}

	override fun addListener(listener: ResetEventListener) {
		listeners.add(listener)
	}

	override fun removeListener(listener: ResetEventListener) {
		listeners.remove(listener)
	}
}

object NoopResetEventPublisher : ResetEventPublisher {
	override fun publish(event: ResetEvent) {}
	override fun addListener(listener: ResetEventListener) {}
	override fun removeListener(listener: ResetEventListener) {}
}
