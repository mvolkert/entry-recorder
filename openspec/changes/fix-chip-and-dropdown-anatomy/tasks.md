# Tasks

## 1. One glyph per event type

- [x] 1.1 Add `eventTypeIcon(eventType)` in `ui/theme/Theme.kt` beside `eventTypeLabel` / `eventTypeColor`
  (RING → phone, NOISE → speaker, MOTION → walking figure, else camera) and verify
  `:app:compileDebugKotlin` resolves every symbol from `material-icons-extended`

- [x] 1.2 Route `RecordingCardItem`'s thumbnail fallback and both filter rows through that map, and verify
  on-device that the Motion filter chip and the Live card's motion trigger show the same walking figure

## 2. Labels stop carrying emoji

- [x] 2.1 Remove the leading emoji from `recordings_filter_ring|motion|noise|all_devices` and
  `settings_mode_local|server` in `strings.xml` and verify no unit test asserted on the old strings
  (`:app:testDebugUnitTest` stays green)

- [x] 2.2 Verify on-device that each chip still shows its category glyph in the leading slot, in dark and
  light

## 3. Uniform chip anatomy

- [x] 3.1 Add `filterChipLeadingIcon(selected, glyph)` in `ui/components/FilterChipSlots.kt` — tick when
  selected, the category glyph when not, nothing when there is no category — and use it for the event chips,
  the device chips and the Engine chips; verify on-device that selecting a chip swaps the category glyph for
  the tick without moving the label text

- [x] 3.2 Confirm the Engine Local/Server chips and the device chips follow the same rule after the emoji
  removal (no double glyph) by opening Recording Mode & Destination on-device in both themes

## 4. Real exposed dropdown

- [x] 4.1 Replace the read-only field plus clickable overlay `Box` in `DeviceFormComponents.kt` with
  `ExposedDropdownMenuBox` and `Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)`,
  keeping the `DropdownMenuPopup` / `DropdownMenuGroup(shapes = MenuDefaults.groupShapes())` /
  `SelectableDropdownMenuItem` body and the motion-spec arrow rotation, and verify
  `:app:compileDebugKotlin` and `:app:lintDebug` are clean with no deprecated call

- [x] 4.2 Verify on-device: the menu opens anchored under the field with the arrow flipped, the current
  selection is marked in the list, an outside tap closes it and leaves the field value unchanged, and the
  press highlight no longer bleeds past the field's rounded corners

## 5. Nested container role

- [x] 5.1 Change the recording-row thumbnail placeholder from `surfaceVariant` to
  `surfaceContainerHighest` and the device cards from a `surfaceVariant` fill to the shared settings-card
  container colours, and verify `:app:lintDebug` plus a code read that a nested surface now steps up rather
  than across families

- [x] 5.2 Verify on-device that the Devices tab cards read as one tonal step above the screen in both themes
  (the shared settings-card colours, no `surfaceVariant` fill left on a card)

- [ ] 5.3 Sample the recording-row placeholder against the card fill on-device — blocked: the archive is
  empty because the Verso at 192.168.1.100 is offline, so no recording row renders

## 6. Gates

- [x] 6.1 Run `./gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug` green

## Workflow follow-up

- Archive after 5.3 or after accepting that 5.3 shares the populated-archive blocker recorded for
  `add-app-bar-scroll-containers` and `harden-press-and-disabled-states`.
- `README.md` lists features, not chip glyphs, so the emoji removal needs no spec-sheet edit.
