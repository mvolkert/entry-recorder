# Tasks

`theme-appearance` requirements that must stay satisfied verbatim: *System / light / dark theme choice*, *Curated
per-role accent presets with WCAG-AA contrast*, *Layout spacing comes from one scale*, *Status colours are
declared once and stay off-scheme*.

## 1. Move the leaf concerns

- [ ] 1.1 Create `ui/theme/ThemeMode.kt` with `ThemeMode` + `themeModeAt` (and its KDoc about the ordinal-stored
      preference and MainActivity's night-mode pinning); verify: `.\gradlew.bat compileDebugKotlin` passes.
- [ ] 1.2 Create `ui/theme/Accents.kt` with `AccentRoles`, `AccentPalette`, the `accentPresets` table **with its
      do-not-hand-edit / re-run-the-script comment block**, `accentPresetAt`, and `buildColorScheme`; verify:
      `.\gradlew.bat testDebugUnitTest` passes `AccentPaletteContrastTest` unmodified and the diff shows moved
      literals unchanged (check with `git diff --color-moved`).
- [ ] 1.3 Create `ui/theme/StatusColors.kt` with `StatusColor`, `StatusPalette`, `statusColorFor`,
      `eventTypeColor`, `eventTypeOnColor`, `eventTypeIcon`, `monitorStatusColor`; verify:
      `StatusPaletteContrastTest` passes unmodified.

## 2. Move tokens and labels

- [ ] 2.1 Create `ui/theme/Tokens.kt` with `Spacing` (keeping the badge-exception note), `DisabledContentAlpha`,
      `disabledContentColor`, `recordingGlyphColorFor` / `recordingGlyphColor`, and `onScrimColor`; verify:
      `compileDebugKotlin` passes and `Spacing` still has exactly the xs/sm/md/lg/xl steps.
- [ ] 2.2 Create `ui/theme/Labels.kt` with `eventTypeLabel`, `sipModeLabel`, `streamProtocolLabel`, keeping the
      note that the resource-id mapping lives in the data layer; verify: `compileDebugKotlin` passes.

## 3. Slim the entry point

- [ ] 3.1 Reduce `Theme.kt` to `AppTheme` + `LocalDarkTheme` (and the imports they need); verify:
      `compileDebugKotlin`, `testDebugUnitTest` and `.\gradlew.bat lintDebug` all pass, `Theme.kt` is under ~90
      lines, and no call site outside `ui/theme/` changed (package is unchanged).

## 4. Keep the generator's pointers honest

- [ ] 4.1 Update `tools/derive_accents.py`'s docstring and the "presets (dark, from Theme.kt)" comment plus the
      "paste into Theme.kt" output line to name `ui/theme/Accents.kt`; verify: `python tools/derive_accents.py`
      still runs and prints the same preset block as before (no logic edit).

## 5. Visual acceptance gate (blocks archive)

- [ ] 5.1 On a phone: cycle the three theme modes and all accent presets for primary/secondary/tertiary, checking
      the recording badges, filter chips, nav indicator, and disabled controls render identically to a pre-split
      build (screenshot-compare one screen per preset if unsure).
- [ ] 5.2 Enable dynamic colour on an API 31+ device and confirm the bypass path still themes the whole app;
      confirm status/navigation bar icon contrast follows the resolved scheme edge-to-edge.
