package io.github.mvolkert.entryrecorder.di

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.mvolkert.entryrecorder.data.backup.AndroidBackupDocumentStore
import io.github.mvolkert.entryrecorder.data.backup.BackupDocumentStore
import io.github.mvolkert.entryrecorder.data.local.AppDatabase
import io.github.mvolkert.entryrecorder.data.local.crypto.AndroidKeystoreCredentialKeyProvider
import io.github.mvolkert.entryrecorder.data.local.crypto.CredentialCipher
import io.github.mvolkert.entryrecorder.data.local.dao.ActiveServerRecordingDao
import io.github.mvolkert.entryrecorder.data.local.dao.AppSettingsDao
import io.github.mvolkert.entryrecorder.data.local.dao.DeviceDao
import io.github.mvolkert.entryrecorder.data.local.dao.RecordingDao
import io.github.mvolkert.entryrecorder.data.local.entity.AppSettingsEntity
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.domain.model.Device
import io.github.mvolkert.entryrecorder.ui.theme.AndroidThemeModeStore
import io.github.mvolkert.entryrecorder.ui.theme.ThemeModeStore
import io.github.mvolkert.entryrecorder.data.local.prefs.AndroidAutoBootStore
import io.github.mvolkert.entryrecorder.data.local.prefs.AndroidOnboardingStore
import io.github.mvolkert.entryrecorder.data.local.prefs.AutoBootStore
import io.github.mvolkert.entryrecorder.data.local.prefs.OnboardingStore
import io.github.mvolkert.entryrecorder.ui.onboarding.AndroidPermissionChecker
import io.github.mvolkert.entryrecorder.ui.onboarding.PermissionChecker
import io.github.mvolkert.entryrecorder.util.AndroidExportGateway
import io.github.mvolkert.entryrecorder.util.AndroidUiStrings
import io.github.mvolkert.entryrecorder.util.AppStorageDirs
import io.github.mvolkert.entryrecorder.util.ExportGateway
import io.github.mvolkert.entryrecorder.util.TRANSCODE_DIR_NAME
import io.github.mvolkert.entryrecorder.util.UiStrings
import io.github.mvolkert.entryrecorder.util.recordingsDirOf
import io.github.mvolkert.entryrecorder.util.thumbnailsDirOf
import io.github.mvolkert.entryrecorder.worker.AndroidRetentionCleanupScheduler
import io.github.mvolkert.entryrecorder.worker.RetentionCleanupScheduler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * The whole object graph in one place: what used to be a hand-built tree of `object` singletons and
 * `by lazy` properties on the Application class. Nothing here is reachable through a global; every
 * consumer declares what it needs in its constructor instead.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(@DefaultDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher)

    @Provides
    @Singleton
    fun provideGson(): Gson = Gson()

    /**
     * The one way a class that is not UI reaches a string resource, and the reason a ViewModel can be
     * constructed in a JVM test at all — see [UiStrings].
     */
    @Provides
    @Singleton
    fun provideUiStrings(@ApplicationContext context: Context): UiStrings = AndroidUiStrings(context)

    /**
     * Where the app's own files live, resolved once. Consumers take the [AppStorageDirs] value instead of a
     * Context so a test can hand them three temp folders.
     */
    @Provides
    @Singleton
    fun provideAppStorageDirs(@ApplicationContext context: Context): AppStorageDirs = AppStorageDirs(
        recordingsDir = recordingsDirOf(context),
        thumbnailsDir = thumbnailsDirOf(context),
        transcodeScratchDir = File(context.cacheDir, TRANSCODE_DIR_NAME),
    )

    /** The synchronous theme-mode mirror, behind the one method the settings ViewModel writes through. */
    @Provides
    @Singleton
    fun provideThemeModeStore(@ApplicationContext context: Context): ThemeModeStore =
        AndroidThemeModeStore(context)

    @Provides
    @Singleton
    fun provideAutoBootStore(@ApplicationContext context: Context): AutoBootStore =
        AndroidAutoBootStore(context)

    /**
     * The first-run flow's own synchronous mirror. It has to be readable before the window inflates, which is
     * why it is SharedPreferences behind this seam rather than a settings column.
     */
    @Provides
    @Singleton
    fun provideOnboardingStore(@ApplicationContext context: Context): OnboardingStore =
        AndroidOnboardingStore(context)

    /** Whether the system still shows its sheet for a permission, and whether the battery exemption is held. */
    @Provides
    @Singleton
    fun providePermissionChecker(
        @ApplicationContext context: Context,
        store: OnboardingStore,
    ): PermissionChecker = AndroidPermissionChecker(context, store)

    /** The "clean up now" button, behind the one method the settings ViewModel calls. */
    @Provides
    @Singleton
    fun provideRetentionCleanupScheduler(@ApplicationContext context: Context): RetentionCleanupScheduler =
        AndroidRetentionCleanupScheduler(context)

    /** SAF document streams for backup export/restore, behind the two methods the settings ViewModel uses. */
    @Provides
    @Singleton
    fun provideBackupDocumentStore(
        @ApplicationContext context: Context,
        strings: UiStrings,
    ): BackupDocumentStore = AndroidBackupDocumentStore(context, strings)

    /** MediaStore, the SAF export folder and the share sheet, behind the interface its callers use. */
    @Provides
    @Singleton
    fun provideExportGateway(@ApplicationContext context: Context): ExportGateway =
        AndroidExportGateway(context)

    /**
     * The control-plane client: 5 s to connect, 10 s to read, 5 s to write. This is also the client the
     * other two profiles are derived from, so all three share one connection pool — which is the point,
     * because the 2N serialises its HTTP stack and a pool per caller only adds connection setup on top.
     */
    @Provides
    @Singleton
    @ControlClient
    fun provideControlClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    /**
     * Snapshot polling, one TCP connection reused across the ~6 req/s the 2N encoder serves. A single lost
     * request or ACK costs one RTO (~0.44 s measured on this LAN); three in a row would exceed the frame
     * slot, so the budget is tight and the caller retries once instead of letting the slot go empty.
     */
    @Provides
    @Singleton
    @SnapshotClient
    fun provideSnapshotClient(@ControlClient controlClient: OkHttpClient): OkHttpClient =
        controlClient.newBuilder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
            .build()

    /** A body that streams for as long as the caller keeps reading: video download, or an SSE event feed. */
    @Provides
    @Singleton
    @StreamClient
    fun provideStreamClient(@ControlClient controlClient: OkHttpClient): OkHttpClient =
        controlClient.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

    @Provides
    @Singleton
    fun provideCredentialCipher(@ApplicationContext context: Context): CredentialCipher =
        CredentialCipher(AndroidKeystoreCredentialKeyProvider(context))

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
        credentialCipher: CredentialCipher,
    ): AppDatabase {
        // Held so the creation callback can write through the same database Room is still building. The
        // old companion-object version called getDatabase() again from inside onCreate, which is a
        // re-entrancy that only worked because the instance was assigned before the callback ever fired.
        lateinit var database: AppDatabase
        database = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "entry_recorder_database",
        )
            // Deliberately no fallbackToDestructiveMigration: its Boolean argument is *dropAllTables*, not an
            // on/off switch, so any call to it still empties the archive when a step is missing. Without it,
            // a database this app has no migration for fails at open instead of silently deleting the user's
            // recordings — see the note on APP_DATABASE_MIGRATIONS about the pre-4 gap.
            .addMigrations(*APP_DATABASE_MIGRATIONS, credentialReencryptMigration(credentialCipher))
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    // Initialize default app settings. The callback runs on the thread that created the
                    // file, so the insert goes to the application scope instead of blocking table creation.
                    scope.launch {
                        database.appSettingsDao().insertOrUpdate(AppSettingsEntity())
                    }
                }
            })
            .build()
        return database
    }

    @Provides
    @Singleton
    fun provideDeviceDao(database: AppDatabase): DeviceDao = database.deviceDao()

    @Provides
    @Singleton
    fun provideRecordingDao(database: AppDatabase): RecordingDao = database.recordingDao()

    @Provides
    @Singleton
    fun provideAppSettingsDao(database: AppDatabase): AppSettingsDao = database.appSettingsDao()

    @Provides
    @Singleton
    fun provideActiveServerRecordingDao(database: AppDatabase): ActiveServerRecordingDao =
        database.activeServerRecordingDao()
}

