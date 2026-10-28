# Tasks

## 1. Dependency (ask-first, authorised by the request)

- [x] 1.1 Add Timber to `gradle/libs.versions.toml` and `app/build.gradle.kts`, and verify
  `./gradlew :app:compileDebugKotlin` resolves the new dependency.

## 2. Logging facade + file tree

- [x] 2.1 Plant a `Timber.DebugTree` unconditionally in `EntryRecorderApp.onCreate` and convert the
  `android.util.Log.*` call sites to `Timber.*` (preserve each class's existing tag, including the shared
  `"IntercomMonitorService"` tag); verify `./gradlew compileDebugKotlin testDebugUnitTest` stays green.
- [x] 2.2 Implement a `FileTree` that appends to `filesDir/logs/` on a background `HandlerThread`, with size
  rotation and a bounded generation count; verify with a unit test that writes exceed the cap and roll while the
  oldest generation is dropped.
- [x] 2.3 Make file-logging enable/disable a runtime `Timber.plant`/`uproot` of the `FileTree` driven by the
  persisted setting, held in `FileLoggingPlanter` so the decision is a unit rather than an `Application` body
  (`EntryRecorderApp` only feeds it the persisted toggle); verify with `FileLoggingPlanterTest` that one enable
  plants exactly one tree, a re-emission of the same value plants nothing, and a disable uproots it.
- [x] 2.4 Ensure no `FileTree` is planted under unit tests; verify the existing JVM test suite still passes with
  `isReturnDefaultValues` (no file writes during tests).

## 3. Settings persistence (Room schema change)

- [x] 3.1 Add `loggingToFileEnabled: Boolean = false` to `AppSettingsEntity` and the matching `AppSettings` field
  + `DomainMappers` mapping; verify `./gradlew compileDebugKotlin` and KSP regenerates a new schema JSON.
- [x] 3.2 Ship `MIGRATION_22_23` (`ALTER TABLE app_settings ADD COLUMN loggingToFileEnabled` default off) and
  bump the `@Database` version; verified by the generated schema JSON and `compileDebugKotlin`. The over-real-data
  upgrade check is a device gate and lives in group 7.

## 4. Settings UI + share

- [x] 4.1 Add a "Save logs to file" `SettingsSwitchRow` bound to `loggingToFileEnabled` in the settings hub;
  verify the toggle persists and reappears on reopen (Compose UI test or `lintDebug` for the new row).
- [x] 4.2 Add a "Share logs" action exporting every non-empty generation under `filesDir/logs` through the
  existing `FileProvider` (`ACTION_SEND_MULTIPLE` of `content://` URIs, `FLAG_GRANT_READ_URI_PERMISSION`) with
  strings in `strings.xml`. That the intent resolves on a device is a group 7 gate.
- [x] 4.3 Run `./gradlew lintDebug` green for the new UI.

## 5. Redaction guarantee

- [x] 5.1 Add a unit test asserting a logged `Device`/`AppSettings` line shows `<redacted>` and never a raw
  password/SIP password/API key, so the exported file is safe.

## 6. Build verification

- [x] 6.1 Run `./gradlew compileDebugKotlin testDebugUnitTest lintDebug` green.

## 7. On-device verification (gate — do not mark complete until it passes)

- [ ] 7.1 Enable "Save logs to file", reproduce a connection loss (unplug the Verso camera), disable, and share:
  confirm the file contains the loss/recovery lines and that no credential appears in clear.
- [ ] 7.2 Confirm the log stays within its size cap and rotates over many lines, and that recordings cleanup does
  not count or delete log files.
- [ ] 7.3 Upgrade a real v22 database across `22 -> 23 -> 24` (the record-limits change added `23 -> 24` after
  this task was written) and confirm the recordings survive and `loggingToFileEnabled` defaults to off.
- [ ] 7.4 Open the share sheet from settings and confirm the log files resolve and arrive as readable text.

## Workflow follow-up

- Update `README.md` manually once the diagnostics toggle ships (spec-sheet convention).
- Archive after groups 7.1-7.4 pass; `MIGRATION_22_23` requires the over-real-data check (7.3) before archive.
