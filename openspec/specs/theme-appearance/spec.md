# theme-appearance Specification

## Purpose

Control light/dark mode, accent colours and the launcher/splash icon. This capability owns the
theme-mode override, curated accent presets with enforced contrast, and the shared splash layers.

## Requirements

### Requirement: System / light / dark theme choice
The system SHALL let the user pick System, Light or Dark, applying the choice to the splash, window
background and status/navigation icon tones by overriding the device night mode.

#### Scenario: Force dark while system is light
- **WHEN** the picker is Dark and the device is in light mode
- **THEN** the app and next cold-start splash use the dark variant

### Requirement: Curated per-role accent presets with WCAG-AA contrast
The system SHALL offer OKLCh-derived accent presets per color role (primary/secondary/tertiary) for
both light and dark modes, with a unit test enforcing at least 4.5:1 contrast for every role pair.

#### Scenario: Contrast regression
- **WHEN** an accent pair drops below the contrast floor
- **THEN** the palette test fails before the change ships

### Requirement: Themed launcher icon and matching splash
The system SHALL ship an adaptive launcher icon with a monochrome layer and build the splash from the
same layers, so the icon and splash cannot drift.

#### Scenario: Themed icons enabled
- **WHEN** the launcher applies themed (monochrome) icons
- **THEN** the silhouette and the splash disc come from the same foreground layers
