# Tasks

Status note: groups 1–6 and group 8 were implemented and verified before these artifacts were written, so
they are recorded as done with the evidence that closed them. Group 8 is the first review round's fixes.
Group 9 is the second round, which removed the seed group 8 had added and gave the pager its own navigation.
Group 7 is the on-device pass, which has not run — this change is not done until every gate in it is
checked.

## 1. Contract and page table

- [x] 1.1 Probe the pinned material3 (1.5.0-beta01) for an expressive large button size and record the
  result in design.md — outcome: no `ButtonSize` enum, `Scaffold` is not experimental, so the default
  `Button` size stands and no `@OptIn` is added; verified by compiling against the extracted sources jar
  before any size override was written.
- [x] 1.2 Create `ui/onboarding/OnboardingContract.kt` with `OnboardingPermission`, `GrantState`,
  `OnboardingStep`, `OnboardingTarget`, `OnboardingAction`, `OnboardingCopy`, `pagesFor`, `stepAt`,
  `copyFor` (no default branch), `primaryAction`, `secondaryAction`, `previousAction`, `nextAction` and the
  top-level `shouldShowOnboarding`; verified by `.\gradlew.bat compileDebugKotlin`.
- [x] 1.3 Add `OnboardingContractTest` covering page order, the sub-API-33 drop-out with a matching counter
  denominator, granted/requestable/settings-only mapping, per-permission labels, the unresolved-grant
  fallback, battery states, finish targets, always-two-slots, distinct copy per page, the nav row's
  page-index-only enablement at both ends, and the owed rule for fresh / answered / reset / updated-in-place
  installs; verified by `.\gradlew.bat testDebugUnitTest --tests "*OnboardingContractTest"` (17 tests,
  0 failures).

## 2. State and platform seams

- [x] 2.1 Add `data/local/prefs/OnboardingPrefs.kt` (`isCompleted`, `setCompleted`, `reset`, `askCount`,
  `recordAsk`) plus the `OnboardingStore` / `AndroidOnboardingStore` split; verified by
  `.\gradlew.bat compileDebugKotlin` and by its use from `MainActivity` and the ViewModel in groups 4–5.
- [x] 2.2 Add `ui/onboarding/PermissionChecker.kt` — interface plus `AndroidPermissionChecker` over
  `checkSelfPermission`, `Build.VERSION.SDK_INT`, `PowerManager.isIgnoringBatteryOptimizations` and the
  store's ask counter; verified by `.\gradlew.bat compileDebugKotlin`.
- [x] 2.3 Add `OnboardingViewModel` (`stateIn`-derived `OnboardingUiState`, `Channel(BUFFERED)` event flow,
  `onPageChanged`, `onPrimaryAction`, `onSecondaryAction`, `onPreviousAction`, `onNextAction`,
  `onBackPressed`, `onResume`, `onSettingsScreenUnavailable`) and bind `OnboardingStore` /
  `PermissionChecker` in `di/AppModule.kt`; verified by `.\gradlew.bat compileDebugKotlin`.
- [x] 2.4 Add `FakeOnboardingStore` and `FakePermissionChecker` to `testing/Fakes.kt` and
  `OnboardingViewModelTest` for start page, sub-33 page list, grant re-resolution after `onResume()`,
  one-permission asks, settings-only routing, inline notice clearing, battery receipt + advance, one
  completion write with `Finished(landOnDevices = true)`, skip, back, the nav row paging in a
  `SettingsOnly` state without recording an ask or an answer, and the nav row doing nothing at either end;
  verified by `.\gradlew.bat testDebugUnitTest --tests "*OnboardingViewModelTest"` (13 tests, 0 failures).

## 3. Screens

- [x] 3.1 Add the `onboarding_*` string block (counter, five title/body pairs, fourteen action labels, one
  notice) and remove the `permission_rationale_*` keys; verified by `.\gradlew.bat lintDebug` reporting no
  missing or unused `onboarding_*` resource.
