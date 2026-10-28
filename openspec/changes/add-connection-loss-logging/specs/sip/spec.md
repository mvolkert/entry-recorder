# Spec Delta

## ADDED Requirements

### Requirement: Log SIP registration loss and recovery
The system SHALL emit a diagnostic log when a live SIP account registration is lost (registrar drops it,
the PBX becomes unreachable, or authentication fails after a period of being registered) and when a lost
registration becomes registered again, naming the device/account. This is separate from the user-initiated
connection test, whose result stays on the form.

#### Scenario: Registrar drops a live account
- **WHEN** a device's SIP registration transitions from Registered to Unregistered/Failed while monitoring
- **THEN** the system logs a WARN naming the device and the registration state

#### Scenario: Registration restored
- **WHEN** a device whose registration had been lost reports Registered again
- **THEN** the system logs an INFO recovery line naming that device
