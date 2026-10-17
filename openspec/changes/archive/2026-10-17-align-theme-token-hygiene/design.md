# Design

## Context

See `proposal.md` for motivation. Constraints:

- `Spacing` exists (xs 4, sm 8, md 12, lg 16, xl 24) and `Design.md` §7 requires every non-default value to
  live in one place with a written reason.
- The pinned expressive navigation component already animates an active indicator; the app added its own
  spring scale on the icon on top of it.
- `StatusPaletteContrastTest` asserts through the public accessors (`eventTypeColor`,
  `recordingStatusColor`, `onRecordingStatusColor`, `recordingGlyphColorFor`, `statusWarnColor`), so the
  literals can move as long as those accessors keep their meaning.
- The Live card's header row is followed by an aspect-ratio video box, so any height change in that row
  reflows the card.

## Goals / Non-Goals

**Goals:**

- A reader can tell, at each value, whether it is a scale step or a declared exception.
- No duplicated status-pill implementation.
- The navigation icon stops animating the same property twice.

**Non-Goals:**

- No re-tuning of hues, no new spacing steps, no change to what the nav bar selects or how recording works.
- No change to the incoming-call layout beyond writing down why its FAB sizes are off-scale.

## Decisions

- **Sweep gaps, not geometry.** Only inter-component gaps and container padding became `Spacing.*`; the
  spinner's `size(16.dp)` / `strokeWidth(2.dp)`, the badge interiors and the trigger glyph sizes stayed raw,
  because they are component metrics. The `Spacing` KDoc now names the badge exception instead of leaving it
  as an unexplained inconsistency.
- **`6.dp → Spacing.sm` in the device form was taken, not assumed safe:** the segmented-vs-dropdown decision
  measures each label against `maxWidth / options.size - 32.dp`, which the gap change does not touch, and the
  compact window takes the dropdown path anyway. Re-checked on-device that the form still renders the
  dropdown with unellipsized labels.
- **Remove the icon scale rather than tokenise it.** `Modifier.scale` on the icon and the component's own
  indicator animate the same idea; the A/B against the expressive indicator decided it, and the KDoc at
  `AdaptiveIcon` records why there is no scale. Restoring one is only warranted if a future indicator change
  makes the selected tab read flat.
- **`StatusPalette` is a container, not a redesign.** The literals moved unchanged; the object's KDoc carries
  the "deliberately off-scheme" statement that `Design.md` §7 requires, and the accessors stay the public
  surface so the contrast test keeps guarding them.
- **`StatusChip(compact = true)` instead of a second inline `Surface`.** The compact setting changes only
  padding, gap, dot size and the label role (`labelSmallEmphasized`), so the two pills cannot drift in shape
  or colour handling. The previous hand-written capsule used a 9.sp/12.sp literal; the scale's smallest
  emphasised role replaced it, and the fit was re-measured rather than trusted.

## Risks / Trade-offs

- A spacing step that rounds a gap up can reflow a two-line control → the settings screens, device form and
  Live card were each re-rendered on-device in both themes and compared against their pre-change bounds.
- `labelSmallEmphasized` (11.sp) is larger than the 9.sp it replaces → measured on-device: the REC badge
  occupies the same 42 px line as the monitoring pill beside it and the next header line stays at y = 505, so
  the row does not grow.
- Collecting the palette is a large textual diff in `Theme.kt` with no behaviour change → guarded by the
  existing contrast test staying green.

## Verification

Gradle: `:app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug`, with `StatusPaletteContrastTest`
explicitly in the run.

On-device, dark and light: Live header (badge fit, trigger glyph spacing), Settings hub and a submenu, the
engine card, the device form, the Recordings chips, and the navigation bar across destination changes. The
incoming-call screen needs a live SIP call, so its sizes are code-reviewed and documented only.
