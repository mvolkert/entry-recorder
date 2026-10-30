package io.github.mvolkert.entryrecorder.testing

import android.app.Activity
import io.github.mvolkert.entryrecorder.data.backup.BackupDocumentStore
import io.github.mvolkert.entryrecorder.data.local.prefs.MotionAlertPushStore
import io.github.mvolkert.entryrecorder.domain.alert.RemoteMotionAlert
import io.github.mvolkert.entryrecorder.domain.alert.RemoteMotionAlertPresenter
import io.github.mvolkert.entryrecorder.push.MotionAlertRegistrar
import io.github.mvolkert.entryrecorder.data.local.crypto.CredentialCipher
import io.github.mvolkert.entryrecorder.data.local.crypto.SymmetricKeyProvider
import io.github.mvolkert.entryrecorder.domain.model.Device
import io.github.mvolkert.entryrecorder.domain.model.Recording
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.data.server.ServerActiveRecording
import io.github.mvolkert.entryrecorder.data.server.ServerClient
import io.github.mvolkert.entryrecorder.data.server.ServerRecordingDto
import io.github.mvolkert.entryrecorder.data.server.ServerStartResponse
import io.github.mvolkert.entryrecorder.data.server.ServerStopResponse
import io.github.mvolkert.entryrecorder.sip.SipProbeResult
import io.github.mvolkert.entryrecorder.sip.SipRegistrar
import io.github.mvolkert.entryrecorder.ui.theme.ThemeModeStore
import io.github.mvolkert.entryrecorder.data.local.prefs.AutoBootStore
import io.github.mvolkert.entryrecorder.data.local.prefs.OnboardingStore
import io.github.mvolkert.entryrecorder.ui.onboarding.GrantState
import io.github.mvolkert.entryrecorder.ui.onboarding.OnboardingPermission
import io.github.mvolkert.entryrecorder.ui.onboarding.PermissionChecker
import io.github.mvolkert.entryrecorder.util.ExportEntry
import io.github.mvolkert.entryrecorder.util.ExportFolderAccess
import io.github.mvolkert.entryrecorder.util.ExportGateway
import io.github.mvolkert.entryrecorder.util.ExportMetadata
import io.github.mvolkert.entryrecorder.util.UiStrings
import io.github.mvolkert.entryrecorder.video.H264Transcoder
import io.github.mvolkert.entryrecorder.video.PreRollFrame
import io.github.mvolkert.entryrecorder.video.RecorderEvent
import io.github.mvolkert.entryrecorder.video.RecordingController
import io.github.mvolkert.entryrecorder.worker.RetentionCleanupScheduler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File
import javax.crypto.KeyGenerator

/**
 * Hand-written fakes for the seams the ViewModels consume, in the spirit of the project's AGENTS note:
 * fakes rather than mocks, no mocking framework. Each one records what it was asked for so a test can
 * assert on it, and exposes the small knobs a given scenario needs.
 */

/** Answers every resource with `"res-<id>"` (plus `:arg,arg` when arguments are passed), recording the calls. */
class FakeUiStrings : UiStrings {
    val calls = mutableListOf<String>()

    override fun invoke(id: Int, vararg args: Any): String {
        val text = "res-$id" + if (args.isEmpty()) "" else args.joinToString(":", prefix = ":")
        calls += text
        return text
    }

    override fun quantity(id: Int, quantity: Int, vararg args: Any): String {
        val text = "plural-$id/$quantity" + if (args.isEmpty()) "" else args.joinToString(":", prefix = ":")
        calls += text
        return text
    }

    /** True when a message built from [id] (exact resource, ignoring args) was produced. */
    fun emittedResource(id: Int): Boolean =
        calls.any { it == "res-$id" || it.startsWith("res-$id:") }
}

class FakeThemeModeStore : ThemeModeStore {
    var lastMode: Int? = null
    var setCount = 0

    override fun setThemeMode(mode: Int) {
        lastMode = mode
        setCount++
    }
}

class FakeAutoBootStore : AutoBootStore {
    var lastEnabled: Boolean? = null
    var setCount = 0

    override fun setAutoBootEnabled(enabled: Boolean) {
        lastEnabled = enabled
        setCount++
    }
}

