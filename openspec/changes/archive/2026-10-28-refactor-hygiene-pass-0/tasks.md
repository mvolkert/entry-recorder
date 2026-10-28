# Tasks

## 1. Import hygiene

- [x] 1.1 Expand the wildcard `kotlinx.coroutines` imports in `data/network/MjpegStreamReader.kt`,
  `service/IntercomMonitorService.kt`, `ui/recordings/RecordingsViewModel.kt`,
  `video/OnDeviceMotionAnalyzer.kt` and `ui/components/RtspVideoPlayer.kt`; verify
  `./gradlew compileDebugKotlin` passes and no `^import kotlinx.` line ending in `.*` remains in `app/src/main`.
  *(Landed in `ec6f6e6 refactor: apply Phase 0 code-hygiene pass`, whose stat covers the reader, the analyzer
  and the player. Confirmed on current `main`: the only surviving wildcards are the three Room DAOs'
  `androidx.room.*` and `ui/components/JpegFramePlayer.kt`'s Compose imports, both excluded below. The
  proposal named `RtspVideoPlayer`/`OnDeviceMotionAnalyzer` at paths those files have since moved to, so the
  current paths are recorded here.)*

## 2. Error paths and dispatchers

- [x] 2.1 Give every previously silent catch a log line while keeping its fallback, in `DigestAuthenticator`'s
  header parse, `RtspVideoPlayer`'s setup/release and the recordings screen's catches; verify
  `compileDebugKotlin` and that no `catch` body is empty.
  *(The `DigestAuthenticator` and `RtspVideoPlayer` sites landed in `ec6f6e6` (which also covered
  `H264Encoder`'s close), each now logging with a why-comment. `RecordingsViewModel.kt` has no `catch` block
  left at all, and no file under `ui/recordings` does either, so nothing is swallowed there now; the
  connection-probe path that later refactors moved out of that view model lives in
  `ui/settings/DeviceProbeRunner.kt`, whose catches rethrow `CancellationException` and comment why the other
  one leaves the button idle. Which change deleted the recordings catches cannot be read off the current tree,
  so this task records the verified end state rather than a cause.)*
- [x] 2.2 Remove the redundant per-chunk `withContext(ioDispatcher)` from
  `MjpegStreamReader.readMjpegStream`; verify `compileDebugKotlin` and that each MJPEG flow still ends in a
  single `flowOn(ioDispatcher)`.
  *(Landed in `ec6f6e6`. Current state: no `withContext` remains in the file, and all three server/camera
  flows end `.flowOn(ioDispatcher)` (L112, L146, L224), with the comment at L261 recording that the collector
  is already on the IO dispatcher.)*

## 3. Scope and shared state

- [x] 3.1 Replace `SipCallManager`'s private `SupervisorJob` scope with an injected application scope; verify
  `compileDebugKotlin` and that `AppModule` provides it.
  *(Landed in `ec6f6e6` as `@DefaultDispatcher`; it is `@ApplicationScope private val scope: CoroutineScope`
  at `SipCallManager.kt:45` today, which is the stronger form the proposal asked for — no scope is constructed
  inside the class.)*
- [x] 3.2 Annotate `TwoNIPVersoDevice.channelExpiresAtMs` and `transportReportedDown` `@Volatile`; verify
  `compileDebugKotlin` and read the diff to confirm both are written off the poll thread.
  *(Landed in `ec6f6e6`, which named the second field `transportOpenReportedDown`; both are `@Volatile` at
  `TwoNIPVersoDevice.kt:71` and `:76`, alongside `channelId` at `:69`.)*

## 4. Named values and DRY

- [x] 4.1 Name the manual-record magic number and fix the duration double-conversion in the monitor's start
  helpers; verify `compileDebugKotlin` and that no `delay(<expr>.milliseconds.milliseconds)` form remains.
  *(Landed in `ec6f6e6`; `DEFAULT_MANUAL_RECORD_SECONDS` is `LiveViewModel.kt:88`, used at `:68`. Every
  remaining `delay`/`withTimeoutOrNull` call takes either a `Long` ms value or an already-named `_MS` constant
  through `.milliseconds`, so no value is converted twice.)*
- [x] 4.2 Collapse the duplicated navigation blocks behind a `deviceEditRoute(id)` helper and a
  `settingsDetail(...)` graph builder; verify `compileDebugKotlin` and that the settings destinations call the
  helper instead of repeating the `composable` block.
  *(Both landed in `ec6f6e6`: `Navigation.kt:109` declares `private fun deviceEditRoute(deviceId: Long)` used
  by both edit sites (`:262`, `:263`), and `Navigation.kt:188` declares
  `fun NavGraphBuilder.settingsDetail(...)` used by the engine/storage/appearance/backup destinations.)*
- [x] 4.3 Give the monitoring path one shared log tag definition so the service and its collaborators cannot
  drift apart; verify by grepping `"IntercomMonitorService"` in `app/src/main` and finding it in exactly one
  file.
  *(The proposal's "share a `TAG` constant in `IntercomMonitorService`" was satisfied inside the service by
  `ec6f6e6`, then regressed: `refactor-intercom-service-responsibilities` created three collaborators that
  each re-typed the literal. Fixed by `service/MonitorLogTag.kt` declaring
  `internal const val MONITOR_LOG_TAG`, with the four declarations sourcing their value from it and every call
  site unchanged.)*
- [x] 4.4 Move the Linphone Account-API migration backlog out of the inline `TODO(sip)` into its own change;
  verify no `TODO(sip)` remains in `app/src/main` and that the backlog change exists.
  *(Landed in `ec6f6e6`, which added `openspec/changes/migrate-linphone-account-api/` with its own device
  gates; that change is now `openspec/changes/archive/2026-10-17-migrate-linphone-account-api/`.)*

## 5. Build verification

- [x] 5.1 Run `./gradlew compileDebugKotlin testDebugUnitTest lintDebug` green; verify the JVM suite count is
  unchanged from the pre-pass baseline, since this pass asserts nothing about behaviour.
  *(2026-10-28: BUILD SUCCESSFUL for all three; 42 suites / 296 tests / 0 failures, identical to the run that
  preceded this pass. `lintDebug` reports no finding on `MonitorLogTag.kt` or the four edited declarations; the
  lint SarifReporter stack trace it prints over the quick-fix regex is pre-existing and the HTML/SARIF reports
  are still written.)*

## Not in scope (recorded decision, not a silent skip)

- Expanding `ui/components/JpegFramePlayer.kt`'s Compose wildcard imports and the three Room DAOs'
  `androidx.room.*` is left alone: the proposal excludes the DAO wildcards, and this project configures no
  code-style tooling (`lintDebug` is the only check), so there is no verifier behind a pure import-layout
  churn in files this pass never needed to open.
- Further shrinking `IntercomMonitorService` or `SipCallManager` toward another change's line-count target is
  owned by `refactor-intercom-service-responsibilities` 4.1 and `split-sip-call-manager-responsibilities` 3.1,
  not by hygiene.

## Workflow follow-up

- Archive with `openspec archive refactor-hygiene-pass-0 --skip-specs -y` once 5.1 is green: the change declares
  `skip_specs`, so no delta is written into `openspec/specs/`.
- `README.md` needs no update - this pass changes no user-visible behaviour.
