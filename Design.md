# Design: Material 3 Expressive Overhaul

## Summary

EntryRecorder now runs on the real Material 3 Expressive theme, not a hand-ported stand-in. The
`AppMotionScheme` seam existed only because `MaterialExpressiveTheme` and `MotionScheme` were
`internal` in the resolved `material3:1.4.0` (compiler-proven, see below). With an approved
dependency bump to `1.5.0-beta01`, those APIs became callable, so the seam was deleted and the app
rooted on `MaterialExpressiveTheme(colorScheme, motionScheme = MotionScheme.expressive())`. Expressive
motion, shapes, typography and component styling now come from the platform, applied app-wide through
the single theme root.

## Availability — proven with a compile probe, not docs

The reference site lists `MaterialExpressiveTheme` as public `@ExperimentalMaterial3Api`; that page is
generated against newest-main and was wrong for the pinned artifact. A throwaway probe file compiled
against the resolved version is the only authority:

- `material3:1.4.0` (the `2026.09.00` BOM ceiling):
  `Cannot access 'interface MotionScheme': it is internal in file` and
  `Cannot access 'fun MaterialExpressiveTheme(...)': it is internal in file`. Root cause of the seam.
- `material3:1.5.0-beta01`: the same probe compiles clean behind one `@OptIn(ExperimentalMaterial3Api)`,
  and the compiler warns `This extension is shadowed by a member: 'val motionScheme: MotionScheme'` —
  confirming the platform member had already superseded the app extension, exactly the "migration day"
  the seam's own KDoc forecast.

## What changed

- **Dependency (`gradle/libs.versions.toml`).** `androidx.compose.material3:material3` now pins
  `1.5.0-beta01` via a `material3Expressive` version ref, overriding the BOM's `1.4.0`. Window-size-class
  and the rest of Compose stay on the `2026.09.00` BOM.
- **Theme root (`ui/theme/Theme.kt`).** `AppTheme` is `@OptIn(ExperimentalMaterial3Api)` and wraps content
  in `MaterialExpressiveTheme(colorScheme, motionScheme = MotionScheme.expressive())`. `LocalDarkTheme`,
  the dynamic-color bypass, the edge-to-edge `SideEffect` and the curated accent derivation are unchanged.
- **Seam removed.** `ui/theme/AppMotionScheme.kt` and its `AppMotionSchemeTest` are deleted; the
  `LocalAppMotionScheme` provider is gone. The five motion call sites (`Navigation`, `AdaptiveScaffold`,
  `VideoPlayerModal`, `RecordingCardItem`, `SettingsComponents`) now read the platform
  `MaterialTheme.motionScheme` member — same method names, so only the now-obsolete app import was dropped.
- **Shape/token cleanup (`ui/components/VideoPlayerModal.kt`).** The scrim close-button fill moved from the
  literal `RoundedCornerShape(50)` to the `CircleShape` idiom already used app-wide, and its `16.dp` inset
  to `Spacing.lg`. All other containers already consumed `MaterialTheme.shapes.*` roles, so they inherit the
  expressive shape scheme with no edit.

## What the expressive root delivers automatically

Because `MaterialExpressiveTheme` supplies the expressive defaults for every layer beneath it, these
upgrade with no per-widget code: bouncy spring motion on selection/state changes; larger rounded shapes on
`Card`, `FAB`, `Dialog`, `Surface`; the icon-morph selection animation on `FilterChip` (recordings filter);
restyled `Switch`, `Slider`, `Button`, `NavigationBar`/`NavigationRail` items; and expressive typography
roles that reflow with window size.

## Scope held back (needs a decision)

The theme flip is the behavior-neutral-by-design core. Deliberately *not* done, because each is a bespoke
visual change that should be approved per the flag-the-trade-off rule:

- Hero **layout transforms** (list-card → playback modal, nav-icon morph) replacing the current
  enter/exit spring transitions.
- **Morphable** icon toggles on the call controls and record button.
- Adopting `1.5.0-beta01`-only experimental widgets beyond what the root already restyles.

## Verification

- `gradlew.bat :app:compileDebugKotlin` — green; the seam shadow warning is gone.
- `gradlew.bat :app:testDebugUnitTest` — green (contrast and identity suites unaffected).
- `gradlew.bat :app:lintDebug` — green, no new findings.
- Not verified here: on-device visual pass across dark/light and the eight accents — run before merge.

## Risks

- **Beta dependency.** `1.5.0-beta01` can change before stable; pin it and re-check the release notes on
  every bump. This is the accepted cost of reaching expressive components ahead of a stable release.
- **Motion values shifted.** The platform `MotionScheme.expressive()` springs differ slightly from the
  retired hand-ported constants, so animation feel changes by design. If a specific transition regressed,
  tune at that call site rather than reintroducing the seam.