/**
 * The motion-alert receiver prefs (change `add-motion-notification-to-other-phone`) in memory: the flag, the
 * address the push service would have stored, and a count of each write, so a settings test can show the
 * receiver section works without Room or a Context.
 */
class FakeMotionAlertPushStore : MotionAlertPushStore {
    // Named apart from the interface's setter, which occupies the `setReceiveEnabled` JVM signature.
    var enabled = false
    var storedEndpoint: String? = null
    var storedNeedsEncryption = false
    val enabledWrites = mutableListOf<Boolean>()
    val endpointWrites = mutableListOf<Pair<String, Boolean>>()
    var clearCount = 0

    override fun isReceiveEnabled(): Boolean = enabled

    override fun endpoint(): String? = storedEndpoint

    override fun endpointNeedsEncryption(): Boolean = storedNeedsEncryption

    override fun setReceiveEnabled(enabled: Boolean) {
        this.enabled = enabled
        enabledWrites += enabled
    }

    override fun saveEndpoint(url: String, needsEncryption: Boolean) {
        storedEndpoint = url
        storedNeedsEncryption = needsEncryption
        endpointWrites += url to needsEncryption
    }

    override fun clearEndpoint() {
        storedEndpoint = null
        storedNeedsEncryption = false
        clearCount++
    }
}

/**
 * The registrar seam: answers [registerResult] synchronously and counts the calls, so a test can prove what
 * opting in and out does to the distributor without the connector or a distributor app. [registerActivities]
 * records the Activity each call was given, which is what a picker needs and a boot receiver cannot supply.
 */
class FakeMotionAlertRegistrar : MotionAlertRegistrar {
    var distributorInstalled = true
    var registerResult = true
    var registerCount = 0
    var unregisterCount = 0
    val registerActivities = mutableListOf<Activity?>()

    override fun hasDistributor(): Boolean = distributorInstalled

    override fun register(activity: Activity?, onResult: (Boolean) -> Unit) {
        registerCount++
        registerActivities += activity
        onResult(registerResult)
    }

    override fun unregister() {
        unregisterCount++
    }
}

/** Collects the alerts the handler decided to notify about — the only output the payload tests assert on. */
class RecordingRemoteMotionAlertPresenter : RemoteMotionAlertPresenter {
    val shown = mutableListOf<RemoteMotionAlert>()

    override fun show(alert: RemoteMotionAlert) {
        shown += alert
    }
}

/**
 * The intro's own prefs seam, in memory: the completed flag and the per-permission ask count the flow
 * counts on to decide when the system sheet has stopped appearing. [resetCount] says how often Settings'
 * reset row was what cleared them.
 */
class FakeOnboardingStore : OnboardingStore {
    var completedAnswer = false
    var setCompletedCount = 0
    var resetCount = 0
    val askCounts = mutableMapOf<String, Int>()

    override fun isCompleted(): Boolean = completedAnswer

    override fun setCompleted(completed: Boolean) {
        completedAnswer = completed
        setCompletedCount++
    }

    override fun reset() {
        completedAnswer = false
        askCounts.clear()
        resetCount++
    }

    override fun askCount(permissionKey: String): Int = askCounts[permissionKey] ?: 0

    override fun recordAsk(permissionKey: String) {
        askCounts[permissionKey] = askCount(permissionKey) + 1
    }
}

/**
 * The platform seam with hand-set answers, so a test states the device condition it means rather than
 * stubbing a Context. Every ask the flow records shows up in [recordedAsks].
 */
class FakePermissionChecker : PermissionChecker {
    var notificationsSupported = true
    var batteryExempt = false
    val states = mutableMapOf<OnboardingPermission, GrantState>()
    val recordedAsks = mutableListOf<OnboardingPermission>()

    override fun supportsNotifications(): Boolean = notificationsSupported

    override fun grantState(permission: OnboardingPermission): GrantState =
        states[permission] ?: GrantState.Requestable

    override fun isBatteryExempt(): Boolean = batteryExempt

    override fun platformName(permission: OnboardingPermission): String = "platform-${permission.name}"

    override fun recordAsk(permission: OnboardingPermission) {
        recordedAsks += permission
    }
}

