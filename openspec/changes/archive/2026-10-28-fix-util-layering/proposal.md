# Proposal

## Why

`util.AndroidExportGateway` imported `ui.theme.eventTypeLabelRes` and took `RecordingEntity` on two of
its methods — a reverse dependency from a utility seam up into the UI layer and into Room entities. The
`export` capability's observable behaviour (share sheet, gallery, SAF folder) does not change, so there
is no spec delta; this is a structural fix that unblocks the Phase 2 domain-model boundary.

## What Changes

- Move the `EventType` → label-resource mapping from `ui.theme` to `data.model` (new `EventTypeLabels.kt`);
  `Theme.kt`'s composable `eventTypeLabel` now delegates to it. (Chosen over a `domain` mapper because
  Phase 2 forbids the domain layer from importing `R`.)
- Add `util.ExportMetadata(deviceName, eventType)`; `ExportGateway.shareFile` takes it instead of a
  `RecordingEntity`, and the entity-taking `saveFileToGallery` overload is dropped (the `deviceName`
  form was already the only one gallery saves need).
- `RecordingsUiEvent.Share` carries `metadata: ExportMetadata?` instead of the triple
  `recording / deviceName / eventTypeLabel` (the local and server share paths collapse into one shape).
- `AndroidExportGateway`, `RecordingsViewModel` and the test `FakeExportGateway` adapted.

## Impact

- `util` no longer imports `ui` (the two remaining `data.local.entity` imports in `CleanupPlan` /
  `CleanupRunner` are retention-planning inputs, addressed by the Phase 2 boundary change).
- No observable behaviour delta; `compileDebugKotlin` + `testDebugUnitTest` green.
