# Design

## Context

Logging today is direct `android.util.Log` calls with a per-class `TAG`/`tag` (e.g. `HttpSnapshotClient`,
and the service classes deliberately share the `"IntercomMonitorService"` tag so one trigger is followable in a
single stream). JVM unit tests set `unitTests.isReturnDefaultValues = true` precisely because ViewModels log
through `android.util.Log`. Secrets are already redacted at the object level: `Device.toString()` and the entity
collapse `password`/`sipPassword` to `<redacted>`, and `serverApiKey` is stored as `enc:v1:` ciphertext and only
decrypted in the mapper.

Motivation is in proposal.md. This design settles how Timber is introduced, where the file lives, and how the
toggle and share flow work.

## Goals / Non-Goals

**Goals:**
- One logging facade with two trees (logcat always, file opt-in) planted once at app start.
- Runtime enable/disable of the file tree driven by the persisted setting, no restart.
- File writes off the main thread; a crash-safe, bounded, rotation policy.
- Preserve the existing tag-per-class convention so the converted stream reads like the old one.

**Non-Goals:**
- No change to what is logged or the connection-loss wording (that is `add-connection-loss-logging`).
- No log upload, remote aggregation, or in-app log viewer.
- No new log levels; Timber's default priority set is used as-is.

## Decisions

- **Timber as the facade, `Log` call sites converted mechanically.** `Timber.plant` a `DebugTree` (tag = class
  simple name via `callSite()` where the existing tag already matches, or an explicit `tag` for the shared
  service tag) plus a custom `FileTree`. Alternative: keep `android.util.Log` and write a manual tee — rejected
  because it duplicates formatting/level logic Timber already provides and the request names Timber.
- **`FileTree` writes on an injected IO dispatcher / single background thread.** File append must never run on
  the main thread or a UI frame; a dedicated `HandlerThread` (Timber's own example uses one) serialises writes so
  lines keep order. Redaction stays at the source (objects already print `<redacted>`), so the tree does no
  secret-scrubbing of its own beyond a defensive filter on the known API-key literal.
- **Storage location: `context.filesDir/logs/` (private).** `cacheDir` risks eviction by the system before a user
  can share; `filesDir` keeps the evidence. Rotation: roll at a fixed per-file size (e.g. ~2–5 MB) keeping a small
  fixed count (e.g. 3–5 files). Independent of `internalQuotaMb`/`externalQuotaMb`, so the recordings cleanup pass
  neither counts nor reclaims log files (stays a Non-Goal of `storage-cleanup`).
- **Room migration for the toggle.** Add `loggingToFileEnabled: Boolean = false` to `AppSettingsEntity` and the
  matching `AppSettings` domain field + mapper; ship `MIGRATION_22_23` that `ALTER TABLE app_settings ADD COLUMN`
  with default `0`, mirroring how `MIGRATION_21_22` added the motion-alert columns off. A declared-new column does
  not force a table rebuild. Bump `@Database(version = …)` and regenerate the schema JSON.
- **Enable/disable at runtime.** Observing the settings row (or a settings `StateFlow`) drives
  `Timber.uproot`/`Timber.plant` of the `FileTree`, so flipping the switch takes effect immediately; the logcat
  tree is planted unconditionally in `EntryRecorderApp.onCreate`.
- **Share via `FileProvider` + `ACTION_SEND`.** Export the current (and optionally rolled) file(s) through the
  **existing** `io.github.mvolkert.entryrecorder.fileprovider` (already declared in `AndroidManifest.xml`) as a
  `content://` URI, never a `file://` URI (strict mode + recipient apps). This means adding `filesDir/logs` to that
  provider's `res/xml` paths config, not a new `<provider>`. User strings go in `strings.xml`.

## Risks / Trade-offs

- [Secrets leaking to a shareable plaintext file] → Redaction is at the object `toString` already; add a focused
  unit test asserting a `Device`/`AppSettings` line contains `<redacted>` and not the raw password/key.
- [Mass conversion of `Log`→`Timber` regressions / noise] → Mechanical, tag-preserving sweep; run
  `compileDebugKotlin` + `testDebugUnitTest` to catch signature slips. Keep JVM tests green by planting no file
  tree in tests (a no-op/`DebugTree`-only forest under test).
- [Disk growth on a 24/7 headless phone] → Hard size cap + bounded generations; log volume is small (transition
  lines, not per-frame), so the cap is rarely hit.
- [FileProvider must expose the log dir] → A `FileProvider` (authority
  `io.github.mvolkert.entryrecorder.fileprovider`) already exists; extend its `res/xml` paths to cover
  `filesDir/logs` so the share resolves a `content://` URI. No new exported component is added.
- [Room over-real-data upgrade] → `MIGRATION_22_23` is an additive column; verify an upgrade from a real v22
  database keeps recordings and simply defaults the flag off.

## Migration Plan

1. Add Timber to the version catalog and app module.
2. Introduce `FileTree` + planting in `EntryRecorderApp`; convert `Log` call sites.
3. Add the settings field, entity column, mapper, `MIGRATION_22_23`, schema JSON.
4. Wire the runtime enable/disable observer and the Settings toggle/share UI.
Rollback: revert to `android.util.Log` (drop the facade) and remove the column in a follow-up migration; existing
installs are unaffected because the flag defaults off.

## Open Questions

- None blocking. Exact size cap / generation count and whether "Share logs" includes rolled generations can be
  finalised during implementation.
