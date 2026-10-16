package io.github.mvolkert.entryrecorder.domain.call

import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.domain.model.Device

/**
 * The port the monitor service uses to bring a call/preview screen to the foreground. The service decides
 * *that* a screen should show, but not *how* — the Android activity mechanics live in the UI layer behind
 * this interface, so the service package stops importing `ui` (a presentation detail leaking into the
 * background layer is exactly the layering smell this seam removes).
 */
interface CallScreenLauncher {
    /** Launch the full-screen call/preview for [device] on [eventType]; only a ring carries a [caller] to display. */
    fun launch(device: Device, eventType: EventType, caller: String? = null)
}
