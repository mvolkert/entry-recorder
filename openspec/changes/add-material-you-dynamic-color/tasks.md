# Tasks

## 1. Persist the opt-in

- [x] 1.1 Add a dynamic-color flag to `AppSettingsEntity` with a Room migration and verify restore/defaults
  keep it off by default

## 2. Apply

- [x] 2.1 In `AppTheme`, apply `dynamicLight/DarkColorScheme` when the flag is on and verify the curated
  presets still apply when off

## 3. UI

- [x] 3.1 Add the Appearance toggle + explanatory hint that accents are overridden and verify it reflects and
  persists the choice

## 4. Verification

- [x] 4.1 Run `./gradlew compileDebugKotlin testDebugUnitTest lintDebug` green (AccentPaletteContrastTest
  stays green for the opt-out path)
- [ ] 4.2 On-device with a Material You ROM: enable dynamic color and confirm the scheme follows the system
  palette and remains readable

## Workflow follow-up

- Deferred by decision; archive after 4.2.
