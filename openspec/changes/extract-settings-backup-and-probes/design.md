# Design

## Context

See proposal.md — Why. `SettingsViewModel` currently declares four `combine(…).distinctUntilChanged().map(…)`
flows that hit the SAF provider or the filesystem (`exportFolderAccess`, `storageUsage`, `externalUsageBytes`,
`exportFolderName`), all re-triggered by one `probeTick`; three separate probe state holders for the device form
with copy-pasted "ignore a second call while running" guards; `exportBackup` / `restoreBackup` inline with Gson,
`normalizedForRestore()`, credential re-encryption, and the `serverDeviceId = null` reset rule; plus device
CRUD/settings mirrors (`themeModeStore`, `autoBootStore`) that must stay in the ViewModel because `MainActivity`
and `BootReceiver` read the synchronous stores before Room is warm.

## Goals / Non-Goals

**Goals:**
- Each cluster's *work* lives in exactly one injected class with a narrow dependency set.
- The ViewModel's dependency list and file size drop without moving screen-state ownership.
- Every extracted runner is constructible in a JVM test with hand-written fakes only.

**Non-Goals:**
- No child ViewModels, no `@Immutable`/`@Stable` additions, no change to `SettingsUiState`'s shape.
- No change to what the probes do against the network or the registrar.

## Decisions

- **State stays in the ViewModel.** AGENTS.md mandates unidirectional data flow with the ViewModel exposing
  immutable `StateFlow`s, and `stateIn(viewModelScope, …)` cannot move into a process-scoped `@Inject` singleton
  without keeping the folder probe ticking after the screen leaves. So the runners are `suspend` functions and one
  cold `Flow<Float>`, not state owners. Alternative considered and rejected: coordinators that own the flows and
  take `viewModelScope` from the VM — it works for a per-screen helper (`GalleryExportCoordinator`) but here three
  of the four flows are pure provider reads the storage card could drive itself, and the extra indirection buys
  nothing a `suspend fun` does not already give.
- **`BackupRestoreRunner` lives in `data.backup`** because its whole job is document I/O plus entity mapping
  (`toEntity(credentialCipher)`, `normalizedForRestore()`), and it returns a non-UI `BackupRestoreOutcome`; the
  VM maps outcome → message, keeping message wording in the presentation layer.
- **`themeModeStore` is injected into both the VM and the backup runner.** Both must mirror the theme
  synchronously (`updateSettings` and `restoreBackup`); duplicating the rule in a shared helper is the alternative,
  but the runner applying it next to the Room write it belongs to is the smaller change.
- **`DeviceProbeRunner` returns UI state types** (`DeviceTestState`, `SipTestState`, `SnapshotProbeState`), so it
  must live in `ui.settings` — the archived `fix-util-layering` change established that `util` does not import
  `ui`, and putting it there would re-open that violation.
- **The three "second call while running" guards stay in the VM**, expressed once as a small private helper over
  the three `MutableStateFlow`s, because they are about the *screen's* in-flight state, not the probe.
  `monitorUsesSip` becomes a delegation to `DeviceProbeRunner.usesActiveRegistrar`, since `SipRegistrar` moves
  onto the runner.
- **`measuredSnapshotFps`'s poll loop moves into the runner as a cold `Flow<Float>`** taking the device-id
  `StateFlow` as a parameter; the VM keeps `flowOn(ioDispatcher).stateIn(viewModelScope, …)` and the
  `MEASURED_FPS_POLL_MS` constant moves with the loop.

## Risks / Trade-offs

- [Restore path subtly reordered (theme mirror before Room write)] → keep the statement order identical inside
  `BackupRestoreRunner`; `AppSettingsRestoreTest` plus a new runner test pin the order.
- [Guard helper changes probe semantics] → the helper only reads/writes the existing state holders; verified by
  keeping the current probe cases in `SettingsViewModelTest` running through the VM, not only the runner.
- [More classes, more indirection for a reader] → accepted: each runner's dependency list is 3–5 entries versus
  the current 18-argument constructor, and the test setup no longer needs real OkHttp clients.

## Migration Plan

Three independent extractions (backup → storage hub → probes), each compiling and tested on its own; rollback =
revert a single cluster.

## Open Questions

- None. Whether `StorageHubRunner` also absorbs `probeTick` bumping is deferred to implementation and cannot
  change behaviour either way (the VM keeps the tick).
