# Tasks

## 1. Spacing sweep

- [x] 1.1 Replace the raw gaps in `ui/settings/SettingsComponents.kt` (16 / 12 / 16) and
  `ui/settings/SettingsEngineCard.kt` (chip row gap, divider height, badge widths and heights) with
  `Spacing.*`, leaving the spinner's own size and stroke raw, and verify `:app:compileDebugKotlin`

- [x] 1.2 Replace the raw gaps in `ui/settings/DeviceFormComponents.kt` (segment label gap 6 → `Spacing.sm`,
  two field-row gaps 8 → `Spacing.sm`) and verify on-device that the compact window still renders the
  dropdown variant with unellipsized labels (the fit test measures against `maxWidth / count - 32.dp`, which
  this does not touch)

- [x] 1.3 Replace the raw gaps in `ui/components/LiveStreamPlayer.kt`, `ui/components/RtspVideoPlayer.kt`
  (including the now-unused `dp` import) and the three trigger gaps in `ui/live/LiveCamerasScreen.kt`, and
  verify the Live card still renders in both themes

- [x] 1.4 Extend the `Spacing` KDoc to name badge interiors as the exception, and verify by grep that the
  remaining raw dp literals are component metrics, not inter-component gaps

## 2. Navigation icon scale

- [x] 2.1 Remove `Modifier.scale(iconScale)` and its `animateFloatAsState` from `AdaptiveScaffold.kt`, keep
  the label colour/weight emphasis and the component's own indicator, and record the reason in the
  `AdaptiveIcon` KDoc; verify `:app:compileDebugKotlin` with the now-unused imports deleted

- [x] 2.2 A/B on-device in both themes: pixel-sample the bar and confirm the selected destination shows the
  expressive indicator (`#E2D7FF` against the `#F3EDF7` bar in light) and that the indicator moves when the
  destination changes; the unselected icons keep their resting size, so no token-driven scale is restored

## 3. Status palette becomes documented intent

- [x] 3.1 Collect the fixed hues into `object StatusPalette` in `ui/theme/Theme.kt` with a KDoc stating they
  deliberately bypass the scheme, re-point `statusColorFor`, `recordingStatusColor`, `onRecordingStatusColor`,
  `recordingGlyphColorFor`, `statusWarnColor` and `monitorStatusColor` at it, and verify
  `:app:testDebugUnitTest` keeps `StatusPaletteContrastTest` green

## 4. One badge component

- [x] 4.1 Add `compact: Boolean = false` to `StatusChip` (5/1 padding, 3 gap, 5 dot, `labelSmallEmphasized`)
  with the tightness constraint documented, and verify `:app:compileDebugKotlin`

- [x] 4.2 Replace the Live REC capsule's inline `Surface(...)` with `StatusChip(compact = true)` passing
  `contentColor`, and verify `:app:lintDebug` plus the removed `CircleShape` / `Surface` / `FontWeight` /
  `sp` imports

- [x] 4.3 Measure the fit on-device in dark and light: with a recording active the REC badge occupies the
  same 395–437 line as the monitoring pill and the next header line stays at y = 505, so `labelSmallEmphasized`
  replaces the old 9.sp/12.sp literals without reflowing the card

## 5. Documentation

- [x] 5.1 Add the call-screen FAB sizes (64 / 68 / 72 dp and their glyphs) to `Design.md` §7 as an
  intentional off-scale idiom, verified by reading the three literals in
  `ui/incoming/IncomingCallControls.kt` (a live SIP call is not available on the AVD, so this stays
  code-reviewed)

- [x] 5.2 Correct `Design.md` §7 for the retired REC-capsule type exception and the stadium-shape bullet, and
  verify no stale reference to 9.sp/12.sp remains in the repo

## 6. Gates

- [x] 6.1 Run `./gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug` green

- [x] 6.2 On-device dark + light pass over the Live header, Settings hub and submenu, engine card, device
  form, Recordings chips and navigation bar

## Workflow follow-up

- Archive after the `theme-appearance` delta is reviewed; the incoming-call sizes remain code-reviewed only,
  which is the plan's stated limit for that screen.
