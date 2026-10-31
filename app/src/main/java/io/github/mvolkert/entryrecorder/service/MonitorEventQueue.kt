package io.github.mvolkert.entryrecorder.service

import io.github.mvolkert.entryrecorder.domain.device.IntercomEvent
import io.github.mvolkert.entryrecorder.domain.device.logIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
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
 * while the foreground notification still reported the monitor running. A cancellation is the exception - it is
 * the scope being torn down, and the collector has to go with it.
 */
class MonitorEventQueue(
    scope: CoroutineScope,
    private val handler: suspend (IntercomEvent) -> Unit,
) {
    private val tag = MONITOR_LOG_TAG
    private val events = Channel<IntercomEvent>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (event in events) {
                try {
                    handler(event)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    Timber.tag(tag).e(failure, "Handler failed for ${event.logIdentity()}")
                }
            }
        }
    }

    /**
     * Hands [event] to the collector without ever suspending the caller: events arrive from the devices' own
     * poll loops and from the analyzer, none of which may wait on the handler's HTTP or SIP work. Returns false
     * only after [close], once teardown has ended the collector - a dropped trigger has to say so rather than
     * vanish.
     */
    fun submit(event: IntercomEvent): Boolean {
        val delivered = events.trySend(event).isSuccess
        if (!delivered) Timber.tag(tag).w("Dropped ${event.logIdentity()}, the monitor queue is closed")
        return delivered
    }

    /** Ends the collector's input on the way out, so a late event is refused and logged instead of queued forever. */
    fun close() {
        events.close()
    }
}
