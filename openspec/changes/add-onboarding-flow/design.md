# Design

## Context

Motivation is in `proposal.md` — Why. The constraints that shape the approach:

- `MainActivity` decides what the window shows before it composes anything, so the "is the intro owed"
  answer has to be available synchronously. Room cannot provide that.
- The flow composes *outside* `AppRoot`, so `LocalAppSnackbar` has no host above it. Anything the flow needs
  to say about a failure has to live on the page.
- `Design.md` (the project's design contract) fixes component tokens, the 8 dp `Spacing` scale, the Playful
  tier for landing/completion moments and the low end of Balanced for functional screens, and requires
  expressive motion to be gated by `rememberExpressiveMotionEnabled()`.
- Android gives no reliable "denied until the sheet stops appearing" signal without an `Activity`, and
  cannot distinguish "never asked" from "permanently denied" — the flow has to record its own asks.
- minSdk is 26 and `POST_NOTIFICATIONS` only exists from API 33, so the page list cannot be a constant.
- The launch-time modal this flow replaces persisted nothing — it gated on `rememberSaveable` state that
  died with the process — so no stored flag can tell an install that was asked before from one that wasn't.
  The package's own install record can, but it is not read: an install cannot recover from an intro that
  never appeared, whereas an extra tour is one press away from being over.

## Goals / Non-Goals

**Goals:**

- The filled action's on-screen frame is byte-for-byte identical on every page, in both orientations, at
  every font scale. That is the acceptance criterion, not a nicety.
- Every rule the pages express (page list, label, target, enabled-ness, copy, owed-ness) lives in one pure
  table with no Android types, so it is testable on the JVM.
- No dead ends: any state reachable from the system prompt has a visible, working next action, and the page
  is always turnable — forward and back key off the page index, never off a grant state.

**Non-Goals:**

- No new manifest permission, and specifically no `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — that entry is on
  the AGENTS "ask first" list, so the battery page opens the system's own list screen instead of requesting
  the exemption directly.
- No Compose UI or Robolectric tests; the project has no activity-test host, so visual proof is the
  on-device pass.
- No changes to the device form, capture, SIP, or retention behaviour. The last page only navigates.
- No new locale, and no `strings.xml` translation work.

## Decisions

### One pure contract, one thin ViewModel, one screen with two pinned strips

`ui/onboarding/OnboardingContract.kt` holds `pagesFor(supportsNotifications)`, `stepAt`, `copyFor`,
`primaryAction`, `secondaryAction`, `previousAction`, `nextAction` and the top-level
`shouldShowOnboarding(isCompleted)`. The nav row's two actions are derived from the page index alone, so
the contract states plainly that nothing in a permission's grant state reaches them. `copyFor`
deliberately has no `else` branch — adding a page without copy is a
compile error, not a blank screen. `OnboardingViewModel` only holds state (`combine` → `stateIn(
SharingStarted.Eagerly)`) and dispatches; the one-shot intents the screen must carry out itself
(`RequestPermission`, `OpenAppSettings`, `OpenBatterySettings`, `GoToPage`, `Finished`) go through a
`Channel(BUFFERED).receiveAsFlow()` because they must not replay on rotation. `GoBack` / `GoForward` are
`OnboardingTarget`s the ViewModel resolves to `goTo(index ± 1)` rather than new events: the pager already
owns the downward `GoToPage` path, so the nav row reuses the one writer per concern below.
`RequestPermission` carries
the `OnboardingPermission`, not the manifest string: `platformName` is resolved where the sheet is fired, so
no platform constant crosses into the UI contract.

*Alternatives:* putting the action table in the composable (untestable, and the geometry guarantee becomes
an inspection rather than a property of the data); a sealed `When` inside the ViewModel (fine, but then the
page list is not reusable by the counter and the migration rule stays in `MainActivity`).

### The streamlined button: a `Column` sibling, not an overlay

`OnboardingScreen` composes `Scaffold(contentWindowInsets = WindowInsets(0))` → `Column` → `HorizontalPager`
with `Modifier.weight(1f)`, then `OnboardingPermissionBar` below it and `OnboardingNavRow` below that. The bar
is `Column(Modifier.appContentMaxWidth())` containing exactly
one `Button(Modifier.fillMaxWidth())` and one always-present `TextButton(Modifier.fillMaxWidth())`. No
height, width, corner, color or size overrides anywhere: `ButtonDefaults.buttonColors()`, default `shapes.full`
variant, and the 48 dp touch target comes from `Button`'s own `minimumInteractiveComponentSize`.

The root `Column` sets `horizontalAlignment = Alignment.CenterHorizontally` because both capped children are
narrower than the window on a foldable; centring the column is what keeps the bar and the nav row in one
vertical strip instead of two differently offset ones.

The plan proposed a bottom-anchored overlay in a `Box`; a `Column` sibling was chosen instead because an
overlay lets a 200 % font-scale page scroll its last lines under the button. The y-position is constant
either way because both slots always exist.

Only the label changes: `ActionLabel` swaps the text through `AnimatedContent` with
`fadeIn/fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec<Float>())`, skipped entirely when
`rememberExpressiveMotionEnabled()` is false, so animator duration scale 0 gives an instant swap. The
"Allowed" receipt draws `Icon(Icons.Default.Check, size = ButtonDefaults.IconSize)` with
`ButtonDefaults.IconSpacing` inside the same `Row`, so the glyph never resizes the container.

**Compile probe (material3 1.5.0-beta01, the pinned version):** there is **no `ButtonSize` enum** in this
artifact — sizing is height-constant driven (`ButtonDefaults.MinHeight` 40 dp, plus
`Medium/ContainerHeight`, `LargeContainerHeight`, `ExtraLargeContainerHeight`, `shapesFor`,
`contentPaddingFor`, `iconSizeFor`). `ButtonDefaults.IconSize` and `IconSpacing` do exist, and `Scaffold`
carries **no** `ExperimentalMaterial3Api` marker, so no `@OptIn` is needed. Consequence: the plan's
"one size up, if the pinned version exposes an expressive large size" is *not* taken — no such public size
exists for `Button`, and hand-setting a container height would be a §7 exception. The default size stands.

### Previous | Next is paging, not a second permission surface

`OnboardingNavRow` is a `Row` of two `TextButton`s, each `weight(1f)`, under the bar: back on every page but
the first, forward on every page but the last, both enabled by the page index and by nothing else. `act()`
returns immediately for a disabled action, so an edge press is a no-op rather than a wrap or an accidental
finish — the flow still ends only through its own finish/skip actions, which are the only writers of the
recorded answer. Both slots stay composed at the ends so the filled button above keeps the frame the plan
requires. The row is the bottom-most element and therefore carries
`windowInsetsPadding(WindowInsets.navigationBars)`; the bar no longer does, or the footer would grow by an
inset whenever the nav row is drawn. The glyphs are `Icons.AutoMirrored.Filled.ArrowBack` /
`ArrowForward` with `contentDescription = null` because each button already announces its label.

*Alternatives:* folding paging into the primary action (a grant state would then decide whether the user can
leave a page, which is exactly the dead end this flow removes); a single "Continue" that also advances the
asks (the primary action already owns that, and two controls doing one thing drift).

### Centered text is set per-`Text`, not per-column

`Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement =
Arrangement.spacedBy(Spacing.lg, Alignment.CenterVertically))` inside
`verticalScroll(rememberScrollState())`; every `Text` additionally sets `Modifier.fillMaxWidth()` and
`textAlign = TextAlign.Center` because centering the column does not center a wrapped line. Title is
`headlineSmall` + `Modifier.semantics { heading() }`, body `bodyMedium` on `onSurfaceVariant`, counter
`labelLarge` on `onSurfaceVariant`. Leading visuals: `ExpressiveIconBadge` on welcome and ready only
(Playful, per Design.md §3); the three functional pages get a plain 48 dp `Icon` tinted `primary` — no
container, no hero fill. The counter is a `Text` rather than a dot row, which would need a shape exception.

The page block sits in a `Box(contentAlignment = Alignment.Center)` carrying the same `appContentMaxWidth`
cap as the bar under it. Capping only the bar left a full-bleed paragraph over an 840 dp button on a
foldable: one screen, two measures.

### The pager owns position; the ViewModel owns where its buttons send it

An earlier shape had the page index in both places — `PagerState.currentPage` and a `MutableStateFlow` —
with two guarded `LaunchedEffect`s writing each other. The guards removed the trivial echo but not the
ordering: during a fling the pager is already on page 2 while the ViewModel's state still emits 1, and the
state-driven effect then scrolls the pager backwards. `PagerState.currentPage` also flips at the halfway
mark of a drag, so an aborted swipe changed the footer's labels and cleared the page's notice for a page
nobody landed on.

Now there is one writer per concern: pager → ViewModel through
`snapshotFlow { pagerState.settledPage }.distinctUntilChanged()`, and ViewModel → pager as a one-shot
`OnboardingEvent.GoToPage(index)` consumed by the screen's event collector. `onPageChanged` never bounces an
intent back down, and `goTo` never emits one for a page the pager already reported.

*Alternatives:* a `scrollRequest: Int?` state field cleared after consumption (still two owners of position,
plus a reset write during composition); hoisting `PagerState` into the ViewModel (a framework type in the
ViewModel, which the project's architecture rules keep out).

### Ask counting is the app's own, in a synchronous store

`OnboardingPrefs` (SharedPreferences `"onboarding"`: `completed`, `ask_<key>` counters) behind an
`OnboardingStore` interface + `AndroidOnboardingStore` (the `AutoBootStore`/`ThemeModeStore` split), and a
`PermissionChecker` interface + `AndroidPermissionChecker` so the ViewModel holds no `Context`. Nothing else
reads or writes that file — `MainActivity` injects the store rather than calling the prefs object, which
keeps one write path and leaves the Activity's half of the migration rule reachable from a fake.
`grantState` returns `Granted` when `checkSelfPermission` says so, otherwise `SettingsOnly` once
`askCount >= 2` and `Requestable` below that. Two is the number Android takes before it stops showing the
sheet; the third request would be a silent "no" that looks like a broken button.

Two rules about that file are deliberate. The counter is keyed on `OnboardingPermission.prefsKey`, a fixed
string, not on `enum.name`: a rename would otherwise reset every installed denial counter and re-arm the
prompt the `SettingsOnly` row exists to prevent. And `setCompleted` commits while `recordAsk` uses the
default `apply()` — this store is synchronous precisely so `MainActivity` can trust one answer before the
first frame, so that write is durable; losing an ask count only costs one extra prompt.

*Alternatives:* `ActivityCompat.shouldShowRequestPermissionRationale` — needs an Activity, conflates "never
asked" with "permanently denied", and would make the action table depend on state the app never recorded.
DataStore — asynchronous first read, which is exactly what `MainActivity` cannot wait for. Room — same
problem, plus a schema change for two booleans.

### Landing on a tab needs a nonce

`MainActivity` holds `showOnboarding` and `tabLanding` as `MutableStateFlow`s and branches
`OnboardingScreen(...)` vs `AppRoot(...)` inside the existing `AppTheme` wrapper. Because `AppRoot`'s
pager state is `rememberPagerState` (saveable), a plain `Int` target would be a no-op the second time the
same tab was requested, so the intent is `data class TabLanding(pageIndex, requestId)` and `AppRoot`
scrolls in `LaunchedEffect(onboardingLanding.requestId)` when `requestId > 0`. "Add your first intercom"
→ `DevicesTabIndex`; skip / browse-without-device → `LiveTabIndex`.

### Migration and re-entry

`shouldShowOnboarding(isCompleted)`: the recorded completion answer is the only input. No answer recorded —
including on an install that updated into this build — shows the pages; an answer, completion or skip, does
not. The earlier rule also read the package's install record to seed `completed = true` for update-in-place
installs; that seed is removed because it made the intro unreachable on every development phone that had
ever been reinstalled (Android Studio's Run and `adb install -r` are both updates-in-place, and
`firstInstallTime` survives "Clear data"), and because a permission the user declined must not be able to
keep the tour away. The cost is one unavoidable tour per pre-existing install, which the owner took.

Re-entry wipes rather than rewinds: the Settings row calls `OnboardingPrefs.reset()`, an `edit { clear() }`
over the whole `"onboarding"` file, so the completion answer and both `ask_<key>` counters go together and
the pages re-ask what the counters had silenced. `MainActivity` then flips `showOnboarding` in the same
session, so the flow re-enters at page 0 and every already-granted ask renders as an acknowledgement rather
than a prompt. `PermissionPromptPrefs`, the never-written latch this replaced, is deleted.

Ending the flow is likewise single-shot: a `finished` guard in the ViewModel makes a second tap on finish or
skip a no-op, so one run records one answer and emits one hand-off — otherwise a double tap writes twice
and bumps the landing `requestId` a second time, moving the tabs somewhere the user did not choose.

Both routes back into the app — the system sheet and the settings screen — refresh the monitor service, not
just the ViewModel. After two denials the settings hop is the only way left to grant anything, and the
battery exemption is only ever granted there; a service left running with the permissions the user just
changed is the failure this flow exists to remove.

### Deviations from the plan, recorded because a later reader will compare them

- System back walks one page and skips from the first, instead of "skips forward then exits": leaving
  mid-onboarding strands an install that was never finished.
- A prompt can be fired twice per install before the CTA becomes "Open phone settings". The plan's gate 1
  ("at most once") and gate 3 ("deny twice → settings route") are in tension; gate 3 wins because it is the
  bug being fixed, and the counter is what makes it checkable.
- Footer is a `Column` sibling rather than a bottom overlay (see above).
- The landing intent carries a `requestId` nonce instead of a bare index.
- `copyFor`, `previousAction` / `nextAction` and `shouldShowOnboarding` were added to the contract so the
  copy coverage, the paging rules and the owed rule are unit-testable rather than living in `MainActivity`.
- The plan's "no gating" is implemented as the single-Boolean owed rule. The implementation briefly carried a
  second signal — the install record, used to seed old installs as answered — and that seed is what made the
  flow invisible after an update-in-place, so it was removed rather than tuned.

## Risks / Trade-offs

- [Android's exact denial threshold is device-behaviour, not a documented constant] → the counter limit is
  one named constant (`VisibleAskLimit = 2`) and the on-device gate re-checks it on a real phone; a wrong
  number shows up as an extra prompt on gate 3, not as a crash.
- [The ask counter never resets, so an install that granted a permission years ago and later revoked it
  lands straight on "Open phone settings"] → acceptable: the route still works, it just skips a prompt the
  system may no longer show; re-running the intro from Settings shows the acknowledgement path instead.
- [`SharedPreferences` writes are synchronous on the main thread] → one committed boolean plus an `apply()`ed
  counter, same pattern and same budget as `UiModePrefs` and `AutoBootPrefs`; no new I/O shape.
- [The pager's settle is now the only thing that moves the flow's state, so a scroll interrupted by a second
  tap could leave the labels a page behind] → `goTo` requests the page it intends and the collector skips a
  request the pager has already settled on; gate 5 and the fling scenario are the checks.
- [Every pre-existing install shows the intro once after this update, including ones that were asked by the
  dialog the flow replaces] → the accepted price of an owed rule that no permission state can suppress; the
  pages for permissions the install already holds open as acknowledgements, and Previous | Next lets the user
  walk past them without granting anything. Gate 6 checks the behaviour instead of an exemption.
- [A reset that cleared only the answer would leave the ask counters and reopen a page whose prompt the
  system no longer shows] → `reset()` clears the whole prefs file, which is the only way the row can honestly
  promise "show the first-run pages again".
- [Two slots always composed means a page with nothing to decline shows a `TextButton` that reads oddly] →
  its label is page-specific ("Continue", "Leave as is", "Skip setup"), which is why the labels come from
  the same table as the targets.
- [Battery-optimization list screen is absent or renamed on some skins] → the intent launch is wrapped and
  failure is stated inline on the page, and the secondary slot still advances the flow.
- [`stepAt` answers an index the page list does not have with the *first* page] → deliberately not the last:
  `Finish`'s primary action records a completion answer and closes the flow, so a page-list mismatch would
  fail by silently finishing onboarding rather than by looking wrong.

## Migration Plan

1. Land the code as written; nothing in Room or the manifest changes, so there is no data migration.
2. Installs that predate the build simply get the tour once. No seed and no migration code reads the install
   record, and `PermissionPromptPrefs` is deleted rather than kept as a legacy read: nothing in the
   repository's history ever wrote its key, so reading it could only ever return false.
3. Rollback is a revert of the commit: the flow's prefs file is additive and unused by anything else, so an
   install that ran the flow and rolls back simply sees the old modal again.
4. Ship only after the eleven on-device gates in `tasks.md` pass; green compile/test/lint is not acceptance.