/**
 * Every schema step the app has ever shipped, applied in order. They live here rather than in
 * [AppDatabase]'s companion because they are wiring — how a database gets built — not part of the
 * database's own contract, and because Hilt is the only caller.
 *
 * Destructive migration is not configured, so each step has to be real: an install below the current
 * version is upgraded through this list or Room refuses to open the database, never silently emptied.
 *
 * The oldest file this list can upgrade is version 4 — the app's first three schema steps were never written
 * down as migrations. An install that old now fails at open with Room naming the missing step, which is the
 * honest answer for an archive: the alternative was deleting the user's recordings to make the app start.
 */
private val APP_DATABASE_MIGRATIONS: Array<Migration> = arrayOf(
    // v4 -> v5: add the transcode-on-export setting. Kept as a real migration because
    // destructive migration is disabled; a default of 1 preserves the previous (always-transcode)
    // behavior for existing installs.
    object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE app_settings ADD COLUMN transcodeOnExport INTEGER NOT NULL DEFAULT 1"
            )
        }
    },

    // v5 -> v6: add the SAF export-folder tree URI. Empty string means no folder chosen yet.
    object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE app_settings ADD COLUMN exportFolderUri TEXT NOT NULL DEFAULT ''"
            )
        }
    },

    // v6 -> v7: add the opt-in "auto-export finished recordings" mirror flag. Default 0 keeps
    // existing installs on the previous behavior (no background writes to the SAF folder).
    object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE app_settings ADD COLUMN autoExportOnFinalize INTEGER NOT NULL DEFAULT 0"
            )
        }
    },

    // v7 -> v8: add the selectable accent-color preset index. Default 0 maps to the baseline
    // Material 3 palette, preserving the previous look for existing installs.
    object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE app_settings ADD COLUMN themeAccentIndex INTEGER NOT NULL DEFAULT 0"
            )
        }
    },

    // v8 -> v9: per-role accent selection. Three preset-index columns, each seeded from the old
    // single themeAccentIndex so an existing install renders exactly as before. The legacy column
    // is kept (still mapped by the entity) instead of rebuilding app_settings.
    object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE app_settings ADD COLUMN themePrimaryIndex INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "ALTER TABLE app_settings ADD COLUMN themeSecondaryIndex INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "ALTER TABLE app_settings ADD COLUMN themeTertiaryIndex INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "UPDATE app_settings SET themePrimaryIndex = themeAccentIndex, " +
                    "themeSecondaryIndex = themeAccentIndex, themeTertiaryIndex = themeAccentIndex"
            )
        }
    },

    // v9 -> v10: alert & lockscreen behavior moves from global app_settings onto each device. The
    // five new columns default to 1, then every existing device is seeded from the current global
    // app_settings row so an install keeps the behavior the user already chose.
    object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE devices ADD COLUMN wakeOnRing INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE devices ADD COLUMN soundOnRing INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE devices ADD COLUMN vibrateOnRing INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE devices ADD COLUMN wakeOnMotion INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE devices ADD COLUMN wakeOnNoise INTEGER NOT NULL DEFAULT 1")
            db.execSQL(
                "UPDATE devices SET " +
                    "wakeOnRing = (SELECT wakeOnRing FROM app_settings WHERE id = 1), " +
                    "soundOnRing = (SELECT soundOnRing FROM app_settings WHERE id = 1), " +
                    "vibrateOnRing = (SELECT vibrateOnRing FROM app_settings WHERE id = 1), " +
                    "wakeOnMotion = (SELECT wakeOnMotion FROM app_settings WHERE id = 1), " +
                    "wakeOnNoise = (SELECT wakeOnNoise FROM app_settings WHERE id = 1)"
            )
        }
    },

    // v10 -> v11: per-device motion sensitivity for the in-app analyzer. Default 'BALANCED' reproduces
    // the analyzer's original fixed constants, so every existing device keeps behaving exactly as before.
    object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE devices ADD COLUMN motionSensitivity TEXT NOT NULL DEFAULT 'BALANCED'"
            )
        }
    },

    // v11 -> v12: persisted in-flight server recordings, so a process restart can resume reconciling
    // and auto-stopping a job the server is still running. Transient table: rows live only while the
    // app tracks the job. eventType uses Room's enum-as-TEXT mapping (the enum name), like the
    // other enum columns already in this database.
    object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS active_server_recordings (" +
                    "deviceId INTEGER NOT NULL PRIMARY KEY, " +
                    "recordingId INTEGER, " +
                    "eventType TEXT NOT NULL, " +
                    "maxDurationSeconds INTEGER NOT NULL, " +
                    "startedByThisRequest INTEGER NOT NULL, " +
                    "startedAtMs INTEGER NOT NULL)"
            )
        }
    },

    // v12 -> v13: Phase S device sync. Each app device can now carry the row id the server assigned it
    // when the app registered it via POST /api/devices. Nullable (no default): an existing device is
    // simply unregistered until the app pushes it once in PYTHON_SERVER mode.
    object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE devices ADD COLUMN serverDeviceId INTEGER")
        }
    },

    // v13 -> v14: user-selectable light/dark color mode. Default 0 = ThemeMode.System so the app
    // follows the device setting, matching the system-driven splash screen added alongside this.
    object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE app_settings ADD COLUMN themeMode INTEGER NOT NULL DEFAULT 0"
            )
        }
    },

    // v14 -> v15: the second trigger of a clip that covered two of them. A doorbell press during a running
    // recording is folded into that recording instead of stopping it, so existing rows simply stay
    // single-tagged and keep every filter's original answer.
    object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE recordings ADD COLUMN alsoEventTypes TEXT NOT NULL DEFAULT ''"
            )
        }
    },

    // v15 -> v16: opt-in Material You dynamic color. Default 0 keeps the curated accent presets
    // for existing installs; users who want wallpaper colours turn it on in Settings > Appearance.
    object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE app_settings ADD COLUMN themeUseDynamicColor INTEGER NOT NULL DEFAULT 0"
            )
        }
    },

    // v16 -> v17: separate internal and external storage limits with their own cleanup switches. The
    // internal quota is backfilled from the old whole-archive limit and the internal switch from the
    // old master cleanup toggle, so an existing install keeps exactly the behaviour the user chose;
    // the external half defaults to a roomier archive and stays off.
    //
    // The backfill copies the legacy limit verbatim rather than clamping it into the new range, even
    // though the old stepper had no upper bound and the old restore path stored whatever the backup JSON
    // held for the legacy limit — a 0 among them. Clamping would turn such a value into a limit the user
    // never chose — and a limit below the current usage is a deletion request. Left alone,
    // `CleanupPolicy.bytesToFree` sees a quota outside its range and frees nothing, which makes a bad
    // stored value inert instead of destructive; the row still shows it so the user can correct it.
    object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE app_settings ADD COLUMN internalQuotaMb INTEGER NOT NULL DEFAULT " +
                    AppSettingsEntity.DEFAULT_INTERNAL_QUOTA_MB
            )
            db.execSQL(
                "ALTER TABLE app_settings ADD COLUMN internalCleanupEnabled INTEGER NOT NULL DEFAULT 1"
            )
            db.execSQL(
                "ALTER TABLE app_settings ADD COLUMN externalQuotaMb INTEGER NOT NULL DEFAULT " +
                    AppSettingsEntity.DEFAULT_EXTERNAL_QUOTA_MB
            )
            db.execSQL(
                "ALTER TABLE app_settings ADD COLUMN externalCleanupEnabled INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "UPDATE app_settings SET internalQuotaMb = maxStorageUsageMb, " +
                    "internalCleanupEnabled = autoCleanupEnabled"
            )
        }
    },

    // v17 -> v18: per-device motion pre-roll frame count. Default 6 reproduces the analyzer's fixed
    // ring size, so every existing device keeps prepending exactly as many approach frames as before.
    object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE devices ADD COLUMN motionPreRollFrames INTEGER NOT NULL DEFAULT " +
                    DeviceEntity.DEFAULT_MOTION_PRE_ROLL_FRAMES
            )
        }
    },

    // v18 -> v19: opt-in per-device hold of the wake/wifi locks while idle monitoring. Default 0 is the
    // new battery-friendly behaviour: the locks now only cover active recordings and rings. Existing
    // detection-first installs that relied on the always-held locks turn it on per device once.
    object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE devices ADD COLUMN keepLocksHeld INTEGER NOT NULL DEFAULT 0")
        }
    },

    // v20 -> v21: the user-controlled "Auto Boot" gate on the monitor's boot autostart. Default 1 keeps the
    // unconditional autostart existing installs had — and matches AutoBootPrefs' read default, so a device
    // that upgrades but has not yet saved settings still restarts its monitor exactly as before.
    object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE app_settings ADD COLUMN autoBootEnabled INTEGER NOT NULL DEFAULT 1"
            )
        }
    },

    // v21 -> v22: the remote motion alert (change `add-motion-notification-to-other-phone`). Both columns
    // default off/empty, so an upgraded install relays nothing until the user opts in and names a recipient.
    object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE app_settings ADD COLUMN motionAlertEnabled INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "ALTER TABLE app_settings ADD COLUMN motionAlertRecipient TEXT NOT NULL DEFAULT ''"
            )
        }
    },

    // v22 -> v23: the file-logging opt-in (change `add-timber-file-logging`). Default 0 leaves an upgraded
    // install writing no log file, exactly as before, until the user turns the Settings toggle on.
    object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE app_settings ADD COLUMN loggingToFileEnabled INTEGER NOT NULL DEFAULT 0"
            )
        }
    },

    // v23 -> v24: the per-trigger recording ceilings (change `add-per-trigger-record-limits`). 0 is the
    // literal "no limit", so every existing device keeps the clip lengths it recorded with before.
    object : Migration(23, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE devices ADD COLUMN motionMaxFrames INTEGER NOT NULL DEFAULT " +
                    DeviceEntity.DEFAULT_MAX_RECORD_LIMIT
            )
            db.execSQL(
                "ALTER TABLE devices ADD COLUMN noiseMaxFrames INTEGER NOT NULL DEFAULT " +
                    DeviceEntity.DEFAULT_MAX_RECORD_LIMIT
            )
            db.execSQL(
                "ALTER TABLE devices ADD COLUMN ringMaxMinutes INTEGER NOT NULL DEFAULT " +
                    DeviceEntity.DEFAULT_MAX_RECORD_LIMIT
            )
        }
    },
)

