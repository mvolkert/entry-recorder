# Proposal

## Why

Every screen adopted a Material 3 top app bar, but none of them were told about scrolling. The bars stayed
on their resting fill while content moved underneath, so a long list slid past a title that never reacted —
the one behaviour users read as "this bar is on top of that content". On the Recordings tab the problem was
worse: the band under the bar was hand-painted with a fixed `surface`, so the bar could not have stepped
without opening a seam through the search field, the storage line and the filter rows.

## What Changes

- Each screen's scrolling root reports its offset to its own `TopAppBar` through
  `TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())` and
  `Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)`.
- One shared helper, `HeaderBandColor(state)`, resolves the band fill as `surface` at rest and
  `surfaceContainer` once content has scrolled under the bar, animated with the motion scheme's effects
  spec.
- The Recordings tab drops its hand-painted `.background(surface)`; the bar and its band are painted
  transparent so the band owns the single fill.
- Edge-to-edge inset ownership is unchanged: bars keep `contentWindowInsets = WindowInsets(0)` and the
  existing comments about which node consumes which inset.

**Out of scope:** collapsing, medium, large or centre-aligned app bars; any change to bar height, title
style or actions; the search surface's own full-screen chrome (it is a separate surface and gets no scroll
connection); bottom navigation.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `theme-appearance`: a title bar reacts to the content scrolling under it, and a bar that shares a band
  with content paints that band as one surface.

## Impact

- Code: `ui/theme/HeaderBand.kt` (new helper), `ui/live/LiveCamerasScreen.kt`,
  `ui/recordings/RecordingsScreen.kt`, `ui/recordings/RecordingsTopBar.kt`,
  `ui/settings/DeviceEditScreen.kt`, `ui/settings/SettingsComponents.kt`, `ui/settings/SettingsScreen.kt`.
- Perceived elevation changes on every screen at once, which is why this lands as its own change and
  reverts as its own change.
- Verification: `:app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug`, plus an on-device scroll
  check with pixel sampling of the bar/band fill at rest, scrolled, and back at rest, in dark and light.
  The Recordings tab's scrolled step needs a populated archive (real camera or server); the other screens
  do not.