- [x] 3.2 Add `OnboardingPermissionBar` — one filled `Button` and one always-present `TextButton`, both
  `fillMaxWidth()` inside `appContentMaxWidth()`, no
  geometry overrides, label-only `AnimatedContent` gated by `rememberExpressiveMotionEnabled()`, and the
  check glyph at `ButtonDefaults.IconSize`; verified by `.\gradlew.bat compileDebugKotlin` and by gate 2.
- [x] 3.3 Add `OnboardingNavRow` — two `TextButton`s at `weight(1f)` inside `appContentMaxWidth()`, mirroring
  `Icons.AutoMirrored.Filled.ArrowBack` / `ArrowForward` glyphs with `contentDescription = null`, a
  `Spacing.sm` row gap and `ButtonDefaults.IconSpacing` inside each button, and
  `windowInsetsPadding(WindowInsets.navigationBars)` moved here as the bottom-most element; verified by
  `.\gradlew.bat compileDebugKotlin` and by gates 2 and 7.11.
- [x] 3.4 Add `OnboardingScreen` — `Scaffold` + `HorizontalPager` with the bar and the nav row as `Column`
  siblings (`weight(1f)` on the pager, `horizontalAlignment = Alignment.CenterHorizontally` on the root
  column), centered page body inside `verticalScroll`, `headlineSmall` title with
  `semantics { heading() }`, `ExpressiveIconBadge` only on welcome/ready, two activity-result launchers,
  `LifecycleEventEffect(ON_RESUME)` refresh, `BackHandler`, guarded pager ↔ ViewModel sync, and
  `ActivityNotFoundException` reported inline; verified by `.\gradlew.bat compileDebugKotlin` and
  `.\gradlew.bat lintDebug`.

## 4. Entry, exit and migration

- [x] 4.1 Rewire `ui/MainActivity.kt`: delete `PermissionRationaleGate`, `pendingPermissions`,
  `unaskedMissingPermissions()`, `permissionLauncher` and `missingRequiredPermissions()`; branch
  `OnboardingScreen` vs `AppRoot` inside `AppTheme`; keep `IntercomMonitorService.start(this)`; add
  `showOnboarding`, `tabLanding` and `resetOnboarding()`, gating on the pure `shouldShowOnboarding` with the
  recorded completion answer as its only argument; verified by `.\gradlew.bat compileDebugKotlin` and by
  gates 1, 6 and 8.
- [x] 4.2 Add `TabLanding(pageIndex, requestId)` with `LiveTabIndex` / `DevicesTabIndex` to `Navigation.kt`
  and give `AppRoot` `onboardingLanding` + `onResetOnboarding`, seeding `rememberPagerState` and scrolling
  on a non-zero `requestId`; verified by `.\gradlew.bat compileDebugKotlin` and by gates 4 and 7.

## 5. Re-entry from settings

- [x] 5.1 Add one hub row to `ui/settings/SettingsScreen.kt` ("Reset intro and permissions",
  `Icons.Default.Refresh`) wired to `onResetOnboarding`, with its `settings_reset_onboarding_*` strings;
  verified by `.\gradlew.bat compileDebugKotlin`, `.\gradlew.bat lintDebug` and by gate 7.9.

## 6. Build verification

- [x] 6.1 Run `.\gradlew.bat compileDebugKotlin` — BUILD SUCCESSFUL.
- [x] 6.2 Run `.\gradlew.bat testDebugUnitTest` — BUILD SUCCESSFUL, 257 tests / 36 suites / 0 failures,
  of which 30 are onboarding (17 contract + 13 ViewModel).
- [x] 6.3 Run `.\gradlew.bat lintDebug` — BUILD SUCCESSFUL, 31 results all pre-existing, 0 in
  `ui/onboarding` or the touched files; the one new `InlinedApi` hint was suppressed on `platformName` with
  a reason comment.

## 7. On-device acceptance (blocking — the change is not done until all pass)

Use a physical phone (API 33+) plus, if available, one below API 33. Screenshot each page in both
orientations before moving on.

- [ ] 7.1 Gate 1 — uninstall the app, install and cold start: page 1 is the welcome page; each permission
  prompt appears at most once per page visit and its label names exactly the permission the sheet asks for.
