package io.github.mvolkert.entryrecorder.service

import io.github.mvolkert.entryrecorder.data.model.MonitorStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * App-scoped registry of per-device monitoring status, observable by the UI.
 *
 * IntercomMonitorService is the only writer (devices start/stop monitoring, motion
 * events come and go); ViewModels collect [statuses]. Exists because a foreground
 * service cannot be injected into Compose state — same problem class the recorder's
 * activeDeviceIds StateFlow solved for the REC badge.
 */
object MonitorStatusHolder {

    private val _statuses = MutableStateFlow<Map<Long, MonitorStatus>>(emptyMap())
    val statuses: StateFlow<Map<Long, MonitorStatus>> = _statuses.asStateFlow()

    fun update(deviceId: Long, status: MonitorStatus) {
        _statuses.update { it + (deviceId to status) }
    }

    fun remove(deviceId: Long) {
        _statuses.update { it - deviceId }
    }

    fun clear() {
        _statuses.value = emptyMap()
    }
}
