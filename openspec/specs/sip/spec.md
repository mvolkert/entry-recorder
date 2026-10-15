# sip Specification

## Purpose

Intercom two-way audio over SIP. This capability owns peer-to-peer and PBX calling, registration
lifecycle, the connection-test probe, and accepting a call from the lock screen.

## Requirements

### Requirement: Peer-to-peer and PBX SIP modes
The system SHALL support direct peer-to-peer SIP over local port 5060 without a PBX, and SIP via a
server/PBX (for example Fritz!Box, Asterisk, FreePBX) with registration.

#### Scenario: Direct IP dial
- **WHEN** a device is configured for peer-to-peer and the intercom dials the phone's IP
- **THEN** the system accepts the call without any registrar

### Requirement: Answer on the alarm/lock screen
The system SHALL let the user accept a SIP call directly from the alarm/lock-screen view, with
microphone mute and speaker toggle during the call.

#### Scenario: Ring while locked
- **WHEN** an incoming call shows over the lock screen
- **THEN** the user can answer and control mute/speaker without unlocking first

### Requirement: Withdraw superseded registrations
The system SHALL unregister and clear auth for a retired account before reconfiguring the live core,
so editing/saving a device does not stack registrations or leave a stale account refreshing.

#### Scenario: Password changed and saved
- **WHEN** the user edits a device's SIP credentials and saves
- **THEN** the old registration is withdrawn and exactly one new registration remains

### Requirement: SIP connection test without disturbing the live core
The system SHALL provide a SIP registration test that builds a separate core on a rotating local
port (never the live 5060), returns a truthful result (Registered/Rejected/NoAnswer/MissingFields),
and tears down without reconfiguring the running monitor.

#### Scenario: Wrong SIP password
- **WHEN** the user tests with a deliberately wrong password
- **THEN** the form shows a Rejected result rather than a logcat grep

### Requirement: Core lifecycle on the main thread
The system SHALL build/start/stop each Linphone core on the main thread and never run teardown
inside a liblinphone registration callback, to avoid native crashes.

#### Scenario: Test repeated rapidly
- **WHEN** the user taps the test button multiple times in a row
- **THEN** no `Fatal signal 11` occurs and each probe core is released
