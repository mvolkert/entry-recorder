# Proposal

## Why

Code-smell audit (android-dev-standards review) surfaced hygiene issues with no behaviour delta:
wildcard `kotlinx.coroutines` imports, silent exception catches, a per-chunk dispatcher hop that
duplicates `flowOn`, a hardcoded `CoroutineScope` inside `SipCallManager`, missing `@Volatile`
annotations on shared fields, magic numbers/duration double-conversions, duplicated navigation
blocks, and an inline `TODO(sip)` that belongs in the backlog.

## What Changes

- Expand wildcard coroutine imports in `MjpegStreamReader`, `IntercomMonitorService`,
  `RecordingsViewModel`, `OnDeviceMotionAnalyzer`, `RtspVideoPlayer` (Room/Linphone wildcards kept).
- Log previously silent catches in `DigestAuthenticator`, `RtspVideoPlayer`, `RecordingsViewModel`.
- Remove redundant per-chunk `withContext(ioDispatcher)` in `MjpegStreamReader.readMjpegStream`.
- Inject `@ApplicationScope CoroutineScope` into `SipCallManager`.
- Annotate `TwoNIPVersoDevice.channelExpiresAtMs` / `transportReportedDown` `@Volatile`.
- Named `DEFAULT_MANUAL_RECORD_SECONDS` constant; fix `delay(ms.milliseconds)` double conversion
  and share a `TAG` constant in `IntercomMonitorService`.
- Navigation DRY: `deviceEditRoute(id)` helper and a `settingsDetail(...)` graph-builder helper.
- Move the Linphone Account-API migration backlog into the `migrate-linphone-account-api` change.

## Impact

- Pure refactor; no behaviour or spec delta. `compileDebugKotlin` + `testDebugUnitTest` green.
- Accessibility review item was a false positive (retry icons have adjacent visible labels).
