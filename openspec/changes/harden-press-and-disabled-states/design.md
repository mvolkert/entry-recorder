# Design

## Context

See `proposal.md` for motivation. Constraints:

- `Card` draws its own opaque container fill inside the modifier chain it is handed, so anything the caller
  puts in front of the click modifier is painted behind that fill.
- The row's selection colour is animated (`animateColorAsState` on `secondaryContainer` /
  `surfaceContainerHigh`, with the effects spec) and must keep animating.
- Long press drives range selection, so the interaction has to stay `combinedClickable`.
- Material's disabled rule is `onSurface` at 38 %; the app had `onSurfaceVariant` at 35 % in two places.

## Goals / Non-Goals

**Goals:**

- A held press is visible on a recording row without replacing `Card`.
- Exactly one expression of "this content is unavailable" in the theme.

**Non-Goals:**

- No new row component; no change to which gestures do what.
- No change to the status hues or to the slider token alphas in the frame strip.

## Decisions

- **Paint forward, keep `Card`:** `modifier.fillMaxWidth().clip(shape).background(containerColor)`
  then `.combinedClickable(...)`, with `Card(shape = shape, colors = cardColors(containerColor =
  Color.Transparent))`. Rejected: `Card(onClick = …)`, which has no `onLongClick` slot and would drop range
  selection; rejected: swapping `Card` for a bare `Surface`/`Box`, which would lose the content colours and
  force the shape to be restated for the interior.
- **Order is part of the contract**, written as a comment at the call site: `clip` before `clickable`
  shape-clips the ripple to the card corners, `background` before `clickable` puts the state layer above the
  fill. A future edit that reorders them silently returns the invisible-press bug.
- **`DisabledContentAlpha = 0.38f` + `disabledContentColor()` in `ui/theme/Theme.kt`.** Both call sites keep a
  KDoc line naming what they tint (a status dot, a trigger glyph) so the recipe is not mistaken for a
  disabled-component helper. Rejected: keeping `onSurfaceVariant.copy(alpha = …)` per site, which is where
  the two different intensities came from.

## Risks / Trade-offs

- The row now paints its own fill, so a caller that adds `.background(...)` upstream would show through at
  the corners only where `clip` does not cover — the clip is applied in the same chain, so this is bounded to
  this composable.
- The state layer is drawn over `surfaceContainerHigh`/`secondaryContainer`; on a selected row the press is
  subtler than on a resting one. Accepted: selection already has its own colour, and the pressed overlay
  still steps the fill.
- Moving the two tints to `onSurface` at 38 % makes them slightly darker than before on light backgrounds —
  intended, and it is the value the accessibility rule is written against.

## Verification

Gradle: `:app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug`.

On-device evidence already taken for the disabled rule: monitoring toggled off on the Live screen, the
status dot pixel-sampled at `#9A959C` against the measured container `#E6E0E9`, which is `onSurface` at
38 % (predicted `#99959C`); the previous `onSurfaceVariant` at 35 % rule would have read about `#B3AEB8`.

The row-press sample needs a clip in the archive. A manual recording was attempted on the AVD: the REC badge
appeared, the offline Verso produced no frames, and the archive still read "No recordings found." / "Total
storage: 0 B", so the press cannot be sampled there yet.
