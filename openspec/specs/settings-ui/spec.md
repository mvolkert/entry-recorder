# settings-ui Specification

## Purpose

Configure devices, storage, server and appearance. This capability owns the settings hub with
sub-menus, the full-screen device form, connection tests, and adaptive layout on narrow screens.

## Requirements

### Requirement: Settings hub with sub-menus
The system SHALL organize settings into sub-screens rather than one flat page.

#### Scenario: Open storage settings
- **WHEN** the user navigates to the storage sub-screen
- **THEN** retention and quota controls render there

### Requirement: Full-screen device editor with connection tests
The system SHALL edit a device on a full-sized screen (not a small dialog) and offer both HTTP "Test
Connection" and SIP "Test SIP Registration", rendering each result as a line on the form.

#### Scenario: Wrong password
- **WHEN** a test fails on credentials
- **THEN** the form shows the rejection inline instead of requiring a logcat grep

### Requirement: Optional password reveal
The system SHALL let the user reveal masked password fields transiently (not persisted) using a
password keyboard type.

#### Scenario: Reveal then hide
- **WHEN** the user taps the eye toggle on a password field
- **THEN** the field shows plaintext until toggled off and the state is not saved

### Requirement: Adaptive storage screen on narrow widths
The system SHALL drop a stepper onto its own right-aligned line, stack folder actions full-width, and
ellipsize long values/labels on compact windows so controls never push labels off-screen.

#### Scenario: Compact window
- **WHEN** the storage screen renders in a compact window size class
- **THEN** each control keeps its caption readable
