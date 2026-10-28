# Proposal

## Why

The app logs only to logcat via `android.util.Log`. On a headless deployment phone mounted by the door,
a connection loss or a crashed capture is unrecoverable after the fact — logcat rotates and is gone by the
time the user can plug the phone into a dev machine. The owner wants the existing log stream written to a
file the app keeps, toggled from Settings, so field problems can be shared out without a computer.

## What Changes

- Adopt **Timber** as the app-wide logging facade: plant a `DebugTree` (logcat) plus a file `Tree` that
  appends log lines to a rolling file under the app's private storage.
- Migrate the current `android.util.Log.*` call sites to `Timber.*` (tag preserved per class so the stream
  stays readable), including the connection-loss lines introduced by `add-connection-loss-logging`.
- Add a Settings toggle **"Save logs to file"** (`AppSettings`, default OFF) that enables/disables the file
  tree at runtime; the logcat tree is always on.
- Bound the on-disk log with a size/rotation policy (rotate at a fixed file size, keep a small number of
  generations) so it can never grow unbounded, independent of the recordings quota.
- Add a **"Share logs"** action that exports the current log file(s) through the system share sheet, with
  credentials already redacted at the source (the `Device`/`AppSettings` `toString` redaction and the
  existing API-key handling stay the guarantee).
- **Ask-first items this request authorises:** adding the Timber dependency to `gradle/libs.versions.toml`,
  and a Room schema bump for the new settings column.
- Out of scope: uploading logs to the Python server, a crash-reporting backend, log levels finer than
  Timber's, or surfacing log contents inside the app UI.

## Capabilities

### New Capabilities
- `diagnostics-logging`: capturing the app log stream to a bounded, shareable file on the device.

### Modified Capabilities
- `settings-ui`: the settings hub gains the "Save logs to file" toggle and a "Share logs" action.

## Impact

- Dependency: `gradle/libs.versions.toml` + `app/build.gradle.kts` (new Timber entry).
- Code: `EntryRecorderApp.kt` (plant trees), a new file `Tree` class under `util/`,
  `domain/model/AppSettings.kt` + `data/local/entity/AppSettingsEntity.kt` (+ Room migration and schema
  JSON), `data/mapper/DomainMappers.kt`, `ui/settings/SettingsEngineCard.kt` (or a diagnostics row) and
  `ui/settings/SettingsViewModel.kt`, plus the many `android.util.Log` call sites converted to `Timber`.
- Verification: on-device gate (toggle on, reproduce a connection loss, share the file, confirm the lines
  and that secrets stay redacted); Room over-real-data upgrade check for the new column.
