# Proposal

## Why

Two state signals that Material promises were not reaching the user. A held press on a recording row
produced nothing visible: `combinedClickable` sat on the incoming modifier, behind `Card`'s opaque
container fill, so the state layer was painted and then covered. And two "this is not available right now"
tints each invented their own alpha on a variant colour role, so the app had two different disabled
intensities and neither matched the 38 % rule Material states for disabled content.

## What Changes

- The recording row keeps `Card` (for its content colours, shape and animated selection container) but moves
  the paint forward: `clip(shape)` → `background(containerColor)` → `combinedClickable(...)` on the incoming
  chain, with the `Card` itself transparent. Order is load-bearing — clip before clickable shape-clips the
  ripple, background before clickable puts the ripple above the fill.
- Long-press range selection and the existing `onClickLabel` / `onLongClickLabel` semantics are unchanged.
- One disabled-content recipe in `ui/theme/Theme.kt`: `DisabledContentAlpha = 0.38f` and
  `disabledContentColor()` returning `onSurface` at that alpha. The monitor-status DISABLED tint and the Live
  trigger "faded" tint both call it instead of hand-picking an alpha on `onSurfaceVariant`.

**Out of scope:** making a recording row a `Role.Button` (only if the TalkBack pass finds the gesture
unnamed); any change to which row states exist (selection, protection, server-row non-selectability); the
status hues themselves; slider token alphas in `JpegFramePlayer`, which are component metrics rather than
disabled content.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `recordings-archive`: a press on a recording row is visible while it is held, and the row still supports
  tap-to-open and long-press-to-select.
- `theme-appearance`: one disabled-content rule (`onSurface` at 38 %) instead of per-call-site alphas.

## Impact

- Code: `ui/recordings/RecordingCardItem.kt`, `ui/theme/Theme.kt`, `ui/live/LiveCamerasScreen.kt`.
- Verification: `:app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug`. On-device, the disabled tint
  is checkable immediately by toggling monitoring off and pixel-sampling the status dot; the row-press sample
  and the TalkBack pass over a row both need a clip in the archive, which the offline Verso cannot currently
  produce. The row's `onClickLabel` / `onLongClickLabel` semantics are untouched, so the announcement risk is
  limited to the gesture name TalkBack derives from the clickable node.
