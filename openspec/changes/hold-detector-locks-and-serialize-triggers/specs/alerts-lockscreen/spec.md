# Spec Delta

## ADDED Requirements

### Requirement: Continuous motion analysis holds the wake and Wi-Fi locks
The system SHALL hold the CPU and Wi-Fi locks for as long as any in-app motion analyzer is running, and release
them once none is, so detection does not depend on a trigger having already been recognised by a sleeping phone.

#### Scenario: Screen-off detection with an analyzer running
- **WHEN** a device configured for in-app motion analysis is monitored and the screen turns off
- **THEN** the analyzer keeps polling at its configured interval and a subject crossing the view is recorded

#### Scenario: Last analyzer stops
- **WHEN** the final analyzer stops because its device was removed, disabled, or switched away from in-app analysis
- **THEN** the locks are released if no other hold reason (active recording, pending post-record stop, or a
  device that keeps locks held through idle) still applies

#### Scenario: Camera-event-only deployment is unchanged
- **WHEN** a monitored device has no analyzer and does not keep locks held through idle
- **THEN** idle monitoring holds no lock, as before this change

### Requirement: Wake-lock acquires are bounded and renewed
The system SHALL acquire the wake lock with a timeout rather than indefinitely, and renew that acquire while a
reason to hold the lock remains true or an armed per-trigger hold has not elapsed, so a path that fails to release
cannot pin the CPU until process death. The paired Wi-Fi lock has no timed acquire in the framework, so the system
SHALL bound it with the same renewal path rather than a platform deadline, releasing it once no reason remains.

#### Scenario: Renew while still needed
- **WHEN** an acquire deadline approaches while an analyzer is still running
- **THEN** the lock is acquired again without a gap that suspends the polling loop

#### Scenario: Abandoned hold expires on its own
- **WHEN** the holder stops renewing an acquire, for example because the service is wedged
- **THEN** the lock lapses at its timeout instead of being held until the process dies

#### Scenario: Trigger hold outlives its observable work
- **WHEN** a per-trigger hold is armed for a device whose recording this phone does not observe and the reason set
  empties before that hold elapses
- **THEN** the renewal keeps the acquire in force until the armed deadline passes, then releases both locks

### Requirement: Monitor reports battery-optimization exemption
The system SHALL log, while monitoring runs, whether the app is exempt from battery optimizations, at WARN when it
is not exempt, and SHALL NOT request the exemption or take the permission for it.

#### Scenario: App not exempt
- **WHEN** monitoring runs while the app is subject to battery optimization
- **THEN** the monitor log carries one rate-limited WARN that names the consequence for detection, not one line
  per poll

#### Scenario: App already exempt
- **WHEN** monitoring runs while the app is exempt
- **THEN** the monitor log records that at most once at info level and never warns
