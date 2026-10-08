# Design: Material 3 Expressive Overhaul

Status: **landed and verified** — theme root, icons, animations (incl. hero transitions), cards and
vibrance are all on the expressive system. Committed as `3441cf5` (cards), `dc7c890` (theme root),
`5f78477` (morphable icons), `6b1b89c` (hero transitions). Remaining optional polish is listed in
"Decided holds" and "Next decisions" rather than silently skipped.

## Summary

EntryRecorder runs on the real Material 3 Expressive theme, not a hand-ported stand-in. The
`AppMotionScheme` seam existed only because `MaterialExpressiveTheme` and `MotionScheme` were
`internal` in the resolved `material3:1.4.0` (compiler-proven, see Availability). With an approved
bump to `1.5.0-beta01` the APIs became callable, the seam was deleted, and `AppTheme` now roots on
`MaterialExpressiveTheme(colorScheme, motionScheme = MotionScheme.expressive())`. Expressive motion,
shapes, typography and component styling flow from that single root; the app then adds four bespoke
expressive layers on top: morphing state icons, spring-wired screen and hero transitions, press-reactive
cards, and a curated vibrant color system.

## Availability — proven with compile probes, not docs

- `material3:1.4.0` (the `2026.09.00` BOM ceiling): `Cannot access 'interface MotionScheme': it is
  internal in file`, and the same for `MaterialExpressiveTheme`. Root cause of the former seam.
- `material3:1.5.0-beta01`: both callable behind one `@OptIn(ExperimentalMaterial3Api)`. The compiler
  also warned `This extension is shadowed by a member: 'val motionScheme: MotionScheme'` — the platform
  member superseded the app extension, so the five motion call sites needed only the obsolete import
  dropped, not a signature change.
- M3 **Layout Transform** (`LayoutTransform`, `TransformSpec`, `MorphOrigin`): unresolved even in
  `1.5.0-beta01`. The hero transitions therefore run on `SharedTransitionLayout` +
  `Modifier.sharedBounds` from `compose-animation` (resolved `1.12.1` via the BOM), which the probe
  confirmed: `sharedBounds`/`rememberSharedContentState` are members of `SharedTransitionScope` and
  there is no public `LocalSharedTransitionScope`, so the scope is prop-threaded from the layout.

## 1. Icons

- **`MorphingIcon`** (`ui/components/MorphingIcon.kt`) is the single morph primitive: an
  `AnimatedContent` swap where the outgoing icon fades + scales out to 0.5 while the incoming one
  springs in, both on `MaterialTheme.motionScheme.fastSpatialSpec`. True vector path-morphing needs
  paired `ImageVector`s that Material icons do not ship, so this is the robust expressive treatment
  rather than a hand-authored morph.
- Wired at every binary state toggle: play/pause (`JpegFramePlayer`), mic and speaker
  (`IncomingCallControls`), monitor visibility (`LiveCamerasScreen`), password reveal
  (`DeviceFormComponents`), connection-test result (`DeviceFormTestSection`).
- Deliberately not wired: the recording-card protect lock — it lives in a dropdown that closes on
  tap, so an in-place morph would never be seen.
- `FilterChip` selection in the recordings filter gets the platform's icon-morph selection animation
  for free from the theme root.

## 2. Animations

- **No hand-rolled springs or tweens survive.** Every explicit animation spec reads the expressive
  scheme through the platform `MaterialTheme.motionScheme` member: tab/detail slide+fade
  (`Navigation.kt`, `defaultSpatialSpec`/`defaultEffectsSpec`), adaptive chrome (`AdaptiveScaffold.kt`,
  `slowSpatialSpec`), player modal enter/exit (`VideoPlayerModal.kt`, `fastEffectsSpec`/`fastSpatialSpec`),
  recording card state (`RecordingCardItem.kt`, `defaultEffectsSpec`), settings section expansion
  (`SettingsComponents.kt`, `fastSpatialSpec`), icon morphs (`MorphingIcon.kt`, `fastSpatialSpec`).
- **Hero transitions** (`6b1b89c`): one `SharedTransitionLayout` wraps the whole `NavHost`, providing
  a single `SharedTransitionScope`; each destination's `AnimatedVisibilityScope` is the
  `composable {}` content receiver (navigation-compose 2.10.1), threaded explicitly since no public
  composition local exists. `DeviceCard` is the hero source keyed `device-<id>`, and the
  `DeviceEditScreen` `Scaffold` is the target with the same key — the tapped card morphs into the
  full edit surface and back. The add-device route (`NEW_DEVICE_ID`) finds no partner and renders
  normally, which is the correct fallback.
