package io.github.mvolkert.entryrecorder.sip

import android.util.Log
import io.github.mvolkert.entryrecorder.data.model.SipMode
import io.github.mvolkert.entryrecorder.domain.model.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.linphone.core.Account
import org.linphone.core.Core
import org.linphone.core.Factory

/**
 * The strategy that turns a set of monitored devices into live Linphone accounts: what gets added, what gets
 * retired, which local port receives inbound calls, and which device an incoming call belongs to.
 *
 * Extracted from [SipCallManager] because that class also owns the `Core`, its listener and the call controls,
 * and the reconciliation policy is the half that can be read — and later exercised against a fake `Core`
 * handle — on its own. Constructed by the manager rather than injected: it is stateless apart from [managed]
 * and only ever runs against the manager's core, so a graph singleton would just invite a second core
 * reference.
 *
 * Every method that mutates an [Account] runs on `Dispatchers.Main.immediate`, because the SDK schedules the
 * core loop on the main thread and its API is not thread-safe; [synchronize] owns that wrapper so its callers
 * keep whatever dispatch they arrived on.
 */
class SipDeviceAccountSync {

    private val tag = "SipDeviceAccountSync"

    /**
     * A monitored device's live SIP state on the shared core: its [Device] plus the [Account] that carries
     * its PBX registration (null for a peer-to-peer device, which is reached directly rather than
     * registered). Keyed by device id so [synchronize] can diff the wanted set against what is
     * live and so an incoming call can be traced back to the device that owns the account it rang on.
     */
    private data class ManagedDevice(val device: Device, val account: Account?)

    // Reconciled on the main thread and read from the core listener, which the SDK also runs on the main
    // thread, so no lock is needed.
    private val managed = LinkedHashMap<Long, ManagedDevice>()

    /** How many devices are monitored right now — the count a failed ring attribution reports. */
    val monitoredCount: Int get() = managed.size

    /**
     * Reconciles the shared core's accounts and listen port against the full set of monitored [devices], so
     * peer-to-peer and PBX devices coexist instead of the last-configured one retiring the others.
     *
     * Takes the whole list rather than one device precisely because retiring a device now has to be relative
     * to what should still be live: each PBX device owns an [Account] that is added, replaced or removed on
     * its own, and the core's inbound port follows the (single) peer-to-peer device rather than being wiped
     * on every reconfigure.
     *
     * [core] is nullable because the monitor reconfigures before it has necessarily started the SDK.
     *
     * Suspend because every call below mutates a core whose loop the SDK schedules on the main thread, and
     * Linphone's API is not thread-safe.
     */
    suspend fun synchronize(core: Core?, devices: List<Device>) {
        withContext(Dispatchers.Main.immediate) {
            val c = core ?: return@withContext
            val wanted = devices.associateBy { it.id }

            // Retire devices that were removed or disabled: withdraw their account + credentials.
            for (id in (managed.keys - wanted.keys).toList()) {
                retireDevice(c, id)
            }

            // Add or reconfigure each device whose row changed; leave untouched ones registered.
            for (device in wanted.values) {
                if (managed[device.id]?.device == device) continue
                retireDevice(c, device.id)
                applyDevice(c, device)
            }

            // The core owns one inbound socket. Peer-to-peer intercoms dial it directly, so it binds that
            // device's local port; a PBX-only set keeps the default and registers outbound.
            applyInboundPort(c, wanted.values.filter { it.sipMode == SipMode.PEER_TO_PEER })
        }
    }

    /**
     * Creates the live SIP state for [device] on [sipCore]. A PBX device gets its own register-enabled
     * [Account] plus an [AuthInfo][org.linphone.core.AuthInfo]; a peer-to-peer device gets no account (the
     * core's inbound socket receives its direct INVITE) and is only recorded so a ring can be attributed to
     * it; a disabled device keeps nothing.
     */
    private fun applyDevice(sipCore: Core, device: Device) {
        when (device.sipMode) {
            SipMode.PEER_TO_PEER -> {
                Log.i(tag, "Monitoring SIP in Direct Peer-to-Peer mode on port ${device.sipLocalPort}")
                managed[device.id] = ManagedDevice(device, account = null)
            }
            SipMode.PBX_REGISTRAR -> {
                val host = device.sipServerHost ?: return
                val user = device.sipUser ?: return
                val port = device.sipServerPort ?: SipCallTiming.DEFAULT_SIP_PORT
                val account = addPbxAccount(sipCore, host, port, user, device.sipPassword ?: "")
                managed[device.id] = ManagedDevice(device, account)
            }
            SipMode.DISABLED -> {
                Log.i(tag, "SIP disabled for ${device.name}")
            }
        }
    }

