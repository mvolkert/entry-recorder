# server-backend Specification

## Purpose

Central recording on a separate Python server with a web UI, as an alternative to on-phone capture.
This capability owns the server REST API, mandatory authentication, the web dashboard, device sync,
and the server-relayed live view.

## Requirements

### Requirement: Mandatory API authentication
The system SHALL require an API key (`X-API-Key` header, or `api_key` query parameter for media/live
endpoints) on every server endpoint, and on first start with no configured key generate one, persist
it and log it.

#### Scenario: Fresh server without a key
- **WHEN** the server boots with no `API_KEY` set
- **THEN** it generates a key, stores it, prints it, and rejects unauthenticated calls

### Requirement: Web dashboard
The system SHALL serve a browser dashboard at the server root showing live recording status, a video
gallery with an HTML5 player, download, retention cleanup and manual record triggers.

#### Scenario: Play a recording in the browser
- **WHEN** the user opens a gallery card in the web UI
- **THEN** the clip streams in the modal player (or a documented playback fallback warning shows)

### Requirement: App-to-server device sync
The system SHALL register each app device on the server and store the returned id as
`serverDeviceId`, using it only at the HTTP boundary while internal keys stay the local Room id, and
send recording starts without embedding device credentials.

#### Scenario: Start a server recording for a registered device
- **WHEN** the app requests a start for a synced device
- **THEN** it sends only the server device id and the server resolves credentials from its stored row

### Requirement: Server-relayed live view
In `PYTHON_SERVER` mode the app SHALL pull the live MJPEG feed from the server for a registered
device using an `X-API-Key` header (not a key in the URL), falling back to the direct-to-device path
on any stream failure.

#### Scenario: Server feed drops
- **WHEN** the server relay fails mid-session
- **THEN** the live card reverts to the direct device stream rather than going blank

### Requirement: Server recording id and status lifecycle
The server SHALL pre-insert a recording row in `recording` status, return its id from start, flip it
to `completed` on finalize, hide in-progress rows from list/stats/gallery while still serving them by
id for reconciliation, and delete the row when a capture produces no data.

#### Scenario: Busy device asked to start again
- **WHEN** a second start arrives for a device already recording
- **THEN** the server returns `already_recording` with the in-flight job's id
