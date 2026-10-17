# Design

## Context

See `proposal.md` for motivation. Constraints:

- The pinned `material3:1.5.0-beta01` `TopAppBar` takes its fills through
  `colors = TopAppBarDefaults.topAppBarColors(containerColor, scrolledContainerColor)`; there is no
  per-bar scroll callback to hook.
- `TopAppBarState.overlappedFraction` is what the bar itself compares against its own threshold, so a band
  that reads the same state flips in the same frame as the bar.
- The app is edge-to-edge: each screen's bar keeps `contentWindowInsets = WindowInsets(0)` and the
  surrounding node owns the insets. That ownership split must not move.
- `Design.md` §8 forbids a spatial spec for colour.

## Goals / Non-Goals

**Goals:**

- One rule for the resting/scrolled pair, expressed once.
- The scroll connection attached to the node that actually scrolls.
- No second painter for the Recordings band.

**Non-Goals:**

- No collapsing or large bars; no change to bar content or height.
- No scroll behaviour on the full-screen search surface, which is its own surface rather than a band under
  the bar.

## Decisions

- **`HeaderBandColor(state: TopAppBarState)` in `ui/theme/HeaderBand.kt`.** It takes the state, not the
  behaviour, so a screen that owns several bars can pass whichever one paints its band, and the helper
  stays a pure colour read of a Material state object. Rejected: a `Modifier.headerBand()` extension, which
  would hide the colour decision inside a modifier chain and make the transparent-bar arrangement harder to
  reason about.
- **`overlappedFraction > 0.5f` → `surfaceContainer`, else `surface`, animated with
  `motionScheme.defaultEffectsSpec()`.** Those are exactly the roles Material hands a bar as
  `containerColor` / `scrolledContainerColor`, so bar and band are the same pair rather than an
  approximation of it.
- **Only the Recordings band paints through the helper.** Its bar and band share one surface, so the bar sets
  both fills transparent and the wrapping column paints `HeaderBandColor(...)`. The other four bars keep
  their own defaults and simply receive the scroll behaviour — a bar that paints itself should not also be
  told what colour to be.
- **`.background(MaterialTheme.colorScheme.surface)` deleted from the Recordings band** rather than kept and
  matched by eye; a static fill under an animated bar is the seam.

## Risks / Trade-offs

- Every screen's perceived elevation changes at once → land alone, revert is one change.
- `nestedScroll` on a node that does not scroll is silent, not an error → each site is checked by scrolling
  that screen on-device, not by reading the modifier chain.
- The Recordings *tab* list gets the connection; the copy of the rows inside the search surface deliberately
  does not, so wiring the wrong one would make search steal the band's state.

## Verification

Measured on the Pixel_9 AVD (`emulator-5554`) by pixel-sampling the bar/band fill at rest, scrolled, and
back at rest:

- Light: `#FFFBFE` (`surface`) → `#F3EDF7` (`surfaceContainer`) → `#FFFBFE`.
- Dark: `#141218` → `#211F26` → `#141218`.
- Proven on Edit Device, a Settings sub-screen, and the Settings hub (which correctly stays at `surface`
  because it does not overflow), plus the Recordings band uniform at rest with no seam.

The Recordings tab's *scrolled* step needs a populated archive, which the offline Verso cannot produce; that
residual is recorded in `tasks.md`.
