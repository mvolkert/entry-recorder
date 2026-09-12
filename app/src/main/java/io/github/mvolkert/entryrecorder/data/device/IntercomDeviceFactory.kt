package io.github.mvolkert.entryrecorder.data.device

import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.model.DeviceType
import io.github.mvolkert.entryrecorder.domain.device.IntercomDevice

object IntercomDeviceFactory {
    fun createDevice(entity: DeviceEntity): IntercomDevice {
        return when (entity.deviceType) {
            DeviceType.TWO_N_VERSO -> TwoNIPVersoDevice(entity)
            DeviceType.GENERIC_RTSP_ONVIF -> GenericRtspDevice(entity)
        }
    }
}