class FakeRetentionCleanupScheduler : RetentionCleanupScheduler {
    var runCount = 0

    override fun runNow() {
        runCount++
    }
}

class FakeSipRegistrar : SipRegistrar {
    override var hasActiveCore: Boolean = false
    var probeResult: SipProbeResult = SipProbeResult.Registered("fake-registrar")
    val probeCalls = mutableListOf<Device>()

    override suspend fun probeRegistration(device: Device): SipProbeResult {
        probeCalls += device
        return probeResult
    }
}

/**
 * The recorder seam for the Live ViewModel and monitor-service consumers: [activeDeviceIds] is a real
 * StateFlow the test drives, and every start/stop is recorded instead of touching Media3 or a camera.
 */
class FakeRecordingController : RecordingController {
    private val _activeDeviceIds = MutableStateFlow<Set<Long>>(emptySet())
    override val activeDeviceIds: StateFlow<Set<Long>> = _activeDeviceIds.asStateFlow()

    /** Set the ids the fake should claim are recording right now. */
    var activeIds: Set<Long>
        get() = _activeDeviceIds.value
        set(value) { _activeDeviceIds.value = value }

    override val events: Flow<RecorderEvent> = emptyFlow()

    var localIds: Set<Long> = emptySet()
    override fun isLocallyRecording(deviceId: Long): Boolean = deviceId in localIds

    data class Start(
        val deviceId: Long,
        val eventType: EventType,
        val maxDurationSeconds: Int,
        val preRoll: List<PreRollFrame>
    )

    val startCalls = mutableListOf<Start>()
    val stopCalls = mutableListOf<Pair<Long, EventType?>>()
    val resumeCalls = mutableListOf<List<Device>>()

    override fun startRecording(
        device: Device,
        eventType: EventType,
        maxDurationSeconds: Int,
        preRoll: List<PreRollFrame>
    ) {
        startCalls += Start(device.id, eventType, maxDurationSeconds, preRoll)
    }

    override fun stopRecording(deviceId: Long, reason: EventType?) {
        stopCalls += deviceId to reason
    }

    override suspend fun resumePersistedServerRecordings(devices: List<Device>) {
        resumeCalls += devices
    }
}

/**
 * The server seam: every answer is a programmable [Result] and the calls the ViewModels make are
 * recorded. Unused methods return sensible successes so a scenario never trips over an unconfigured one.
 */
class FakeServerClient : ServerClient {
    var testConnectionResult: Result<Boolean> = Result.success(true)
    var startResult: Result<ServerStartResponse> =
        Result.success(ServerStartResponse(status = "started", deviceId = 1))
    var registerResult: Result<Long> = Result.success(77)
    var updateResult: Result<Unit> = Result.success(Unit)
    var deleteDeviceResult: Result<Unit> = Result.success(Unit)
    var activeResult: Result<ServerActiveRecording?> = Result.success(null)
    var stopResult: Result<ServerStopResponse> = Result.success(ServerStopResponse(status = "stopped", deviceId = 1))
    var listResult: Result<List<ServerRecordingDto>> = Result.success(emptyList())
    var setProtectedResult: Result<Unit> = Result.success(Unit)
    var deleteRecordingResult: Result<Unit> = Result.success(Unit)
    var downloadResult: Result<File> = Result.success(File("downloaded.mkv"))
    var relayMotionAlertResult: Result<Unit> = Result.success(Unit)

    val testConnectionCalls = mutableListOf<Pair<String, String?>>()
    val registerCalls = mutableListOf<Device>()
    val updateCalls = mutableListOf<Pair<Long, Device>>()
    val deleteDeviceCalls = mutableListOf<Long>()
    val listCalls = mutableListOf<Pair<String, String?>>()
    val setProtectedCalls = mutableListOf<Triple<Long, Boolean, String>>()
    val deleteRecordingCalls = mutableListOf<Long>()
    val downloadCalls = mutableListOf<String>()
    val relayMotionAlertCalls = mutableListOf<RelayMotionAlertCall>()

    override suspend fun testConnection(serverUrl: String, apiKey: String?): Result<Boolean> {
        testConnectionCalls += serverUrl to apiKey
        return testConnectionResult
    }

