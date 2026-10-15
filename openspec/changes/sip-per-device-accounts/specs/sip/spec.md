# Spec Delta

## MODIFIED Requirements

### Requirement: Peer-to-peer and PBX SIP modes
The system SHALL support direct peer-to-peer SIP over local port 5060 without a PBX, and SIP via a
server/PBX (for example Fritz!Box, Asterisk, FreePBX) with registration, on per-device accounts so both
modes can be active across different devices at the same time.

#### Scenario: Direct IP dial
- **WHEN** a device is configured for peer-to-peer and the intercom dials the phone's IP
- **THEN** the system accepts the call without any registrar

#### Scenario: Mixed P2P and PBX devices
- **WHEN** one device is registered to a PBX and another uses peer-to-peer, both monitored
- **THEN** each keeps its own registration instead of the last-configured device overriding the other

## ADDED Requirements

### Requirement: Identity-based ring attribution
The system SHALL attribute an inbound ring to the specific device that produced it (per-device core/account
identity) rather than inferring it by the number of monitored devices.

#### Scenario: Behind a PBX with multiple devices
- **WHEN** an INVITE arrives via a PBX that fronts more than one intercom
- **THEN** the system resolves which device rang from SIP identity, not from a by-count heuristic
