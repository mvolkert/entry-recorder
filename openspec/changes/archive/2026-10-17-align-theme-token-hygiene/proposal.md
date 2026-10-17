# Proposal

## Why

Four audit findings were hygiene rather than broken behaviour, and each one was a place where the next
reader had to guess whether a value was deliberate: raw dp literals scattered through layouts that already
have a `Spacing` scale; a hand-driven `Modifier.scale` on the navigation icon stacked on top of the
expressive bar's own animated indicator; contrast-tested status hues written inline at six call sites with
nothing saying "these bypass the scheme on purpose"; and a second, hand-rolled copy of the status pill for
the live REC badge.

## What Changes

- Layout gaps and container padding in the settings screens, device form, stream players and Live card move
  onto `Spacing.*`. Badge interiors stay raw and the exception is now named in the scale's own KDoc and in
  `Design.md`.
- The navigation icon's `Modifier.scale(iconScale)` animation is removed. Selection is carried by the
  expressive `ShortNavigationBar`'s animated indicator plus the label's colour; the A/B on-device check
  decides whether anything is restored.
- The fixed status hues are collected into one `StatusPalette` object whose KDoc states that they
  deliberately ignore the accent preset and dynamic colour, and every accessor reads from it.
- The Live REC badge becomes `StatusChip(compact = true)` with its `contentColor` declared once, instead of
  a second inline `Surface(...)` implementation.
- `Design.md` records the call-screen FAB sizes (64 / 68 / 72 dp) as an intentional off-scale idiom, and its
  exceptions list is corrected now that the REC badge no longer writes its own 9.sp/12.sp type.

**Out of scope:** the status hues themselves (values unchanged, only collected); the incoming-call layout;
restoring an icon scale that the A/B did not need; any behaviour change to navigation or recording.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `theme-appearance`: one spacing scale with a named exception, one documented status palette, one badge
  component for every status pill, and navigation selection carried by the component rather than a
  hand-driven scale.

## Impact

- Code: `ui/theme/Theme.kt` (`Spacing` KDoc, `StatusPalette`), `ui/adaptive/AdaptiveScaffold.kt`,
  `ui/components/StatusChip.kt`, `ui/live/LiveCamerasScreen.kt`, `ui/settings/SettingsComponents.kt`,
  `ui/settings/SettingsEngineCard.kt`, `ui/settings/DeviceFormComponents.kt`,
  `ui/components/LiveStreamPlayer.kt`, `ui/components/RtspVideoPlayer.kt`, `Design.md`.
- No dependency, database or permission change; no user-visible text change.
- Verification: `:app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug` (`StatusPaletteContrastTest`
  must stay green — it reads the palette through the public accessors), then an on-device dark + light pass
  over the Live header, the Settings hub and a submenu, the Recordings chips and the navigation bar. The
  incoming-call screen needs a live SIP call, so its sizes are documented rather than device-checked.