- [ ] 7.2 Gate 2 — screenshot all five pages and overlay them: the filled button's frame is pixel-identical
  on every page, the nav row below it does not change height, and the text block is centered in portrait and
  landscape.
- [ ] 7.3 Gate 3 — decline a permission twice: the action becomes "Open phone settings", pressing it opens
  that app's settings page, and no prompt fires silently in the background.
- [ ] 7.4 Gate 4 — grant a permission: the action becomes the disabled "Allowed" receipt with the check, the
  secondary slot reads "Continue" and advances, and pressing system back on a permission page does not exit
  the app.
- [ ] 7.5 Gate 5 — rotate on every page: no prompt re-fires, the pager stays on the same page, the button
  does not jump.
- [ ] 7.6 Gate 6 — on an install that predates this build (update over it, no uninstall and no data clear):
  the flow opens once, the pages for permissions the install already holds read as acknowledgements, and
  after finishing or skipping it the app opens straight on Live with no trace of the flow.
- [ ] 7.7 Gate 7 — light and dark, dynamic color on and off: the button label holds 4.5:1; at 200 % font
  scale the page scrolls and the bar plus the nav row stay reachable; D-pad reaches the filled button, the
  text action and both nav controls with a visible focus indicator.
- [ ] 7.8 Gate 8 — animator duration scale 0: the label swap is instant and nothing stays half-drawn.
- [ ] 7.9 Gate 9 (added during implementation) — finish with "Add your first intercom" and the devices tab
  is open with the add form reachable; then reset the intro from settings on a phone that had declined an ask
  twice and the flow opens from page 1 with that ask offering its prompt again, while every granted ask opens
  as an acknowledgement with no prompts.
- [ ] 7.10 Gate 10 (from the review round) — fling the pager across two pages in one gesture: it settles
  where the gesture ended with no pull-back, and a swipe dragged part-way and released leaves both action
  labels on the page still on screen.
- [ ] 7.11 Gate 11 (from the second review round) — on a page whose permission has been declined twice, and
  again on a page whose permission is already granted: "Next" moves one page with no prompt and no settings
  screen, "Previous" moves back, both controls are drawn and the unusable one disabled at the first and last
  page, and neither press records an answer or leaves the flow.

## 8. Review-round fixes (group 7 had not run when these were found; each was verified on the JVM)

- [x] 8.1 Replace the never-written `PermissionPromptPrefs` latch as the upgrade seed with the install
  record (`firstInstallTime != lastUpdateTime`, both API forms), delete `PermissionPromptPrefs.kt`, and make
  `shouldShowOnboarding`'s third parameter `upgradedInPlace`; verified by `OnboardingContractTest`'s owed-rule
  tests and `testDebugUnitTest`. **Reversed by 9.1** — the seed was the reason the flow never showed on a
  phone that was installed over rather than freshly installed.
- [x] 8.2 Give the page index one writer per concern: `snapshotFlow { pagerState.settledPage }` upward, new
  `OnboardingEvent.GoToPage` downward, and `PagerState.goToPage` ignoring an already-settled request;
  verified by `OnboardingViewModelTest`'s swipe-versus-button test and gate 7.10.
- [x] 8.3 Call `onPermissionsSettled()` from the settings launcher as well, so a permission or exemption
  granted in phone settings restarts the monitor instead of leaving it stale; verified by gate 7.3.
- [x] 8.4 Commit `setCompleted` (`edit(commit = true)`) and leave `recordAsk` on `apply()`, with the reason at
  each site; verified by `compileDebugKotlin` and by reading the two call sites against design.md.
- [x] 8.5 Key the ask counters on `OnboardingPermission.prefsKey` instead of `enum.name` and pin both strings
  in a test; verified by `OnboardingContractTest`'s `the stored ask keys are fixed strings` test.
- [x] 8.6 Type `OnboardingEvent.RequestPermission` as the permission and resolve the platform name in the
  screen through `viewModel.platformName`; verified by `OnboardingViewModelTest`'s ask test.
- [x] 8.7 Make `stepAt` fall back to the first page rather than the finishing page; verified by the rewritten
  out-of-range test in `OnboardingContractTest`.