/**
 * v19 -> v20: the three credential columns (`devices.password`, `devices.sipPassword`,
 * `app_settings.serverApiKey`) turn from plaintext into ciphertext. Encryption is a Keystore operation,
 * so it is not expressible in migration SQL — this reads each stored value and writes back its encrypted
 * form, skipping blanks and anything already tagged so the step is safe to re-run. The table shape is
 * unchanged (the columns stay TEXT); only the contents move behind the `enc:v1:` marker.
 */
private fun credentialReencryptMigration(cipher: CredentialCipher): Migration =
    object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            encryptColumn(db, cipher, table = "devices", idColumn = "id", column = "password")
            encryptColumn(db, cipher, table = "devices", idColumn = "id", column = "sipPassword")
            encryptColumn(db, cipher, table = "app_settings", idColumn = "id", column = "serverApiKey")
        }
    }

private fun encryptColumn(
    db: SupportSQLiteDatabase,
    cipher: CredentialCipher,
    table: String,
    idColumn: String,
    column: String,
) {
    val pending = ArrayList<Pair<Long, String>>()
    db.query("SELECT $idColumn, $column FROM $table").use { cursor: Cursor ->
        while (cursor.moveToNext()) {
            if (cursor.isNull(1)) continue
            val raw = cursor.getString(1)
            if (raw.isNotEmpty() && !cipher.looksEncrypted(raw)) {
                pending += cursor.getLong(0) to cipher.encrypt(raw)
            }
        }
    }
    pending.forEach { (id, encrypted) ->
        db.execSQL("UPDATE $table SET $column = ? WHERE $idColumn = ?", arrayOf<Any?>(encrypted, id))
    }
}
