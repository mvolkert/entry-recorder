# Design

## Context

See proposal.md — Why. All nine serialisation types sit at the top of `ServerRecordingClient.kt` (L26-136) in package
`io.github.mvolkert.entryrecorder.data.server`, above the `@Singleton class ServerRecordingClient`. The client is the
only implementer of `ServerClient` (110-line interface, same package, already imports `ServerStartResponse`,
`ServerActiveRecording`, `ServerStopResponse`, `ServerRecordingDto` from this package). Outside consumers reference
them by explicit import: `ui/recordings/GalleryItem.kt` (`GalleryItem.Remote.dto`), `ui/recordings/RecordingsViewModel.kt`
(`ServerListState.items`), `testing/Fakes.kt` (`FakeServerClient` returns four of the response shapes).
`ServerDevicePayload`, `ServerDeviceDto` and `MotionAlertPayload` are top-level `private` today, which in Kotlin means
*file*-private, and each is touched only from inside `ServerRecordingClient.kt` (`serverDevicePayload()` at L258,
`gson.fromJson(…, ServerDeviceDto::class.java)` at L286, the relay body at L597).

## Goals / Non-Goals

**Goals:**
- The wire contract reads as one file per direction (server answers vs request bodies), independent of the transport.
- `ServerRecordingClient.kt` contains transport, request construction and response interpretation only.
- Zero behaviour change: same JSON names, same nullability, same defaults, same log lines.

**Non-Goals:**
- Not splitting the client class by responsibility (status vs recordings vs devices vs alerts) — that is a larger
  change with its own verification surface.
- No serializer swap, no `kotlinx.serialization`, no `@Keep` annotations, no new dependency.
- No change to `ServerClient`'s signatures and no rename of any type, so `proguard-rules.pro` is untouched.

## Decisions

- **Split by direction of traffic, not by endpoint.** `ServerDtos.kt` holds the six answer shapes
  (`ServerStatusDto`, `ServerActiveRecording`, `ServerRecordingDto`, `ServerStartResponse`, `ServerStopResponse`,
  `ServerDeviceDto`); `ServerPayloads.kt` holds the three bodies (`StartServerRecordingPayload`,
  `ServerDevicePayload`, `MotionAlertPayload`). A reader of either file sees a complete contract; grouping by endpoint
  would scatter `ServerStatusDto`/`ServerActiveRecording` and re-interleave the two directions.
- **Both files stay in `data.server`.** Same package means no import churn anywhere — outside consumers already write
  `io.github.mvolkert.entryrecorder.data.server.X`, and the client, `ServerClient` and the new files compile together.
  A `data.server.dto` sub-package would force edits in the UI, the ViewModel, the fakes and the ProGuard wildcard rules.
- **File-`private` becomes `internal`, and `StartServerRecordingPayload` narrows public → `internal`.** A cross-file
  move cannot keep top-level `private`. `internal` stays module-scoped (nothing outside the app module consumes these
  types), and `internal` classes compile to public JVM classes, so Gson's reflection is unaffected. The narrowing of
  `StartServerRecordingPayload` is deliberate — grep shows its only construction site is `startRecording` — and any
  missed consumer is a compile error, not a runtime surprise.
- **`serverDevicePayload(device: Device)` stays a private method on the client.** Moving it onto the payload type
  would make the serialisation layer import `domain.model.Device` and `data.model.StreamProtocol` to apply the
  `live_mode` rule; the bodies stay leaf data and the client keeps the domain → wire mapping.
- **Move KDoc and computed properties verbatim**, including the "Nullable on purpose" note on
  `ServerStatusDto.activeRecordings` (Gson writes an absent property as null, not as the Kotlin default) and the
  Phase-S note on `ServerStartResponse.recordingId`, plus `startedByThisRequest` / `hadActiveJob`. Those comments
  encode server behaviour; a move is not the moment to re-summarise them.
- **Import hygiene is part of the move:** `SerializedName` lands in both new files and disappears from the client;
  `Device`, `StreamProtocol`, `EventType`, `DeviceEntity` imports stay with the client. Unused imports are warnings the
  compile task must clear.

## Risks / Trade-offs

- [A `@SerializedName` is dropped while copying, so Gson silently switches to the Kotlin property name and the server
  ignores or rejects the field] → field-by-field diff of each moved type against the original, plus the live-server
  round-trip task; the failure mode is a 422/absent data on the settings connection test or an empty gallery, not a
  crash.
- [Visibility change breaks a consumer the grep missed] → `compileDebugKotlin` and `testDebugUnitTest` are
  sufficient: unit tests are a friend compilation of `main`, so `internal` is visible to `FakeServerClient` if it ever
  needs the payloads.
- [Release build shrinks or renames a moved class and Gson breaks] → not a new risk: `proguard-rules.pro` keys on the
  package wildcard `data.server.**` for members and `keep,allowobfuscation` for the classes, so file names are
  irrelevant. Verified by reading the rules, not by assumption.
- [Two more files to keep in sync when a field changes] → accepted; the alternative (one 623-line file) already hides
  the contract from the readers who need it.

## Migration Plan

Three independently compiling steps, one commit each: (1) move the six answer shapes, delete them from the client,
compile; (2) move the three bodies with `internal`, compile; (3) drop the now-unused `SerializedName` import and run
lint/tests plus the live-server gate. Rollback is reverting one step.

## Open Questions

- None. Whether `ServerDeviceDto` should grow the fields the server already returns is a `server-backend` question,
  out of scope here.
