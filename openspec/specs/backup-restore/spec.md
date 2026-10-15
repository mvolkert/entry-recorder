# backup-restore Specification

## Purpose

Export and restore app settings and recordings. This capability owns the backup round-trip and the
normalization applied when a restored file is incomplete or out of range.

## Requirements

### Requirement: Settings and recordings backup round-trip
The system SHALL export app settings (and recordings database) and restore them so chosen accents,
device rows and quotas come back.

#### Scenario: Export then restore
- **WHEN** the user exports settings and later restores the file
- **THEN** role indices and device configuration are reapplied

### Requirement: Normalize restored values
The system SHALL repair a restored setting a backup file spells out invalidly (for example a quota of
`0`) rather than accepting it verbatim.

#### Scenario: Zero quota in a backup
- **WHEN** a restored backup carries a quota of 0
- **THEN** restore normalizes it to a usable value

### Requirement: Re-register devices against the current server on restore
The system SHALL null `serverDeviceId` on restore so devices re-register against whatever server is
configured now, and pre-select the stored export folder in the picker when its grant must be re-granted.

#### Scenario: Restore onto a different server
- **WHEN** a backup is restored on a deployment pointing at another server
- **THEN** devices re-register rather than carrying stale server ids
