# storage-cleanup Specification

## Purpose

Keep on-device and export-folder storage bounded. This capability owns retention days, the internal
quota, the opt-in SAF export-folder quota, and the background cleanup pass.

## Requirements

### Requirement: Retention period in single days
The system SHALL let the user set a retention period adjustable in single days (0 = keep
indefinitely, capped at 3650) with exact entry, blocking save on unparsable or out-of-range input.

#### Scenario: Set 3 days
- **WHEN** the user enters a 3-day retention
- **THEN** the value is accepted without silently clamping and the cutoff is day-exact

### Requirement: Internal storage quota in 100 MB steps
The system SHALL move the phone-storage quota stepper in 100 MB steps, allow exact entry that is not
a multiple of the step, and only start the daily pass once usage is a full step over the limit,
deleting oldest-unprotected clips down to one step below it.

#### Scenario: One megabyte of churn
- **WHEN** usage exceeds the limit by less than one step
- **THEN** the pass does not delete

### Requirement: Bound the quota target to deletable files
The system SHALL measure the cleanup target over only what the pass is allowed to delete
(unprotected recordings, each credited with its thumbnail), so a limit dominated by protected clips
does not become "delete every unprotected clip".

#### Scenario: Protected half dominates
- **WHEN** protected clips alone exceed the quota
- **THEN** the pass is not driven to delete every unprotected file

### Requirement: Opt-in SAF export-folder quota
The system SHALL keep the export-folder quota off by default and, when on, measure and prune only
direct children whose whole name matches the app's export grammar, counting `server_rec_*` downloads
toward usage but never pruning them, and leaving subfolders and the user's own files untouched.

#### Scenario: Synced server archive present
- **WHEN** the folder holds byte-identical `REC_` files from the Python server
- **THEN** the pinned-extension whole-name match avoids pruning a synced copy that is not the app's

### Requirement: Guard external failures from breaking local cleanup
The system SHALL run the export-folder pass behind an access probe so an offline mount cannot stop
the phone's own cleanup, skip documents the provider refuses, and log the removed-vs-planned byte
shortfall as the signal a switch is on while the folder keeps growing.

#### Scenario: Nextcloud mount offline
- **WHEN** the export folder is unreachable
- **THEN** internal cleanup still runs and the card reports the folder state rather than crashing
