# Spec Delta

## ADDED Requirements

### Requirement: A pressed recording row shows it

The system SHALL show a visible pressed indication on a recording row while the user holds it, clipped to the
row's own outline, without changing what a tap or a long press does.

#### Scenario: Hold a row

- **WHEN** the user presses and holds a recording row
- **THEN** the row's fill visibly darkens for as long as the press is held and returns when released

#### Scenario: Tap still opens

- **WHEN** the user releases without a long press
- **THEN** the clip opens exactly as before

#### Scenario: Long press still selects

- **WHEN** the user long-presses a row and then another one
- **THEN** the rows between them become selected and the pressed indication never replaces the selected
  container colour
