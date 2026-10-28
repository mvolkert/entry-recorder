# Proposal

## Why

Connection loss is currently only partly visible: the snapshot path emits a `ConnectionState` edge and a
handful of transports log a warning per failure, but the 2N event stream, the Python-server link, and SIP
registration can drop without a clear, uniform "lost → why → recovered after N" line in the log stream.
Operators diagnosing a camera that stopped recording, or a phone that missed doorbell rings, have to
correlate scattered warnings. This change makes every connection-loss and recovery transition observable
with a consistent diagnostic.

## What Changes

- Emit one structured diagnostic log per connection **loss edge** and one per **recovery edge** for each
  transport, carrying the device/server name, the reason, and (on recovery) how long it was down or how
  many attempts failed.
- Cover all four sources the app depends on:
  - the snapshot/MJPEG polling path (edge-triggered, already partly present — standardise its wording),
  - the 2N Logging API event subscription (subscribe/pull/re-subscribe failures),
  - the Python server link (reachability probe, device sync, live relay, remote motion alert),
  - SIP registration (registrar drops the account, or the PBX becomes unreachable).
- Log at `WARN` on loss and `INFO` on recovery; no per-poll spam — transitions only, matching the existing
  edge-triggered snapshot-quality behaviour.
- Out of scope: surfacing these logs in any new UI, changing connection-quality *state* semantics, or
  altering retry/backoff timing. Persisting logs to a file is a separate capability (see
  `add-timber-file-logging`); this change only guarantees the transitions are logged.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `device-connectivity`: the snapshot-path connection-quality edge gains an explicit loss/recovery diagnostic
  requirement with standardised wording.
- `event-transport`: the 2N event subscription must log loss and recovery of the camera-event channel.
- `sip`: SIP registration loss and re-registration must be logged.
- `server-backend`: the app-to-server connection (reachability, device sync, live relay, motion-alert relay)
  must log loss and recovery.

## Impact

- Code: `data/network/HttpSnapshotClient.kt`, `video/LocalCaptureLoop.kt` (snapshot edge wording),
  `data/device/TwoNIPVersoDevice.kt` (Logging API subscribe/pull), `data/server/ServerRecordingClient.kt`,
  `data/server/ServerClient.kt`, `service/MonitorEventHandler.kt` (connection-state routing),
  `service/TriggerEpisodeRunner.kt` (motion-alert relay failure), `sip/SipCallManager.kt` /
  `sip/SipDeviceAccountSync.kt` (registration callbacks).
- Verification: on-device gate against a real Verso camera (unplug/replug), a live Python server, and a
  PBX/Fritz!Box registrar to observe each loss/recovery line in the log stream.
