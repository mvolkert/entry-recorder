# Proposal

## Why

The audit of the Material 3 Expressive migration found the chip and dropdown family was the loudest
visible break: filter chips signalled selection with a fill only (while the device row beneath them used
a tick), category meaning was carried by emoji pasted into strings, and the device-form single-choice
control was a read-only text field with a transparent clickable overlay pretending to be a menu — an
unclipped rectangular state layer bleeding past the field's rounded corners and an unnamed control for
assistive tech.

## What Changes

- One event→icon map (`eventTypeIcon`) shared by the recording row's thumbnail fallback and every filter
  chip, so a category reads with the same glyph in the list and in the filter that produced it.
- Emoji removed from the filter and engine-mode labels; the leading icon slot carries the category
  instead.
- Uniform chip anatomy behind one shared slot helper, `filterChipLeadingIcon(selected, glyph)`: the tick
  while selected, the category glyph while not, applied identically to the event chips, the device chips and
  the Engine Local/Server chips.
- The device-form single-choice field becomes a real `ExposedDropdownMenuBox` anchored with
  `menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)`; the overlay `Box` is deleted.
- The recording-card thumbnail placeholder steps up to `surfaceContainerHighest`, since it nests inside a
  `surfaceContainerHigh` card, and the device cards stop filling `surfaceVariant` in favour of the shared
  settings-card container colours.

**Out of scope:** the Live REC capsule (folded into `StatusChip` by `align-theme-token-hygiene`), any
layout change to the filter rows, collapsing/large app bars, and the app-bar scroll behaviour (its own
change).

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `settings-ui`: the device form's single-choice control and the engine chips must use Material's
  dropdown and chip anatomy rather than a hand-built equivalent.
- `recordings-archive`: filter chips carry the category glyph and a selection tick; labels are plain text.

## Impact

- Code: `ui/theme/Theme.kt` (icon map), `ui/components/FilterChipSlots.kt` (new shared chip slot),
  `ui/recordings/RecordingCardItem.kt`, `ui/recordings/RecordingsFilterBar.kt`,
  `ui/settings/SettingsEngineCard.kt`, `ui/settings/SettingsComponents.kt` (the shared settings-card
  container colours), `ui/settings/DeviceCard.kt`, `ui/settings/DeviceFormComponents.kt`.
- Strings: `recordings_filter_ring|motion|noise|all_devices`, `settings_mode_local|server` lose their
  leading emoji — a user-visible label change; `README.md` describes features, not glyphs, so it is
  unaffected.
- Dependencies: none added; `material-icons-extended` is already a dependency.
- Verification: `:app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug`, plus an on-device pass on
  the Pixel_9 AVD in dark and light over the Recordings chips, the Engine Local/Server chips and the
  device-form dropdown opened and closed. No unit test asserts on the emoji strings, so the label change
  is test-neutral.
