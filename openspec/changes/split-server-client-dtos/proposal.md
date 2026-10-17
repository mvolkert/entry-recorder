# Proposal

## Why

`data.server.ServerRecordingClient` is 623 lines in one file, but only about five-sixths of it is the client: nine
serialisation types are shipped ahead of it — the response shapes `ServerStatusDto`, `ServerActiveRecording`,
`ServerRecordingDto`, `ServerStartResponse`, `ServerStopResponse` and the file-`private` `ServerDeviceDto`, plus the
request bodies `StartServerRecordingPayload`, `ServerDevicePayload` and `MotionAlertPayload`, of which the last two
are file-`private` too. `ServerRecordingDto` is already consumed outside the package (`GalleryItem.Remote`,
`RecordingsViewModel`), so a reader looking for the wire contract has to open the OkHttp client, and a reader
looking at request construction has to scroll past ~110 lines of DTOs to reach it. Nothing about the transport
changes, so there is no spec delta.

## What Changes

- New `data/server/ServerDtos.kt` in the same `data.server` package: the server-answer shapes
  (`ServerStatusDto`, `ServerActiveRecording`, `ServerRecordingDto`, `ServerStartResponse`, `ServerStopResponse`,
  `ServerDeviceDto`) with their `@SerializedName` annotations, computed properties
  (`startedByThisRequest`, `hadActiveJob`) and KDoc moved verbatim.
- New `data/server/ServerPayloads.kt`: the request bodies (`StartServerRecordingPayload`, `ServerDevicePayload`,
  `MotionAlertPayload`). The three file-`private` types become `internal`, and `StartServerRecordingPayload` narrows
  from public to `internal` too — it has no consumer outside this file, so the wire bodies stop being package API.
- `ServerRecordingClient.kt` keeps the OkHttp calls, `normalizeUrl`, the `withApiKey` extension, and
  `serverDevicePayload(...)`, dropping to roughly 510 lines.
- No endpoint, JSON field name, nullability, default, or log line changes; no new dependency or serializer.
- Out of scope: splitting the client by responsibility (status vs recordings vs devices vs alerts), hardening the
  transport (owned by `harden-network-transport`), the motion-alert payload's field semantics
  (`add-motion-notification-to-other-phone`), and any change to `ServerClient`'s interface.

## Capabilities

### New Capabilities

- None — pure refactor, `skip_specs: true`.

### Modified Capabilities

- None — `server-backend` and `event-transport` requirements describe endpoints and behaviour that stay identical.

## Impact

- `app/src/main/java/io/github/mvolkert/entryrecorder/data/server/` — two new files, same package, so no import
  churn in `ui/recordings`, `domain/server`, or the test fakes (`FakeServerClient`).
- Gson field names are unchanged, so on-disk/server-side compatibility is untouched; no Room schema and no server
  code change.
- Verification: `compileDebugKotlin` + `testDebugUnitTest`; a live-server check that status polling, start/stop,
  device registration and the motion relay still round-trip (the payloads are the parts whose visibility changes,
  and Gson serialises them by field name, so a silent rename or visibility slip shows up as a server 422, not a
  compile error).
