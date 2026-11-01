# Spec Delta

## ADDED Requirements

### Requirement: Lazy SIP engine lifecycle
The system SHALL start the SIP engine only when a monitored device actually uses SIP (peer-to-peer
or PBX) and SHALL release it again once no monitored device uses SIP, rather than initializing it
unconditionally when the monitor service starts. An in-flight call SHALL pin the engine, and
releasing it SHALL preserve the incoming-call wiring so a later re-start is complete.

#### Scenario: HTTP-only deployment
- **WHEN** the monitor service runs with only devices whose SIP mode is disabled
- **THEN** the native SIP stack is never started and no SIP memory is mapped

#### Scenario: First SIP-configured device appears
- **WHEN** a monitored device with SIP enabled (peer-to-peer or PBX) is added or re-enabled
- **THEN** the engine starts on demand and that device registers and can ring without a service restart

#### Scenario: Last SIP device disabled mid-call
- **WHEN** the only SIP-configured device is disabled while a call is active
- **THEN** the engine keeps running until the call ends and is released afterwards

#### Scenario: SIP device disabled and re-added
- **WHEN** the last SIP-configured device is disabled and later re-enabled
- **THEN** the engine restarts and an incoming call on the re-added device still reaches the doorbell path
