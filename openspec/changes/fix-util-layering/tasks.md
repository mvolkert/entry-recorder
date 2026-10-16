# Tasks

## 1. Mapping relocation

- [x] 1.1 New `data/model/EventTypeLabels.kt` owning the `EventType` → `@StringRes` mapping.
- [x] 1.2 `Theme.kt` deletes its `eventTypeLabelRes` and imports the data-layer mapping; `eventTypeLabel` unchanged for composables.

## 2. Gateway seam

- [x] 2.1 `ExportGateway`: add `ExportMetadata`; `shareFile` takes metadata; drop both `RecordingEntity` overloads.
- [x] 2.2 `AndroidExportGateway` resolves the label from `eventTypeLabelRes(metadata.eventType)`; no `ui` or entity imports left.

## 3. Callers

- [x] 3.1 `RecordingsUiEvent.Share` carries `metadata`; `deliver`/`deliverRemote`/`share()` adapted; batch share unchanged (null metadata).
- [x] 3.2 `FakeExportGateway` in tests adapted.

## 4. Verification

- [x] 4.1 `compileDebugKotlin` green.
- [x] 4.2 `testDebugUnitTest` green.
