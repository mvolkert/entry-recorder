# Proposal

## Why

`ui.theme.Theme.kt` (505 lines) is a single file holding five different design-system concerns plus the theme
entry point: the `ThemeMode` preference enum and its ordinal decoder, the accent system (`AccentRoles`,
`AccentPalette`, the dark+light preset table, `accentPresetAt`), the status palette (`StatusColor`,
`StatusPalette`, `statusColorFor`, `eventTypeColor/OnColor`), a grab bag of cross-cutting tokens and mappings
(`Spacing`, `disabledContentColor`, `recordingGlyphColor`, `monitorStatusColor`, `eventTypeIcon`), the enum-label
composables (`eventTypeLabel`, `sipModeLabel`, `streamProtocolLabel`), and finally `AppTheme` itself. Any colour
or token edit recompiles the whole UI surface and the contrast tests have to reach past the presets to find what
they assert on. No behaviour changes, so there is no spec delta.

## What Changes

- Split `Theme.kt` into focused files in the same `ui.theme` package: `ThemeMode.kt` (mode enum +
  `themeModeAt`), `Accents.kt` (`AccentRoles`, `AccentPalette`, `accentPresets`, `accentPresetAt`,
  `buildColorScheme` — the code that consumes the presets), `StatusColors.kt` (`StatusColor`, `StatusPalette`,
  `statusColorFor`, `eventTypeColor/OnColor`, `eventTypeIcon`, `monitorStatusColor`), `Tokens.kt` (`Spacing`,
  `DisabledContentAlpha` + `disabledContentColor`, `recordingGlyphColor(For)`, `onScrimColor`), `Labels.kt`
  (the `eventTypeLabel` / `sipModeLabel` / `streamProtocolLabel` composables), and `Theme.kt` keeping only
  `AppTheme` + `LocalDarkTheme`.
- No declaration is renamed, retuned, moved to another package, or re-implemented; every symbol keeps its exact
  colour values and `when` arms.
- `tools/derive_accents.py` doc/comment references to `ui/theme/Theme.kt` are repointed at `Accents.kt` so the
  paste target stays findable (the script only prints output; it does not parse the file).
- Out of scope: any palette or accent value change, the Material3 motion-scheme workaround in
  `ExpressiveMotion.kt`, dynamic-colour behaviour (`add-material-you-dynamic-color`), the app-bar scroll
  containers, and `theme-appearance` requirements of any kind.

## Capabilities

### New Capabilities

- None — pure refactor, `skip_specs: true`.

### Modified Capabilities

- None — `theme-appearance` requirements are untouched; only file layout changes.

## Impact

- `app/src/main/java/io/github/mvolkert/entryrecorder/ui/theme/` — six files replace one; no call-site edits
  because the package is unchanged.
- `AccentPaletteContrastTest` and `StatusPaletteContrastTest` keep passing unmodified (same package, same
  symbols) — they are the regression net for the split.
- `tools/derive_accents.py`: comment/docstring text only, no logic change.
- Verification: `compileDebugKotlin` + `testDebugUnitTest` + `lintDebug`; a UI smoke check that light/dark and the
  accent presets render identically, plus a dynamic-colour device check since `AppTheme` is the one file whose
  imports change.
