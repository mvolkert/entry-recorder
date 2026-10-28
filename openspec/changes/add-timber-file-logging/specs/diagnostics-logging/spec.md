# Spec Delta

## Purpose

Capture the app's diagnostic log stream to a bounded, user-shareable file on the device so field problems
survive after logcat has rotated.

## ADDED Requirements

### Requirement: App-wide logging facade
The system SHALL route all diagnostic logging through a single logging facade so that enabling or disabling
file capture is one global decision rather than a per-call-site change, and SHALL preserve the existing
per-class tag on every line.

#### Scenario: Existing call sites keep their tag
- **WHEN** a component that previously logged with its class tag emits a line through the facade
- **THEN** the line still carries that tag in both the live stream and the file

### Requirement: Optional file capture
The system SHALL write the log stream to a file in the app's private storage only while the user has enabled
file logging, and SHALL keep the live (logcat) stream on regardless of that setting. Toggling the setting
takes effect without requiring an app restart.

#### Scenario: Logging off leaves no growing file
- **WHEN** file logging is disabled
- **THEN** the system does not append new lines to the on-device log file

#### Scenario: Enabling captures subsequent lines
- **WHEN** the user enables file logging and the app then logs a connection-loss line
- **THEN** that line is present in the on-device log file

### Requirement: Bounded log retention
The system SHALL bound the on-disk log by size, rolling to a new file once the current one reaches a fixed
limit and keeping only a small, fixed number of generations, independent of the recording storage quota, so
file logging can never fill the device.

#### Scenario: Log exceeds the per-file limit
- **WHEN** the active log file reaches its size cap
- **THEN** the system rolls to a fresh file and drops the oldest generation beyond the kept count

### Requirement: Shareable log export with redaction
The system SHALL let the user export the current log through the platform share mechanism, and SHALL guarantee
that credentials, SIP passwords and the server API key are never written in clear to the log (they are
redacted at the source).

#### Scenario: Share produces a readable file
- **WHEN** the user invokes the log-share action
- **THEN** the system hands the platform share sheet a log file the recipient can open as plain text

#### Scenario: Secrets stay redacted on disk
- **WHEN** a device or settings object is interpolated into a log line
- **THEN** the password/API-key fields appear redacted, not in clear, in the exported file
