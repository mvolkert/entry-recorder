package io.github.mvolkert.entryrecorder.service

import io.github.mvolkert.entryrecorder.data.model.ConnectionQuality
import io.github.mvolkert.entryrecorder.data.model.MonitorStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * App-scoped registry of per-device monitoring status, observable by the UI.
 *
 * IntercomMonitorService is the only writer (devices start/stop monitoring, events come and go);
 * ViewModels collect [statuses]. Exists because a foreground service cannot be injected into Compose
 * state — same problem class the recorder's activeDeviceIds StateFlow solved for the REC badge.
 *
 * [statuses] is the single overall badge for the live card. [eventQualities] and [snapshotQualities]
 * keep the two delivery paths separate so Settings can say which one specifically is down: the
 * device's own event stream (doorbell / camera motion / noise) versus the snapshot path (live view,
 * recording, in-app motion analysis).
 */
object MonitorStatusHolder {

    private val _statuses = MutableStateFlow<Map<Long, MonitorStatus>>(emptyMap())
    val statuses: StateFlow<Map<Long, MonitorStatus>> = _statuses.asStateFlow()

    private val _eventQualities = MutableStateFlow<Map<Long, ConnectionQuality>>(emptyMap())
    val eventQualities: StateFlow<Map<Long, ConnectionQuality>> = _eventQualities.asStateFlow()

    private val _snapshotQualities = MutableStateFlow<Map<Long, ConnectionQuality>>(emptyMap())
    val snapshotQualities: StateFlow<Map<Long, ConnectionQuality>> = _snapshotQualities.asStateFlow()

    fun update(deviceId: Long, status: MonitorStatus) {
        _statuses.update { it + (deviceId to status) }
    }

    /** Latest health of the device's own event stream, for the doorbell / camera-motion / noise triggers. */
    fun updateEventQuality(deviceId: Long, quality: ConnectionQuality) {
        _eventQualities.update { it + (deviceId to quality) }
    }

    /** Latest health of the HTTP snapshot path, for the live view, recording and in-app motion analysis. */
    fun updateSnapshotQuality(deviceId: Long, quality: ConnectionQuality) {
        _snapshotQualities.update { it + (deviceId to quality) }
    }

    /** Current status of one device, or null while the service has not taken it over. */
    fun statusFor(deviceId: Long): MonitorStatus? = _statuses.value[deviceId]

    fun remove(deviceId: Long) {
        _statuses.update { it - deviceId }
        _eventQualities.update { it - deviceId }
        _snapshotQualities.update { it - deviceId }
    }

    fun clear() {
        _statuses.value = emptyMap()
        _eventQualities.value = emptyMap()
        _snapshotQualities.value = emptyMap()
    }
}