    override suspend fun startRecording(
        serverUrl: String,
        apiKey: String?,
        serverDeviceId: Long,
        deviceName: String,
        eventType: EventType,
        durationSeconds: Int
    ): Result<ServerStartResponse> = startResult

    override suspend fun registerDevice(
        serverUrl: String,
        apiKey: String?,
        device: Device
    ): Result<Long> {
        registerCalls += device
        return registerResult
    }

    override suspend fun updateServerDevice(
        serverUrl: String,
        apiKey: String?,
        serverDeviceId: Long,
        device: Device
    ): Result<Unit> {
        updateCalls += serverDeviceId to device
        return updateResult
    }

    override suspend fun deleteServerDevice(
        serverUrl: String,
        apiKey: String?,
        serverDeviceId: Long
    ): Result<Unit> {
        deleteDeviceCalls += serverDeviceId
        return deleteDeviceResult
    }

    override suspend fun activeRecordingFor(
        serverUrl: String,
        apiKey: String?,
        deviceId: Long
    ): Result<ServerActiveRecording?> = activeResult

    override suspend fun stopRecording(
        serverUrl: String,
        apiKey: String?,
        deviceId: Long
    ): Result<ServerStopResponse> = stopResult

    override suspend fun listRecordings(
        serverUrl: String,
        apiKey: String?,
        deviceId: Long?,
        limit: Int
    ): Result<List<ServerRecordingDto>> {
        listCalls += serverUrl to apiKey
        return listResult
    }

    override suspend fun setProtected(
        serverUrl: String,
        apiKey: String?,
        recordingId: Long,
        isProtected: Boolean
    ): Result<Unit> {
        setProtectedCalls += Triple(recordingId, isProtected, serverUrl)
        return setProtectedResult
    }

    override suspend fun deleteRecording(
        serverUrl: String,
        apiKey: String?,
        recordingId: Long
    ): Result<Unit> {
        deleteRecordingCalls += recordingId
        return deleteRecordingResult
    }

    override suspend fun downloadVideo(
        absoluteUrl: String,
        targetDir: File,
        baseName: String,
        onProgress: (percent: Int) -> Unit
    ): Result<File> {
        downloadCalls += absoluteUrl
        return downloadResult
    }

    override suspend fun relayMotionAlert(
        serverUrl: String,
        apiKey: String?,
        recipient: String,
        deviceName: String,
        timestampMs: Long
    ): Result<Unit> {
        relayMotionAlertCalls += RelayMotionAlertCall(serverUrl, apiKey, recipient, deviceName, timestampMs)
        return relayMotionAlertResult
    }
}

/** One recorded `relayMotionAlert` call, so a test can assert the transport forwarded every field intact. */
data class RelayMotionAlertCall(
    val serverUrl: String,
    val apiKey: String?,
    val recipient: String,
    val deviceName: String,
    val timestampMs: Long,
)

/**
 * The transcode seam: [transcodeToH264] returns a real temp file, and setting [blockTranscode] makes it
 * suspend until the caller's job is cancelled — which is how a test keeps an export run "active" to
 * exercise cancellation and the busy guard.
 */
class FakeH264Transcoder : H264Transcoder {
    val transcodeCalls = mutableListOf<Recording>()
    var blockTranscode: CompletableDeferred<Unit>? = null

    /** When set, [transcodeToH264] throws it instead of returning a file — the encode-failure fallback path. */
    var transcodeFailure: Throwable? = null

    override fun outputFor(source: File): File =
        File(source.parentFile, "${source.nameWithoutExtension}_h264_fake.mp4")

    override suspend fun transcodeToH264(
        recording: Recording,
        onProgress: (done: Int, total: Int) -> Unit
    ): File {
        transcodeCalls += recording
        blockTranscode?.await()
        transcodeFailure?.let { throw it }
        onProgress(1, 1)
        return File.createTempFile("fake_h264_", ".mp4").apply { deleteOnExit() }
    }
}

/** The gateway seam: SAF writes are recorded, their answer is configurable, nothing touches a provider. */
class FakeExportGateway : ExportGateway {
    val safSaveCalls = mutableListOf<Triple<String, File, String>>()
    var safSaveResult = true
    var gallerySaveResult = true

