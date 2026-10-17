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

### Requirement: Layout spacing comes from one scale

The system SHALL space the gap between components and the padding inside a container from one named spacing
scale, so a screen's rhythm is readable as a set of steps rather than as individual measurements. A
component's own interior geometry is the declared exception and keeps its own values.

#### Scenario: Settings list rhythm

- **WHEN** the settings hub, a settings sub-screen and the device form are displayed
- **THEN** their row gaps and card padding use the same steps in the same relationship, in both themes

#### Scenario: A badge's interior

- **WHEN** a status pill lays out its dot, its gap and its label padding
- **THEN** those measurements stay the pill's own, because rounding them to a scale step would make the pill
  taller than the text line it has to sit beside

### Requirement: Navigation selection is carried by the bar

The system SHALL mark the selected destination with the navigation component's own animated indicator and
label emphasis, without adding a separate hand-driven enlargement of the icon.

#### Scenario: Switch destination

- **WHEN** the user moves from Live View to Recordings
- **THEN** the indicator animates to the new destination and the previous one returns to its unselected
  colour

#### Scenario: Animations disabled

- **WHEN** the system animation setting is off
- **THEN** the selected destination is still distinguishable by its indicator and label, not by motion alone

### Requirement: Status colours are declared once and stay off-scheme

The system SHALL collect the fixed trigger, recording and monitor hues in one documented place that states
they deliberately do not follow the accent preset or dynamic colour, and SHALL keep their contrast enforced
by a test.

#### Scenario: Accent changed

- **WHEN** the user picks a different accent preset or enables dynamic colour
- **THEN** the event and status hues are unchanged, because they carry meaning rather than theme

#### Scenario: A hue is re-tuned

- **WHEN** a status colour loses its contrast against its own container or against the video scrim
- **THEN** the palette test fails before the change ships

### Requirement: One status badge serves every pill

The system SHALL render every small status pill from the same component, with a tighter setting for a pill
that has to share a line with existing text, and shall declare the pill's container and content colour
together.

#### Scenario: A recording starts on the Live screen

- **WHEN** the REC badge appears beside the monitoring pill
- **THEN** the header row keeps its height and nothing below it reflows

#### Scenario: Two pills on one screen

- **WHEN** the monitoring pill and the recording badge are shown together
- **THEN** they share shape, dot and label roles, differing only in the tightness setting
