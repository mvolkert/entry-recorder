# Spec Delta

## ADDED Requirements

### Requirement: Filter chips name their category with a glyph and a tick

The system SHALL mark each recordings filter chip with the same glyph the archive uses for that category
elsewhere, and SHALL show a tick as the leading icon only while the chip is selected. Selection SHALL
therefore read the same way in the event row and the device row.

#### Scenario: Event filter unselected

- **WHEN** the ring filter is not active
- **THEN** its chip shows the phone glyph beside the plain label "Doorbell Rings"

#### Scenario: Event filter selected

- **WHEN** the ring filter is active
- **THEN** its chip shows a tick on a filled container instead of the phone glyph

#### Scenario: Glyph agreement with the list

- **WHEN** a filtered clip is rendered in the gallery
- **THEN** the glyph on its row is the same symbol the filter chip used for that event type

### Requirement: Filter labels carry no emoji

The system SHALL spell out filter and device labels as plain text, so the meaning survives a screen
reader, a font without emoji coverage, and a right-to-left locale.

#### Scenario: Screen reader reads a filter row

- **WHEN** a screen reader announces the filter chips
- **THEN** it reads the label words alone, with the category glyph announced as decoration-free content

### Requirement: Thumbnail placeholder steps up from its card

The system SHALL fill a recording row's missing-thumbnail placeholder with a container role one step
lighter than the card that contains it, so a nested surface never disappears into its parent.

#### Scenario: Clip has no thumbnail yet

- **WHEN** a recording row renders before its thumbnail resolves
- **THEN** the placeholder is visibly distinct from the card background in both light and dark themes
