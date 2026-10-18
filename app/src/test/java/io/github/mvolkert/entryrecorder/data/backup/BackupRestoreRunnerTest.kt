package io.github.mvolkert.entryrecorder.data.backup

import com.google.gson.Gson
import io.github.mvolkert.entryrecorder.data.local.dao.AppSettingsDao
import io.github.mvolkert.entryrecorder.data.local.entity.AppSettingsEntity
import io.github.mvolkert.entryrecorder.data.repository.IntercomRepository
import io.github.mvolkert.entryrecorder.domain.model.AppSettings
import io.github.mvolkert.entryrecorder.testing.FakeActiveServerRecordingDao
import io.github.mvolkert.entryrecorder.testing.FakeAppSettingsDao
import io.github.mvolkert.entryrecorder.testing.FakeBackupDocumentStore
import io.github.mvolkert.entryrecorder.testing.FakeDeviceDao
import io.github.mvolkert.entryrecorder.testing.FakeRecordingDao
import io.github.mvolkert.entryrecorder.testing.FakeThemeModeStore
import io.github.mvolkert.entryrecorder.testing.assertNone
import io.github.mvolkert.entryrecorder.testing.assertEquals
import io.github.mvolkert.entryrecorder.testing.expectIs
import io.github.mvolkert.entryrecorder.testing.testCredentialCipher
import io.github.mvolkert.entryrecorder.testing.testDevice
import io.github.mvolkert.entryrecorder.ui.theme.ThemeModeStore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The backup document pipeline on its own, over hand-written seams: a written file round-trips through a
 * second install with its credentials readable and its server ids cleared, a document that is not a backup is
 * reported rather than thrown, and the synchronous theme mirror is written before the Room row it belongs to —
 * the order that lets the next cold start show the restored accent while the database is still closed.
 */
class BackupRestoreRunnerTest {
    private val dispatcher = StandardTestDispatcher()
    private val cipher = testCredentialCipher()

    private fun repository(
        devices: FakeDeviceDao = FakeDeviceDao(),
        settings: AppSettingsDao = FakeAppSettingsDao(),
    ): IntercomRepository = IntercomRepository(devices, FakeRecordingDao(), settings, FakeActiveServerRecordingDao(), cipher, dispatcher)

    private fun runner(
        store: FakeBackupDocumentStore,
        repository: IntercomRepository,
        themeStore: ThemeModeStore = FakeThemeModeStore(),
    ): BackupRestoreRunner = BackupRestoreRunner(
        repository = repository,
        backupStore = store,
        gson = Gson(),
        credentialCipher = cipher,
        themeModeStore = themeStore,
        ioDispatcher = dispatcher,
    )

    @Test
    fun `a backup round trip keeps the credentials readable and clears the server id`() = runTest(dispatcher) {
        val sourceDevices = FakeDeviceDao()
        val source = repository(devices = sourceDevices)
        source.updateSettings(AppSettings(serverApiKey = "sk", themeMode = 2))
        source.upsertDevice(testDevice(id = 1L, serverDeviceId = 77L).copy(username = "admin", password = "s3cret"))
        val exportStore = FakeBackupDocumentStore()

        val exported = expectIs<BackupRestoreOutcome.Exported>(runner(exportStore, source).export(DOCUMENT))

        assertEquals(1, exported.deviceCount, "the count the success line interpolates is the device count")
        val (writtenUri, json) = exportStore.writeCalls.single()
        assertEquals(DOCUMENT, writtenUri, "the document the picker returned is the one written")
        assertEquals(77L, sourceDevices.rows.single().serverDeviceId, "exporting leaves the source row registered")

        // A second install sharing the local key: the file above is the only thing it ever gets.
        val targetDevices = FakeDeviceDao()
        val target = repository(devices = targetDevices)
        val themeModes = FakeThemeModeStore()
        val restoreStore = FakeBackupDocumentStore().apply { content = json }

        val restored = expectIs<BackupRestoreOutcome.Restored>(runner(restoreStore, target, themeModes).restore(DOCUMENT))

        assertEquals(1, restored.deviceCount)
        assertNone(targetDevices.rows.single().serverDeviceId, "a backup taken against another server must not pin its id")
        assertEquals(2, themeModes.lastMode, "the restored mode reaches the synchronous mirror")
        assertEquals("sk", target.getSettings().serverApiKey, "the server key decrypts with the local key")
        assertEquals("s3cret", target.getAllDevicesList().single().password, "the device password decrypts with the local key")
    }

    @Test
    fun `an empty document is reported invalid instead of throwing`() = runTest(dispatcher) {
        val store = FakeBackupDocumentStore().apply { content = "" }

        assertEquals(BackupRestoreOutcome.Invalid, runner(store, repository()).restore(DOCUMENT))
    }

    @Test
    fun `a document that is not json fails with its cause`() = runTest(dispatcher) {
        val store = FakeBackupDocumentStore().apply { content = "not a backup" }

        expectIs<BackupRestoreOutcome.Failed>(runner(store, repository()).restore(DOCUMENT))
    }

    @Test
    fun `a refused output stream fails the export instead of escaping`() = runTest(dispatcher) {
        val store = FakeBackupDocumentStore().apply { writeFailure = IllegalStateException("no space left") }

        val failed = expectIs<BackupRestoreOutcome.Failed>(runner(store, repository()).export(DOCUMENT))

        assertEquals("no space left", failed.cause.localizedMessage, "the cause text is what the snack bar shows")
    }

    @Test
    fun `a restore mirrors the theme before it writes the settings row`() = runTest(dispatcher) {
        val order = mutableListOf<String>()
        val store = FakeBackupDocumentStore().apply {
            content = Gson().toJson(AppBackup(exportedAt = 1L, appSettings = AppSettings(themeMode = 3), devices = emptyList()))
        }
        val target = repository(settings = OrderedSettingsDao(FakeAppSettingsDao(), order))
        val mark = order.size

        runner(store, target, OrderedThemeStore(FakeThemeModeStore(), order)).restore(DOCUMENT)

        assertEquals(listOf("theme", "room"), order.subList(mark, order.size), "the mirror is what the next cold start reads")
    }

    private companion object {
        /** The document identity travels as a String, so a JVM test needs no `android.net.Uri`. */
        const val DOCUMENT = "content://entryrecorder.test/backup"
    }
}

/** Appends to [order] on the way to the delegate, so a test can read the write order off a shared list. */
private class OrderedSettingsDao(
    private val delegate: AppSettingsDao,
    private val order: MutableList<String>,
) : AppSettingsDao by delegate {
    override suspend fun insertOrUpdate(settings: AppSettingsEntity) {
        order += "room"
        delegate.insertOrUpdate(settings)
    }
}

/** Appends to [order] on the way to the delegate, mirroring the synchronous SharedPreferences store. */
private class OrderedThemeStore(
    private val delegate: ThemeModeStore,
    private val order: MutableList<String>,
) : ThemeModeStore by delegate {
    override fun setThemeMode(mode: Int) {
        order += "theme"
        delegate.setThemeMode(mode)
    }
}
