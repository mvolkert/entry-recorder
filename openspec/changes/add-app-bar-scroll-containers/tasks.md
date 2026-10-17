# Tasks

## 1. The band helper

- [x] 1.1 Add `HeaderBandColor(state: TopAppBarState)` in `ui/theme/HeaderBand.kt` returning
  `surfaceContainer` above `overlappedFraction > 0.5f` and `surface` below, animated with
  `MaterialTheme.motionScheme.defaultEffectsSpec()`, and verify `:app:compileDebugKotlin` is clean

## 2. Wire the scroll behaviour

- [x] 2.1 Give the Live screen a `pinnedScrollBehavior(rememberTopAppBarState())`, pass it to its
  `TopAppBar`, and attach `Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)` to its scrolling
  root; verify by scrolling that screen on-device
- [x] 2.2 Do the same for the Recordings tab (bar in `RecordingsTopBar`, connection on the gallery
  `LazyColumn` only — not the copy inside the search surface) and verify the search field still types
- [x] 2.3 Do the same for the device editor and verify by scrolling the form on-device
- [x] 2.4 Do the same for the settings sub-screens (`SettingsComponents.kt`, which covers the hub and all
  four submenus) and the settings root, verifying one overflowing sub-screen and one non-overflowing hub

## 3. One painter for the Recordings band

- [x] 3.1 Delete the hand-painted `.background(MaterialTheme.colorScheme.surface)` on the Recordings band,
  paint the wrapping column with `HeaderBandColor(scrollBehavior.state)` and set the bar's
  `containerColor` / `scrolledContainerColor` transparent; verify `contentWindowInsets = WindowInsets(0)`
  and the inset-ownership comments are untouched

- [x] 3.2 Verify on-device that bar, search row, storage line and filter chips read as one surface at rest
  in both themes (pixel-sampled: uniform `#FFFBFE` in light)

## 4. Gates and device evidence

- [x] 4.1 Run `./gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug` green

- [x] 4.2 On-device, light: sample a scrolling screen's band at rest, scrolled, and back at rest and
  confirm `#FFFBFE` → `#F3EDF7` → `#FFFBFE`

- [x] 4.3 On-device, dark: repeat and confirm `#141218` → `#211F26` → `#141218`

- [ ] 4.4 On-device with a populated archive: confirm the Recordings *tab* list steps the band the same way
  — blocked: the Verso at 192.168.1.100 is offline, so no clip lands in the archive to scroll

## Workflow follow-up

- Archive after 4.4 or with 4.4 recorded as the hardware-blocked residual; the behaviour itself is proven on
  three other scroll containers with the same wiring.
