# Proposal

## Why

`SipCallManager` drives the monitor core through APIs deprecated in Linphone 5.x —
`createProxyConfig`/`edit`/`done`, `addProxyConfig`, `defaultProxyConfig`, `removeProxyConfig`, the
address/register setters and `Factory.setDebugMode`. The inline `TODO(sip)` tracked this only in the
source; deferred work belongs as an OpenSpec change. Behaviour does not change today, so there is no
spec delta yet — this is backlog.

## What Changes

- Migrate the Linphone integration from the deprecated Core/ProxyConfig APIs to the Account API
  (`AccountFactory`, `AccountManager`), keeping per-device accounts, PBX registration, probe
  behaviour and the incoming-ring routing intact.
- Replace `Factory.setDebugMode` with the supported logging configuration.

## Impact

- `sip/SipCallManager.kt` (and `SipRegistrar` consumers if the probe surface has to change shape).
- Requires real-device / PBX validation before and after the migration (2N peer-to-peer and PBX
  modes, incoming ring → monitor service routing, registration probe in the device form).
