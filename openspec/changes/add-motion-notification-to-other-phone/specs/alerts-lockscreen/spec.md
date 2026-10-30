# Spec Delta

## ADDED Requirements

### Requirement: Notify a second device on motion
The system SHALL let the user configure a remote recipient that receives a notification when the
monitoring device detects motion.

#### Scenario: Motion fires the remote alert
- **WHEN** motion is detected and a remote recipient is configured
- **THEN** the recipient is notified through the chosen transport

#### Scenario: The recipient is the other phone's alert address
- **WHEN** the user fills in the remote recipient
- **THEN** the field takes the other phone's push alert address rather than a free-text label, and an
  address that is not a usable absolute URL is reported inline before the alert is ever triggered

#### Scenario: A relay the remote end refused is not a lost server
- **WHEN** the server answers the relay call with a client or delivery error status
- **THEN** the app logs that the alert was not delivered and says nothing about the connection to the
  server being lost, and only a transport failure may declare that

### Requirement: Receive a relayed motion alert on this phone
The system SHALL let a phone opt in to receiving relayed motion alerts through an installed push
distributor, and SHALL tell the user what is missing instead of failing silently.

#### Scenario: An alert addressed to this phone is shown
- **WHEN** the distributor delivers a motion alert for this phone's address
- **THEN** the phone raises a high-priority notification naming the camera that detected the motion and
  stamped with the alert's time, without any app on that phone having to be recording

#### Scenario: Opting in is what attaches to a distributor
- **WHEN** the user turns on "receive motion alerts on this phone"
- **THEN** the app registers with the push distributor and shows the address the distributor handed back,
  and turning the switch off unregisters and drops that address

#### Scenario: No distributor installed
- **WHEN** the receiver section is opened on a phone with no push distributor app
- **THEN** the screen says no distributor is available instead of offering an address, and nothing is
  registered

#### Scenario: A distributor that needs encrypted delivery
- **WHEN** the endpoint the distributor reports belongs to a Web-Push style encrypted delivery
- **THEN** the screen states that this distributor cannot receive relayed alerts rather than showing an
  address the server could not fill

#### Scenario: A payload this app cannot recognise is ignored
- **WHEN** a delivered message is not marked decrypted, or its body is malformed, names a kind other than
  the motion alert kind, or carries no camera name
- **THEN** no notification is raised and no part of the body is guessed at

#### Scenario: The alert still arrives after a reboot
- **WHEN** the phone restarts with the receiver flag on
- **THEN** the app re-registers with the distributor it was already using, so the stored address stays
  usable

### Requirement: Keep the push alert address confidential
The system SHALL treat a motion-alert recipient address as a bearer secret: holding it is enough to make
the recipient's phone buzz.

#### Scenario: The address is never printed in full
- **WHEN** an alert is logged, a settings row is printed, or the server records a relay
- **THEN** only the address's scheme and host appear, an unparsable address is replaced by a fixed marker
  rather than echoed, and the token in its path is not written to any log

#### Scenario: The address is available where the user needs it
- **WHEN** the user opens the receiver section on the receiving phone
- **THEN** the address is shown there for copying, so it can be typed into the monitoring phone
