# Proposal

> **Superseded by `sip-per-device-accounts`.** The `ProxyConfig → Account` API migration and the
> `Factory.setDebugMode → LoggingService` replacement described here are implemented as part of that change
> (same `sip/SipCallManager.kt` surface, executed together with per-device accounts and identity-based ring
> attribution). Archived 2026-10-17 with no spec delta (`skip_specs`); its hardware validation belongs to
> `sip-per-device-accounts` 1.2 / 4.2 and `verify-on-device-gates` 2.1, which remain open until those gates
> pass on a Verso and a PBX.

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
