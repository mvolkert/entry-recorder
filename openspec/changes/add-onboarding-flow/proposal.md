# Proposal

## Why

The app has no first-run experience. On launch it fires a single `PermissionRationaleGate` modal that
asks for notifications and microphone together, and after two denials Android stops showing its sheet —
leaving the user with a button that silently does nothing and no route to the phone's own settings. Nothing
in the app explains what it watches, records and rings for, and nothing tells the monitor service to be
left alone by battery management, which is why calls go missing on a phone that has slept.

## What Changes

- New first-run flow: five centered pages in a `HorizontalPager` (welcome → notifications → microphone →
  battery exemption → ready), shown instead of the main UI when the install owes an answer.
- One pinned footer **outside** the pager holds the flow's entire action surface: a single filled `Button`
  whose geometry never changes between pages — only its label and target do — plus an always-present
  `TextButton` slot, so the filled button's position cannot shift.
- Each ask requests exactly **one** permission per launcher call, and its label names that permission.
- A permanent route to phone settings appears once the system stops showing its sheet for an ask, which
  removes the dead end the current modal has after two denials.
- Completion is recorded synchronously — committed, not queued — in SharedPreferences (same shape as the
  existing `UiModePrefs`/`AutoBootPrefs` mirrors) because `MainActivity` must decide before the first frame
  and must be able to trust the answer after a process death.
- Battery exemption opens the system's own list screen via
  `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`; no new manifest permission is requested.
- **BREAKING** (UI only): the launch-time `PermissionRationaleGate` modal, its `pendingPermissions` queue
  and its `missingRequiredPermissions()` helper are deleted from `MainActivity`.
- Installs that predate this build are seeded as answered on their first open and go straight to the main UI;
  they are not walked through a first-run tour. The modal this flow replaces persisted nothing, so the
  install record — not a stored flag — is the durable signal that a phone was asked before.
- Settings gains one hub row that re-runs the intro and permissions, handing the main UI a landing page so
  "Add your first intercom" opens the Devices tab.

## Capabilities

### New Capabilities
- `onboarding`: the first-run window — which pages exist on this device, what each page says, what its two
  actions do, how a permission state maps onto them, when the flow is owed, and how it ends.

### Modified Capabilities

None. No existing requirement changes. `settings-ui` keeps its requirements as written; the replay row is an
entry point into the flow and is specified under `onboarding` rather than duplicated there. The deleted
rationale modal was never covered by a spec (no requirement in `openspec/specs/` mentions permissions), so
its removal produces no delta.

## Impact

- `ui/MainActivity.kt` — gate removed; branches `OnboardingScreen(...)` vs `AppRoot(...)`; owns the
  owed/replay decision and the landing intent.
- New package `ui/onboarding/` — `OnboardingContract.kt` (pure page list + action table + copy + owed rule),
  `OnboardingViewModel.kt`, `OnboardingScreen.kt`, `OnboardingPermissionBar.kt`, `PermissionChecker.kt`.
- `data/local/prefs/OnboardingPrefs.kt` (+ `OnboardingStore`/`AndroidOnboardingStore`) — new; `di/AppModule.kt`
  binds both seams, and it is the only writer of that file — `MainActivity` reads and seeds through the store.
- `ui/Navigation.kt` — `AppRoot` takes an onboarding landing intent and an `onReplayOnboarding` callback.
- `ui/settings/SettingsScreen.kt` — one hub row.
- `res/values/strings.xml` — `onboarding_*` keys replace `permission_rationale_*`; two `settings_rerun_*` keys.
- Tests: `OnboardingContractTest` (16) and `OnboardingViewModelTest` (11), both JVM-only.
- No new permissions in the manifest, no Room schema change, no server or transport change.

### Out of scope

No new manifest permissions; no `CAMERA` runtime ask (capture is IP-camera snapshot based and the phone
camera is unused); no server/PBX provisioning steps; no edits to the device form itself — the last page only
navigates to it; no new localization; no README edit until this change is archived.

### Verification this change needs

Compile, unit tests and lint are necessary but not sufficient. The flow is accepted on a physical phone
against nine gates: fresh-install page order and one sheet per ask; pixel-identical filled-button frame
across all five pages in both orientations; deny-twice → "Open phone settings" with no silent re-fire;
grant → inert "Allowed" receipt that still advances, with the monitor service restarted on both ways back;
rotation without a re-ask or pager reset; an install that predates the build opening straight on Live;
light/dark with and without dynamic color at 200 % font scale plus D-pad focus; animator duration scale 0;
and the completion hand-off landing on the Devices tab with a re-run showing receipts rather than prompts.
They are listed as tasks and must pass before this change is called done.
