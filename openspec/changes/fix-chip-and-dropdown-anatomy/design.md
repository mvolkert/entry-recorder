# Design

## Context

See `proposal.md` for motivation. Four constraints shaped the approach:

- The pinned Material 3 Expressive artifact (`material3:1.5.0-beta01`, `androidx-main` sources) exposes
  both a stable `ExposedDropdownMenuBox` and a deprecated `ExposedDropdownMenu` composable.
- `material-icons-extended` is already a dependency, so a real glyph per event type costs nothing.
- The recording card already owns an animated selection container (`secondaryContainer`) that must keep
  working, and the filter rows already lay out identically for chips with and without a leading icon.
- Strings are shared with non-UI callers, so a label change cannot silently alter a comparison elsewhere.

## Goals / Non-Goals

**Goals:**

- One place decides what glyph an event type gets.
- Chip selection is legible without colour, in either theme.
- The dropdown is a Material component with an anchor relationship, not a hand-synced overlay.

**Non-Goals:**

- No new chip component; the existing `FilterChip`/`AssistChip` calls are corrected in place.
- No change to filter semantics, row layout, or which categories exist.

## Decisions

- **`eventTypeIcon(eventType)` lives in `ui/theme/Theme.kt` next to `eventTypeLabel` / `eventTypeColor`.**
  The three are the same "how an event type reads" question; splitting the icon into a component file is
  what produced the divergent maps in the first place. Rejected: a `when` inside `RecordingCardItem`,
  which the filter bar then had to duplicate.
- **One shared slot helper, `filterChipLeadingIcon(selected, glyph)`, instead of the same `if (selected)`
  lambda at four call sites.** The rule is the thing that kept drifting, so it lives in one file with the
  reason for the tick/glyph/none precedence in its KDoc. Rejected: keeping the tick in `trailingIcon`, which
  the Material chip spec reserves for dismiss actions on input chips.
- **`ExposedDropdownMenuBox` + `menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)`** with the
  existing `DropdownMenuPopup` / `DropdownMenuGroup(shapes = MenuDefaults.groupShapes())` /
  `SelectableDropdownMenuItem` body kept. Rejected: the deprecated `ExposedDropdownMenu` wrapper, and
  rejected the minimal fix (`Modifier.clip(fieldShape)` + `Role.DropdownList` on the old overlay), which
  leaves the anchor relationship and outside-tap dismissal hand-rolled.
- **Emoji removed from the strings rather than filtered at render time.** A label that is clean in the
  resource is clean for every consumer; a render-time strip would leave the emoji in exported settings and
  backups.
- **Placeholder fill `surfaceVariant` → `surfaceContainerHighest`.** M3 nests surfaces by stepping the
  container role up; `surfaceVariant` is a different family and does not read as "inside" the
  `surfaceContainerHigh` card.

## Risks / Trade-offs

- A one-file compile probe guards the `ExposedDropdownMenuBox` assumption; if the symbol ever fails to
  resolve on an upgraded artifact, fall back to the clipped `Box` + `Role.DropdownList` interim rather
  than reaching for a deprecated API.
- Removing emoji changes strings users recognise. The glyph moves to the chip's leading icon, so each
  control keeps a per-category mark; no unit test asserts on the old strings.
- The tick/`null` swap changes a chip's intrinsic width by the icon slot. The filter rows wrap, so the
  reflow is bounded to the row that changes.

## Verification

Gradle: `:app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug`.

On-device (Pixel_9 AVD, `emulator-5554`), dark and light: Recordings event and device chips selected and
unselected; Engine Local/Server chips; device-form dropdown opened, chosen from, and dismissed by an
outside tap. No hardware camera is required, so this change's device gate is fully executable on the AVD.