- **Structural limit, by design:** shared bounds only work inside one Compose window. The playback
  modal is a `Dialog` and the incoming-call screen a separate `Activity`, so no card-to-modal or
  card-to-call hero is possible there; the same-NavHost device pair is the meaningful one.

## 3. Cards

- Settings section cards and the hub cards (`3441cf5`) are expressive: press-driven scale via
  `animateFloatAsState` + `collectIsPressedAsState` on a `graphicsLayer`, tonal icon badges with
  semantic container colors, and the trailing forward arrows and redundant instructional labels
  removed as visual clutter.
- All card containers consume `MaterialTheme.shapes.*` roles, so the expressive shape scheme from
  the theme root (larger, rounder, more uniform cornering on `Card`, `FAB`, `Dialog`, `Surface`)
  applies with no per-widget edits; `DeviceCard` is also the hero source, so its bounds participate
  in the layout transition.
- Token hygiene: literals were replaced by tokens where found (`RoundedCornerShape(50)` →
  `CircleShape`, `16.dp` insets → `Spacing.lg` in `VideoPlayerModal`); typography uses
  `MaterialTheme.typography` roles throughout.

## 4. Vibrance (color)

- Theme root: `MaterialExpressiveTheme` with the eight curated, contrast-safe `AccentPreset` palettes
  (`ui/theme/Theme.kt`), per-role accent derivation, and the dynamic-color bypass kept as-is —
  colors are expressive by scheme, not by new colors invented here.
- Semantic containers carry state instead of decoration: `errorContainer` for muted mic / offline and
  delete affordances, `secondaryContainer` for speaker-on, `surfaceVariant` card fills, and the
  `StatusColor` palette plus `monitorStatusColor`/`eventTypeLabel` for live and recording status —
  consistent light/dark via `LocalDarkTheme`.
- Call controls use scrim-over-stream with tonal circular buttons; the REC capsule and event chips
  stay within the semantic roles (no custom colors outside `StatusColor`).

## What the expressive root delivers automatically

Bouncy spring motion on selection and state changes; expressive shapes on all M3 containers;
restyled `Switch`, `Slider`, `Button`, `NavigationBar`/`NavigationRail` items; `FilterChip` icon
morphs; dialog/sheet enter-exit springs; and the expressive typography baseline that the existing
role names (`titleMedium`, `labelMedium`, …) resolve against.

## Decided holds

- M3 `LayoutTransform`-based form morphs (list-item shape transforms between breakpoints): API not
  public in any reachable material3; `sharedBounds` covers the hero use case instead.
- Vector path-morph icons: requires custom paired `ImageVector`s; scale+fade morph chosen as the
  robust equivalent.
- Cross-window heroes (modal player, incoming-call Activity): structurally impossible with the
  available API.

## Next decisions (not started, each is a bespoke visual change)

- Adopt expressive-only type roles (larger display sizes / weight updates) in hero spots: recording
  headers, live screen status, settings hub titles.
- Shape-variant exploration: the expressive corner-form variants material3 1.5.x offers (beyond the
  standard rounded scale) on cards and FABs, verified by compile probe before adoption.
- A second hero pair for recordings: card → player surface would require lifting `VideoPlayerModal`
  out of its `Dialog` into the NavHost window.
- Per-component motion tuning (sheet, snackbar, FAB extend) where the defaults feel off on device.

## Verification

- `gradlew.bat :app:compileDebugKotlin` — green at every commit; seam-shadow warning gone.
- `gradlew.bat :app:testDebugUnitTest` — green (contrast and identity suites unaffected).
- `gradlew.bat :app:lintDebug` — green, no new findings.
- Not verified: on-device visual pass across dark/light, all eight accents, and the morph/hero feel —
  run before merge. Android Studio may flag `MaterialTheme.motionScheme` as internal until it
  re-syncs the `1.5.0-beta01` override; Gradle compilation is the authority.

## Risks

- **Beta dependency.** `material3:1.5.0-beta01` can shift before stable; it is pinned via the
  `material3Expressive` version ref and must be re-checked on every bump. Accepted cost of reaching
  the expressive API ahead of stable.
- **Motion values shifted.** Platform `MotionScheme.expressive()` springs differ from the retired
  hand-ported constants, so animation feel changes by design; regressions get tuned at the call
  site, not by reintroducing a seam.
- **Experimental API surface.** `@ExperimentalMaterial3Api` (theme root) and
  `@ExperimentalSharedTransitionApi` (heroes) can break across prereleases; both opt-ins are
  localized to the few files that need them.
