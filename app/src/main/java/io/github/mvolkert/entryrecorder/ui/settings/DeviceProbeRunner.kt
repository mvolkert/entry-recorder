package io.github.mvolkert.entryrecorder.ui.settings

import android.util.Log
import io.github.mvolkert.entryrecorder.data.device.IntercomDeviceFactory
import io.github.mvolkert.entryrecorder.data.network.HttpSnapshotClient
import io.github.mvolkert.entryrecorder.domain.model.Device
import io.github.mvolkert.entryrecorder.sip.SipRegistrar
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.milliseconds

/**
 * The three probes the device form runs against values that were typed but never saved, plus the passive
 * measured-rate poll. Each returns a state *value* rather than writing one, so the ViewModel keeps the
 * `MutableStateFlow`s the screen reads — a probe survives turning the phone over, its holder does not.
 *
 * No dispatcher is injected: [HttpSnapshotClient], [SipRegistrar] and the device classes
 * [IntercomDeviceFactory] builds each hop to their own IO dispatcher internally, and [measuredFps] stays a
 * cold flow so the ViewModel keeps deciding which dispatcher its shared flow runs on.
 */
class DeviceProbeRunner @Inject constructor(
    private val deviceFactory: IntercomDeviceFactory,
    private val snapshotClient: HttpSnapshotClient,
    private val sipRegistrar: SipRegistrar,
) {
    private val tag = "DeviceProbeRunner"

    /** Asks the intercom itself whether the typed address, port and credentials work, before anything is saved. */
    suspend fun connectionTest(candidate: Device): DeviceTestState = try {
        val outcome = deviceFactory.createDevice(candidate).testConnection()
        if (outcome.isSuccess) DeviceTestState.Passed
        else DeviceTestState.Failed(outcome.exceptionOrNull()?.localizedMessage)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A device class that cannot even report why it failed still has to leave the button idle.
        Log.w(tag, "Connection probe for ${candidate.name} threw", e)
        DeviceTestState.Failed(e.localizedMessage)
    }

    /** Asks a registrar whether the typed SIP account would register. See [SipRegistrar.probeRegistration]. */
    suspend fun sipRegistration(candidate: Device): SipTestState =
        SipTestState.Completed(sipRegistrar.probeRegistration(candidate))

    /**
     * Burst-measures what the snapshot endpoint can serve, for the typed path and credentials. Writes
     * nothing: the form owns its own fields, so the screen decides how to use the number.
     */
    suspend fun snapshotFps(candidate: Device): SnapshotProbeState =
        SnapshotProbeState.Measured(snapshotClient.probeSnapshotFps(candidate))

    /** True while the monitor service holds a Linphone core, which is what a second probe would contend with. */
    val usesActiveRegistrar: Boolean get() = sipRegistrar.hasActiveCore

    /**
     * The snapshot rate the endpoint actually delivered lately, polled for whichever device [deviceId] is
     * pointing at (null stops reporting a number). Cold, and deliberately without `flowOn`: the dispatcher
     * belongs to whoever consumes it, so the poll keeps running on the one the ViewModel already shares the
     * flow on. [HttpSnapshotClient.achievedFps] is a getter rather than a stream, which is why a loop is
     * needed at all.
     */
    fun measuredFps(deviceId: StateFlow<Long?>): Flow<Float> = flow {
        while (true) {
            val id = deviceId.value
            emit(if (id == null) 0f else snapshotClient.achievedFps(id))
            delay(MEASURED_FPS_POLL_MS.milliseconds)
        }
    }

    private companion object {
        /** How often [measuredFps] re-reads a rate that is a getter, not a stream. */
        private const val MEASURED_FPS_POLL_MS = 2_000L
    }
}
