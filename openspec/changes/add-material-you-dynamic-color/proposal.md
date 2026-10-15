# Proposal

## Why

Material You dynamic color is deferred by decision, not forgotten. It was kept out of the light-role work
because dynamic color replaces the WHOLE scheme, so the curated OKLCh presets and `AccentPaletteContrastTest`
become the opt-out path, and the UI needs an explanatory hint.

## What Changes

- Add an opt-in persisted flag behind which `AppTheme` applies `dynamicLight/DarkColorScheme`.
- When on, the curated accent presets become the opt-out; the Appearance UI explains that the system palette
  overrides accents.

## Capabilities

### Modified Capabilities
- `theme-appearance`: add an opt-in dynamic-color choice alongside the curated accent presets.

## Impact

- Code: `ui/theme/Theme.kt`, `data/local/entity/AppSettingsEntity.kt` (+ Room migration),
  `ui/settings/SettingsAppearanceCard.kt`.
- Deferred by decision — pick up when the opt-in UX is worth building.
