package io.github.mvolkert.entryrecorder.sip

import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.domain.model.Device

/**
 * The two things the settings screen needs from the SIP stack, as a JVM-only contract:
 * [SipCallManager] is the single implementation, but it builds an `AudioManager` from the application
 * context in its constructor, so a ViewModel that only probes registrations cannot take the concrete
 * class and stay unit-testable.
 */
interface SipRegistrar {

    /** True while the monitor service holds a Linphone core, i.e. a live registration a probe would duplicate. */
    val hasActiveCore: Boolean

    /** One-shot registration attempt against [Device]'s configured registrar. See [SipCallManager.probeRegistration]. */
    suspend fun probeRegistration(device: Device): SipProbeResult
}
