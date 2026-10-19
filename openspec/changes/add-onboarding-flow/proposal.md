# Proposal

## Why

The app has no first-run experience. On launch it fires a single `PermissionRationaleGate` modal that
asks for notifications and microphone together, and after two denials Android stops showing its sheet —
leaving the user with a button that silently does nothing and no route to the phone's own settings. Nothing
in the app explains what it watches, records and rings for, and nothing tells the monitor service to be
left alone by battery management, which is why calls go missing on a phone that has slept.

## What Changes

- New first-run flow: five centered pages in a `HorizontalPager` (welcome → notifications → microphone →
  battery exemption → ready), shown instead of the main UI whenever the install has no recorded answer.
- One pinned footer **outside** the pager holds the flow's entire action surface: a single filled `Button`
  whose geometry never changes between pages — only its label and target do — plus an always-present
  `TextButton` slot, and beneath them the pager's own Previous | Next row. All three are always composed,
  so the filled button's position cannot shift.
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
- Nothing but the recorded answer gates the flow. A permission the user declined, a denial count and the
  install record are not read by the rule, so an install that updated into this build sees the pages once
  (the pages whose permissions it already holds open as acknowledgements) and an install that was answered
  does not. This reverses the earlier in-place-upgrade seed: the consequence is that existing installs get
  the intro once after this update, which is the trade the owner took to keep the flow reachable.
- Settings gains one hub row that resets the intro — clearing the recorded answer and both denial counts,
  then opening the flow — and hands the main UI a landing page so "Add your first intercom" opens the
  Devices tab.

## Capabilities

### New Capabilities
- `onboarding`: the first-run window — which pages exist on this device, what each page says, what its two
  actions do, how a permission state maps onto them, when the flow is owed, and how it ends.

### Modified Capabilities

None. No existing requirement changes. `settings-ui` keeps its requirements as written; the reset row is an
entry point into the flow and is specified under `onboarding` rather than duplicated there. The deleted
rationale modal was never covered by a spec (no requirement in `openspec/specs/` mentions permissions), so
its removal produces no delta.

## Impact

- `ui/MainActivity.kt` — gate removed; branches `OnboardingScreen(...)` vs `AppRoot(...)`; owns the
  owed/reset decision and the landing intent.
- New package `ui/onboarding/` — `OnboardingContract.kt` (pure page list + action table + copy + owed rule),
  `OnboardingViewModel.kt`, `OnboardingScreen.kt`, `OnboardingPermissionBar.kt`, `OnboardingNavRow.kt`,
  `PermissionChecker.kt`.
- `data/local/prefs/OnboardingPrefs.kt` (+ `OnboardingStore`/`AndroidOnboardingStore`) — new; `di/AppModule.kt`
  binds both seams, and it is the only writer of that file — `MainActivity` reads and resets through the store.
- `ui/Navigation.kt` — `AppRoot` takes an onboarding landing intent and an `onResetOnboarding` callback.
- `ui/settings/SettingsScreen.kt` — one hub row.
- `res/values/strings.xml` — `onboarding_*` keys replace `permission_rationale_*`; `onboarding_action_previous`
  and `onboarding_action_next`; two `settings_reset_onboarding_*` keys.
- Tests: `OnboardingContractTest` (17) and `OnboardingViewModelTest` (13), both JVM-only.
- No new permissions in the manifest, no Room schema change, no server or transport change.

### Out of scope

No new manifest permissions; no `CAMERA` runtime ask (capture is IP-camera snapshot based and the phone
camera is unused); no server/PBX provisioning steps; no edits to the device form itself — the last page only
navigates to it; no new localization; no README edit until this change is archived.

### Verification this change needs

Compile, unit tests and lint are necessary but not sufficient. The flow is accepted on a physical phone
against eleven gates: fresh-install page order and one sheet per ask; pixel-identical filled-button frame
across all five pages in both orientations; deny-twice → "Open phone settings" with no silent re-fire;
grant → inert "Allowed" receipt that still advances, with the monitor service restarted on both ways back;
rotation without a re-ask or pager reset; an install that updated into this build showing the flow once;
light/dark with and without dynamic color at 200 % font scale plus D-pad focus; animator duration scale 0;
the completion hand-off landing on the Devices tab with a reset showing prompts that had gone quiet; the
pager's fling and part-way-swipe behaviour; and the Previous | Next row paging past a twice-denied
permission with its ends disabled and the filled button's frame unmoved. They are listed as tasks and must
pass before this change is called done.
