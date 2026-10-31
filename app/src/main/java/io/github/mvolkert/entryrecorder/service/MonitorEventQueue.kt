package io.github.mvolkert.entryrecorder.service

import io.github.mvolkert.entryrecorder.domain.device.IntercomEvent
import io.github.mvolkert.entryrecorder.domain.device.logIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The one queue every device event is handled through, and the one coroutine that handles them.
 *
 * The service used to launch a handler per event on its `Dispatchers.IO` scope. That dispatcher is
 * multi-threaded, so a started event and the ended event that follows it could run at the same time or
 * complete in the opposite order — which is how a clip got its post-record stop scheduled before its
 * recording was armed, and then discarded as an empty file. Ordering is now a property of this queue: exactly
 * one collector awaits [handler] event by event, so handling is sequential in arrival order no matter which
 * thread a device callback arrived on.
 *
 * The queue is unbounded on purpose. Dropping a trigger is the failure this replaces, and a burst of device
 * events is small; if a handler ever blocks long enough for this to matter, the answer is a bounded queue that
 * logs the drop, not a silent one.
 *
 * The collector is launched on [scope] as soon as the queue exists, so there is no window where an event can
 * be queued before anything consumes it, and no start call to forget. The service cancels that scope on
 * destroy, which ends the collector.
 *
 * Each event is handled behind its own failure boundary: this collector is the only consumer, so one error that
 * escaped it would cancel the single coroutine and leave every later event sitting in a channel nobody reads,
 * while the foreground notification still reported the monitor running.
 *
 * A [CancellationException] is treated by origin, not by type. The queue's scope is a `SupervisorJob`, so a
 * cancellation raised inside the handler — a timeout, or an awaited job that died on its own — belongs to that
 * one event and the collector has to survive it; only an inactive scope means the service is going away, and then
 * the collector goes with it. [close] drains what the collector never got to, so teardown reports every trigger it
 * is dropping rather than only the ones that arrive after it.
 */
class MonitorEventQueue(
    scope: CoroutineScope,
    private val handler: suspend (IntercomEvent) -> Unit,
) {
    private val tag = MONITOR_LOG_TAG
    private val events = Channel<IntercomEvent>(Channel.UNLIMITED)

    init {
        scope.launch {
            try {
                for (event in events) {
                    try {
                        handler(event)
                    } catch (cancellation: CancellationException) {
                        if (isActive) {
                            // Ours to survive: the scope is still up, so this is one event's cancellation and the
                            // events behind it still have a collector. Losing it silently is the failure class this
                            // class exists for, so the origin has to reach the log.
                            val note = "Handler cancelled for ${event.logIdentity()}, the queue stays alive"
                            Timber.tag(tag).w(cancellation, note)
                        } else {
                            throw cancellation
                        }
                    } catch (failure: Throwable) {
                        Timber.tag(tag).e(failure, "Handler failed for ${event.logIdentity()}")
                    }
                }
            } finally {
                // Collector death is otherwise invisible, and a dead collector is how triggers stop being handled
                // while the notification still says the monitor is running.
                Timber.tag(tag).i("Monitor event collector stopped")
            }
        }
    }

    /**
     * Hands [event] to the collector without ever suspending the caller: events arrive from the devices' own
     * poll loops and from the analyzer, none of which may wait on the handler's HTTP or SIP work. Returns false
     * only after [close], once teardown has ended the collector — a dropped trigger has to say so rather than
     * vanish.
     */
    fun submit(event: IntercomEvent): Boolean {
        val delivered = events.trySend(event).isSuccess
        if (!delivered) Timber.tag(tag).w("Dropped ${event.logIdentity()}, the monitor queue is closed")
        return delivered
    }

    /**
     * Ends the collector's input and reports everything still queued when it died. Call this on the way out, after
     * the scope was cancelled: while the collector is alive its own receive races this drain.
     */
    fun close() {
        val stranded = mutableListOf<IntercomEvent>()
        while (true) {
            val event = events.tryReceive().getOrNull() ?: break
            stranded += event
        }
        events.close()
        if (stranded.isNotEmpty()) {
            val note = "Dropped ${stranded.size} queued events during teardown: " +
                stranded.joinToString { it.logIdentity() }
            Timber.tag(tag).w(note)
        }
    }
}
