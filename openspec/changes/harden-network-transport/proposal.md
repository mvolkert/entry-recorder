# Proposal

## Why

`network_security_config` permits global cleartext and trusts user CAs (a MITM risk). This can only be
tightened once the Python server is HTTPS-first — otherwise self-signed HTTPS on 2N devices and LAN HTTP to
the server break. These two coupled items are done together.

## What Changes

- Server: add TLS (or record an explicit LAN-only decision) — `server/.../main.py`, `docker-compose.yml`, `Dockerfile`.
- App: scope cleartext to the local subnet and drop user-CA trust — `network_security_config`.

## Capabilities

Deployment/transport hardening with no change to app-visible behaviour requirements. `skip_specs: true`.

## Impact

- Ordering: server HTTPS-first MUST land before dropping user CAs app-side (dropping them breaks 2N
  self-signed HTTPS).
- Prerequisite to also retire the `?api_key=`-in-URL exposure in media/live/player paths.