    /** When set, [saveFileToSafFolder] throws it — the transient-failure path a caller retries on. */
    var saveFailure: Throwable? = null
    var probeAccessResult: ExportFolderAccess = ExportFolderAccess.OK
    var folderDisplayName = "FakeExport"
    val grantTakeCalls = mutableListOf<String>()
    val grantReleaseCalls = mutableListOf<String>()
    var grantTakeResult = true

    /** The tree contents [listExportedFiles] answers with, so a usage number has something to count. */
    var exportedFiles: List<ExportEntry> = emptyList()

    /** When set, the two provider queries throw it — the refused listing a folder must report as no data. */
    var listFailure: Throwable? = null
    var displayNameFailure: Throwable? = null

    override fun shareFiles(context: android.content.Context, files: List<File>, onMessage: (String) -> Unit) = Unit

    override fun shareFile(
        context: android.content.Context,
        file: File,
        metadata: ExportMetadata,
        onMessage: (String) -> Unit,
    ) = Unit

    override fun saveFileToGallery(
        sourceFile: File,
        deviceName: String,
        onMessage: (String) -> Unit,
    ): Boolean = gallerySaveResult

    override fun saveFileToSafFolder(
        folderUri: String,
        sourceFile: File,
        displayName: String,
        onProgress: ((Int) -> Unit)?,
    ): Boolean {
        safSaveCalls += Triple(folderUri, sourceFile, displayName)
        saveFailure?.let { throw it }
        return safSaveResult
    }

    override fun probeExportFolderAccess(folderUri: String): ExportFolderAccess = probeAccessResult

    override fun safFolderDisplayName(folderUri: String): String {
        displayNameFailure?.let { throw it }
        return folderDisplayName
    }

    override fun listExportedFiles(folderUri: String): List<ExportEntry> {
        listFailure?.let { throw it }
        return exportedFiles
    }

    override fun deleteExportedFile(folderUri: String, displayName: String): Boolean = true

    override fun takePersistableTreeGrant(uri: android.net.Uri): Boolean {
        grantTakeCalls += uri.toString()
        return grantTakeResult
    }

    override fun releasePersistableTreeGrant(folderUri: String) {
        grantReleaseCalls += folderUri
    }
}

/** The backup-document seam: writes are kept in memory, reads answer a programmable [content]. */
class FakeBackupDocumentStore : BackupDocumentStore {
    val writeCalls = mutableListOf<Pair<String, String>>()
    var content: String = ""

    /** When set, [writeText] throws it — the refused output stream a backup reports as a failure. */
    var writeFailure: Throwable? = null

    override fun writeText(documentUri: String, text: String) {
        writeFailure?.let { throw it }
        writeCalls += documentUri to text
    }

    override fun readText(documentUri: String): String = content
}

/** Plain values the ViewModel tests hand to the seams and the fake repositories. */
fun testDevice(
    id: Long = 1L,
    name: String = "Doorbell",
    isEnabled: Boolean = true,
    serverDeviceId: Long? = null,
): Device = Device(
    id = id,
    name = name,
    ipAddress = "192.168.1.50",
    isEnabled = isEnabled,
    serverDeviceId = serverDeviceId,
)

fun testRecording(
    id: Long = 0L,
    deviceId: Long = 1L,
    deviceName: String = "Doorbell",
    eventType: EventType = EventType.MOTION,
    timestamp: Long = 1_700_000_000_000L,
    filePath: String,
): Recording = Recording(
    id = id,
    deviceId = deviceId,
    deviceName = deviceName,
    eventType = eventType,
    timestamp = timestamp,
    filePath = filePath,
)

/**
 * A [CredentialCipher] on a fixed software AES key, standing in for the Keystore-backed production
 * cipher in repository and ViewModel tests. The key is generated once per call and returned on every
 * encrypt/decrypt, mirroring the cached Keystore entry; a provider that re-generated would make
 * [CredentialCipher.decrypt] see a different key.
 */
fun testCredentialCipher(): CredentialCipher {
    val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    return CredentialCipher(SymmetricKeyProvider { key })
}