    /**
     * Withdraws the account [deviceId]'s device was registered under and forgets its credentials, then drops
     * it from the live set. Only `removeAccount` makes liblinphone send the unregister REGISTER - clearing the
     * account list wholesale would take other devices' registrations with it - so without this a retired
     * account stays bound on the PBX until its expiry, and its stale AuthInfo could still answer a 401
     * challenge after an edited password.
     */
    private fun retireDevice(sipCore: Core, deviceId: Long) {
        val retired = managed.remove(deviceId) ?: return
        val account = retired.account ?: return
        sipCore.removeAccount(account)
        val user = retired.device.sipUser
        if (user != null) {
            sipCore.findAuthInfo(retired.device.sipServerHost, user, null)?.let { sipCore.removeAuthInfo(it) }
        }
    }

    /**
     * Registers [host]/[user] as a PBX account on [sipCore]: credentials, identity and a register-enabled
     * account. One implementation shared by the live path ([applyDevice]) and
     * [SipCallManager.probeRegistration] through the manager, so a registration that tests green cannot then
     * silently behave differently once saved.
     */
    fun addPbxAccount(
        sipCore: Core,
        host: String,
        port: Int,
        user: String,
        password: String
    ): Account {
        Log.i(tag, "Configuring SIP PBX Registrar: user=$user server=$host:$port")
        val factory = Factory.instance()
        sipCore.addAuthInfo(factory.createAuthInfo(user, null, password, null, null, host))

        val params = sipCore.createAccountParams()
        params.setIdentityAddress(factory.createAddress("sip:$user@$host"))
        params.setServerAddress(factory.createAddress("sip:$host:$port"))
        params.setRealm(host)
        params.setRegisterEnabled(true)
        return sipCore.createAccount(params).also { sipCore.addAccount(it) }
    }

    /**
     * Points the core's single inbound socket at the peer-to-peer device's local port, or back at the default
     * when no device is reached directly. More than one distinct peer-to-peer port cannot bind one socket, so
     * the first is used and the rest are reported - a real configuration limit the previous single-core model
     * shared but hid behind the "last wins" reconfigure.
     */
    private fun applyInboundPort(sipCore: Core, peerToPeerDevices: List<Device>) {
        val ports = peerToPeerDevices.map { it.sipLocalPort }.distinct()
        if (ports.size > 1) {
            Log.w(tag, "${peerToPeerDevices.size} peer-to-peer devices want distinct ports $ports; " +
                "only ${ports.first()} can be bound on the shared core")
        }
        val port = ports.firstOrNull() ?: SipCallTiming.DEFAULT_SIP_PORT
        val transports = sipCore.transports
        if (transports.udpPort == port && transports.tcpPort == port) return
        transports.udpPort = port
        transports.tcpPort = port
        sipCore.transports = transports
        Log.i(tag, "SIP inbound port set to $port")
    }

    /**
     * The first identity signal: a peer-to-peer intercom dials the phone directly, so the remote address is
     * the device itself. Null when [remoteHost] is unusable or matches nothing.
     */
    fun deviceIdForRemoteHost(remoteHost: String?): Long? {
        if (remoteHost.isNullOrBlank()) return null
        return managed.entries.firstOrNull { it.value.device.ipAddress == remoteHost }?.key
    }

    /**
     * The second signal: behind a PBX the outbound leg carries the dialed extension, which is the device's SIP
     * user. Null when [dialed] is unusable or matches nothing.
     */
    fun deviceIdForDialedUser(dialed: String?): Long? {
        if (dialed.isNullOrBlank()) return null
        return managed.entries.firstOrNull { it.value.device.sipUser == dialed }?.key
    }

    /** Forgets the live set. The core's own stop() is what withdraws the accounts; this only drops the map. */
    fun clear() {
        managed.clear()
    }

    /**
     * The monitored device whose live [account] this is, by matching the account's identity address, or null
     * when the account belongs to no managed device. Used to name a registration change with the device the
     * operator configured rather than a bare SIP URI.
     */
    fun deviceNameForAccount(account: Account): String? {
        val address = account.params.identityAddress?.asStringUriOnly()
        return managed.values.firstOrNull { entry ->
            val acct = entry.account
            acct != null && (acct === account || acct.params.identityAddress?.asStringUriOnly() == address)
        }?.device?.name
    }
}
