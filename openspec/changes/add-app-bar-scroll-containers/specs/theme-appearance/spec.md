# Spec Delta

## ADDED Requirements

### Requirement: Title bar responds to scrolled content

The system SHALL change a screen's title bar container between its resting surface and its scrolled
surface as content moves under it, and SHALL return it to the resting surface when the content is scrolled
back to the top. A screen whose content cannot overflow its title bar SHALL stay on the resting surface.

#### Scenario: Scroll a long list

- **WHEN** the user scrolls content up past the title bar
- **THEN** the bar's fill steps to the scrolled container surface and stays readable against the content
  sliding under it

#### Scenario: Scroll back to the top

- **WHEN** the user returns the list to its first item
- **THEN** the bar's fill returns to the resting surface

#### Scenario: Screen that does not overflow

- **WHEN** a settings sub-screen's content is shorter than the viewport
- **THEN** the bar keeps its resting surface, because nothing has passed under it

### Requirement: A shared header band moves as one surface

The system SHALL paint a title bar and the content band it sits in from one resolved fill, so the pair
steps together and no seam appears between the bar and the strip below it.

#### Scenario: Recordings band at rest

- **WHEN** the Recordings tab is at the top of its list
- **THEN** the bar, search field row, storage line and filter rows read as one uninterrupted surface

#### Scenario: Recordings band scrolled

- **WHEN** the gallery is scrolled under the bar
- **THEN** the whole band steps to the scrolled surface at once, with no boundary visible between the bar
  and the content below it
