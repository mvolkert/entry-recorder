# Spec Delta

## ADDED Requirements

### Requirement: Device-type choice is an exposed dropdown menu

The system SHALL present the device form's single-choice fields as Material exposed dropdown menus: the
field names the control for assistive tech, tapping it opens a menu anchored to the field, and choosing an
option or tapping outside closes it. A decorative overlay that paints a highlight across the field's
rounded corners SHALL NOT be used in place of the menu.

#### Scenario: Open and choose

- **WHEN** the user taps the device-type field on the device form
- **THEN** a menu of the available device types opens anchored to that field
- **AND** selecting one writes it into the field and closes the menu

#### Scenario: Dismiss without choosing

- **WHEN** the user taps outside an open device-type menu
- **THEN** the menu closes and the previously selected type stays in the field

#### Scenario: Press feedback stays inside the field

- **WHEN** the user presses the device-type field
- **THEN** the pressed highlight is clipped to the field's own rounded outline

### Requirement: Mode chips share one selection anatomy

The system SHALL render every selectable chip in the settings screens with the same anatomy: a tick as the
leading icon while selected, the option's own glyph while not selected, and a label whose text alone
carries the meaning.

#### Scenario: Engine mode selected

- **WHEN** the recording engine is Local
- **THEN** the Local chip shows a tick on its filled container and the Remote Server chip shows a cloud
  glyph with its plain label

#### Scenario: No decorative glyph in labels

- **WHEN** any settings chip label is displayed
- **THEN** the label contains no emoji or other decorative character, so a screen reader reads the same
  words that are printed