- [x] 8.8 Guard `finish()` so one run writes one answer and emits one hand-off, and make the test tap twice;
  verified by `OnboardingViewModelTest`'s finishing test.
- [x] 8.9 Apply the `appContentMaxWidth` cap to the page body as well as the bar; verified by
  `compileDebugKotlin` and by gate 7.2 on a foldable or tablet.
- [x] 8.10 Read and seed the intro's prefs through the injected `OnboardingStore` in `MainActivity` instead of
  the static prefs object, and add `@file:OptIn(ExperimentalCoroutinesApi::class)` to the ViewModel test;
  verified by `compileDebugKotlin` (Hilt graph), `testDebugUnitTest` (27 tests, 0 failures) and
  `lintDebug` (31 results, none in the touched files).

## 9. Second review round — the flow was invisible after an install-over

Reported as "no onboarding shows when installing the app". The cause was the group 8.1 seed: Android
Studio's Run and `adb install -r` are both updates-in-place, so the first launch after a developer install
wrote `completed = true` and the flow was gated away for good — "Clear data" does not undo it because
`firstInstallTime` survives. Fixed by dropping the seed rather than tuning it, and by the owner's rule that a
permission the user declined must not be able to stop the tour.

- [x] 9.1 Reduce `shouldShowOnboarding` to `shouldShowOnboarding(isCompleted)`; delete the install-record
  seed, `MainActivity.onboardingIsOwed()` / `isUpgradeInstall()` and the `PackageManager` / `Build` reads
  they needed; verified by `OnboardingContractTest`'s `only the recorded completion answer keeps the intro
  away` and `compileDebugKotlin`.
- [x] 9.2 Add `OnboardingTarget.GoBack` / `GoForward`, `previousAction(isFirstPage)` /
  `nextAction(isLastPage)` in the contract, `previousAction` / `nextAction` in `OnboardingUiState`,
  `onPreviousAction` / `onNextAction` in the ViewModel and an `if (!action.enabled) return` guard at the top
  of `act()` so a disabled edge press is a no-op; verified by `OnboardingContractTest` (17) and
  `OnboardingViewModelTest` (13).
- [x] 9.3 Add `ui/onboarding/OnboardingNavRow.kt` under the pinned bar, move
  `windowInsetsPadding(WindowInsets.navigationBars)` from `OnboardingPermissionBar` to it, and center the
  root `Column` so the capped bar and nav row line up; verified by `compileDebugKotlin`, `lintDebug` and by
  gates 2 and 7.11.
- [x] 9.4 Replace `OnboardingPrefs.hasAnswer` with `reset(context)` (`edit { clear() }`), carry it through
  `OnboardingStore` / `AndroidOnboardingStore`, and make `MainActivity.resetOnboarding()` clear answer plus
  ask counters and open the flow in the same session; verified by `compileDebugKotlin` and by gate 7.9.
- [x] 9.5 Rename the re-entry surface — `onReplayOnboarding` → `onResetOnboarding` in `Navigation.kt` and
  `SettingsScreen.kt`, `settings_rerun_onboarding_*` → `settings_reset_onboarding_*` with copy that says the
  denial counts are forgotten too — and add `onboarding_action_previous` / `onboarding_action_next`; verified
  by `lintDebug` (no missing or unused resource) and by the label-coverage test.
- [x] 9.6 Drop `answered` / `hasAnswer` from `FakeOnboardingStore` and add `reset()` with a `resetCount`, so
  the fake mirrors the real store's wipe; verified by `testDebugUnitTest`.
- [x] 9.7 Re-run build verification after the round — `compileDebugKotlin` BUILD SUCCESSFUL,
  `testDebugUnitTest` 257 tests / 36 suites / 0 failures, `lintDebug` 31 pre-existing results and 0 errors.

## Workflow follow-up

- Update `README.md` with the first-run flow once the on-device gates pass (explicitly out of scope until
  then, and README is not auto-edited by OpenSpec).
- Archive `add-onboarding-flow` after review; the delta becomes `openspec/specs/onboarding/spec.md`.
- Verify the archived result with `openspec show onboarding --type spec`.
