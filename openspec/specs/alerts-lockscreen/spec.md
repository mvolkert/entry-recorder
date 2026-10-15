# alerts-lockscreen Specification

## Purpose

Wake the device and show live video / an incoming-call UI on a trigger. This capability owns
screen wake + show-when-locked, the full-screen incoming-call intent, heads-up notifications, and
the always-on monitor foreground service including autostart after boot.

## Requirements

### Requirement: Wake and show live video on trigger
The system SHALL turn the screen on and show the live RTSP/snapshot video over the lock screen
(`showWhenLocked` + `turnScreenOn`) on ring, motion or noise, without requiring unlock.

#### Scenario: Doorbell while screen off
- **WHEN** a ring arrives with the screen off
- **THEN** the screen turns on and the live view / incoming-call UI is shown over the keyguard

### Requirement: High-priority heads-up notifications
The system SHALL raise heads-up notifications for motion/noise events, and open the incoming-call
activity when the notification is tapped.

#### Scenario: Motion while app backgrounded
- **WHEN** motion is detected and the app is not foreground
- **THEN** a heads-up notification appears and tapping it opens the call screen

### Requirement: Foreground monitor with boot autostart
The system SHALL run a persistent foreground monitoring service and restart it after device reboot
via the boot receiver.

#### Scenario: Reboot
- **WHEN** the device finishes booting with autostart enabled
- **THEN** the monitor foreground service is started again

### Requirement: Never log device credentials
The system SHALL not emit HTTP/SIP passwords or credential-bearing RTSP URLs to logs; event and
device logging uses an identity string that masks credentials.

#### Scenario: Event logged on every trigger
- **WHEN** an intercom event is logged
- **THEN** the line names the event class and device id/name only, with no password in logcat
