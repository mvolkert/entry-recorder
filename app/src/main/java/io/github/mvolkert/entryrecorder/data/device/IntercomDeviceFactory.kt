package io.github.mvolkert.entryrecorder.data.device

import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.model.DeviceType
import io.github.mvolkert.entryrecorder.di.ApplicationScope
import io.github.mvolkert.entryrecorder.di.IoDispatcher
import io.github.mvolkert.entryrecorder.di.SnapshotClient
import io.github.mvolkert.entryrecorder.di.StreamClient
import io.github.mvolkert.entryrecorder.domain.device.IntercomDevice
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient

/**
 * Turns a stored device row into the implementation that speaks its protocol.
 *
 * A device belongs to one row, so it cannot be a graph singleton — this is the seam where the injected
 * collaborators (shared HTTP profiles, the IO dispatcher, the process scope) are handed to an object the
 * graph does not own.
 */
@Singleton
class IntercomDeviceFactory @Inject constructor(
    @StreamClient private val eventStreamClient: OkHttpClient,
    @SnapshotClient private val snapshotClient: OkHttpClient,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    fun createDevice(entity: DeviceEntity): IntercomDevice {
        return when (entity.deviceType) {
            DeviceType.TWO_N_VERSO -> TwoNIPVersoDevice(
                deviceEntity = entity,
                eventStreamClient = eventStreamClient,
                snapshotPollClient = snapshotClient,
                ioDispatcher = ioDispatcher,
                parentScope = applicationScope,
            )
            DeviceType.GENERIC_RTSP_ONVIF -> GenericRtspDevice(
                deviceEntity = entity,
                ioDispatcher = ioDispatcher,
            )
        }
    }
}
