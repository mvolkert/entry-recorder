# Proposal

## Why

HTTP/SIP passwords and the server API key are stored plaintext in Room. On a rooted or backed-up device
these credentials are exposed. This is a security hardening pass, not a behaviour change, so it opts out of
a spec delta.

## What Changes

- Encrypt device credentials and the server API key at rest via a Keystore-backed Room `TypeConverter`.
- Migrate existing plaintext rows to encrypted storage.

## Capabilities

No spec-level behaviour change (credentials are already required to never hit logs; storage-at-rest is an
implementation concern). `skip_specs: true`.

## Impact

- Code: `data/local/entity/*` (type converters), `data/local/AppDatabase.kt` (migration), DI for the key.
- Risk: a bad migration can brick existing installs; needs an over-real-data upgrade check. Related known
  limitation: embedded `rtsp://user:pass@host` URLs stay out of app logs (external Media3/ffmpeg logging is
  out of our control).
