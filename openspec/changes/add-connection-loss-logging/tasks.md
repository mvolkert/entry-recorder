# Tasks

## 1. Shared wording

- [x] 1.1 Define the per-source `lost`/`recovered` log template (source ∈ snapshot, camera-events,
  sip-registration, python-server) and verify by grepping a sample line for the fixed prefix.

## 2. Snapshot path

- [x] 2.1 Consolidate the `HttpSnapshotClient` / `LocalCaptureLoop` snapshot-quality edges onto the standard
  wording (WARN on DEGRADED/OFFLINE, INFO on ONLINE) with an outage-duration field on recovery, and verify a
  simulated streak of failures logs exactly one loss line and one recovery line (unit test over the edge logic).

## 3. Camera-event channel

- [x] 3.1 Add a loss/recovery edge to the 2N Logging API subscribe/pull path in `TwoNIPVersoDevice` and verify
  it logs `camera-events lost/recovered: <device>` on the transition (compile + a decoding/subscription unit test
  where practical).

## 4. Python-server link

- [x] 4.1 Log reachability / device-sync / live-relay loss and recovery with the reason (never the API key) on the
  one shared `python-server` edge: `PythonServerLink` holds the `ConnectionEdgeTracker` keyed on the server
  origin, and both `ServerRecordingClient` and the `MjpegStreamReader` live relay report into it, so one outage
  prints one loss line and one recovery line regardless of which consumer noticed first. `PythonServerLinkTest`
  and `ConnectionLogTest` cover the keying and the streak.
  - Exception, by design: the settings-screen `ServerRecordingClient.testConnection` probe stays unrouted — a
    user-initiated test must not declare the server down — mirroring the design's decision that the SIP test
    probe keeps its own non-logged result path.
- [x] 4.2 Route the remote motion-alert delivery failure through the same `python-server` wording in
  `TriggerEpisodeRunner` and verify `testDebugUnitTest` stays green.

## 5. SIP registration

- [x] 5.1 Compare incoming Linphone registration state against the last state per account in
  `SipCallManager`/`SipDeviceAccountSync` and log `sip-registration lost/recovered: <device>` only on edges,
  without touching core teardown from the callback; verify compile and that the test-probe path stays un-logged.

## 6. Build verification

- [x] 6.1 Run `./gradlew compileDebugKotlin testDebugUnitTest lintDebug` green.

## 7. On-device verification (gate — do not mark complete until it passes)

- [ ] 7.1 Verso camera: unplug then replug the Ethernet and confirm one `snapshot lost` WARN and one
  `snapshot recovered` INFO (with a down duration), plus `camera-events lost/recovered`.
- [ ] 7.2 Live Python server: stop then restart the server and confirm `python-server lost/recovered` with no API
  key printed.
- [ ] 7.3 PBX/Fritz!Box SIP: force a registration drop (disable the account or lose WAN) and confirm
  `sip-registration lost` then `recovered` when it re-registers.

## Workflow follow-up

- After the on-device gates in group 7 pass, archive the change so the four spec deltas land in `openspec/specs/`.
- When `add-timber-file-logging` is applied afterward, these lines are captured to the shared log file automatically.
