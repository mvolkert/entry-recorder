# Spec Delta

## MODIFIED Requirements

### Requirement: Foreground monitor with boot autostart
The system SHALL run a persistent foreground monitoring service and restart it after device reboot via the
boot receiver, gated on a user-controlled "Auto Boot" setting.

#### Scenario: Reboot
- **WHEN** the device finishes booting with autostart enabled
- **THEN** the monitor foreground service is started again

#### Scenario: Reboot with Auto Boot off
- **WHEN** the device finishes booting and Auto Boot is disabled
- **THEN** the monitor service is not autostarted
