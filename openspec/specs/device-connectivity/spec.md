# device-connectivity Specification

## Purpose

Configure and reach an IP intercom or camera over the local network. This capability owns the
device abstraction (`IntercomDevice`), the transport settings (HTTP/HTTPS/RTSP, ports, Digest/Basic
auth), the shared snapshot client, and the connection-quality signal the rest of the app surfaces.

## Requirements

### Requirement: Configurable device connection
The system SHALL let the user configure the local IP address, ports and credentials (HTTP, HTTPS,
RTSP, Digest/Basic auth) for each device rather than assuming fixed values.

#### Scenario: Editing connection settings
- **WHEN** the user opens the device form and changes address, port or auth mode
- **THEN** the system persists the values on the device row and uses them for subsequent requests

### Requirement: Multi-device abstraction
The system SHALL support an arbitrary number of devices through the `IntercomDevice` interface,
covering 2N IP Verso and generic RTSP/ONVIF models via a factory.

#### Scenario: Adding a second device
- **WHEN** the user registers an additional intercom or camera
- **THEN** the system creates an independent device instance and monitors it alongside existing devices

### Requirement: Snapshot fetch with retry and connection quality
The system SHALL fetch snapshots through a shared client that retries only retryable failures
(timeouts, 5xx, 429), remembers the Digest challenge per device, and reports a connection quality
of ONLINE, DEGRADED or OFFLINE from consecutive-failure edges.

#### Scenario: Endpoint starved
- **WHEN** snapshot fetches miss repeatedly (3-9 consecutive)
- **THEN** the system emits a DEGRADED connection-quality edge that the live card dot reflects

#### Scenario: Permanent failure not retried
- **WHEN** a fetch returns 401, 404 or a non-image body
- **THEN** the system does not retry it and treats it as a permanent error

### Requirement: Snapshot frame-rate ceiling
The system SHALL clamp the configured snapshot FPS to the device's achievable ceiling
(`effectiveSnapshotFps`, 6 for TWO_N_VERSO, 30 hard ceiling) and pace capture and live view by it.

#### Scenario: Over-ceiling request
- **WHEN** the user sets a snapshot FPS above the device ceiling
- **THEN** the form shows the ceiling and the recorder/live view pace by the clamped value while still
  reporting the measured achieved rate

### Requirement: Derived MJPEG endpoint for 2N Verso
The system SHALL derive the MJPEG stream URL for a `TWO_N_VERSO` device from the snapshot endpoint
with an added `fps` parameter, because the firmware serves MJPEG only from `/api/camera/snapshot`.

#### Scenario: Auto fallback to MJPEG
- **WHEN** a Verso device falls back to the MJPEG path
- **THEN** the system dials the derived `snapshotUrl&fps=N` URL rather than a nonexistent `/api/camera/mjpeg`
