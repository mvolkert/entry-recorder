# Tasks

> Superseded by `sip-per-device-accounts` — the migration (1.1/1.2/2.1/2.2/2.3) was implemented there and the
> on-device validation (3.x) is owned by that change's gates. Archived 2026-10-17; nothing here remained.

## 1. Preparation

- [x] 1.1 Inventoried the deprecated Core/ProxyConfig/Factory calls in `SipCallManager` (monitor core,
  `configureDeviceSip`, registration probe core) while executing `sip-per-device-accounts` 1.1 — every listed
  call site is gone from `app/src/main`.
- [x] 1.2 Confirmed the bundled Linphone SDK exposes the Account API by compiling against it during
  `sip-per-device-accounts` 1.1.

## 2. Migration

- [x] 2.1 ProxyConfig handling replaced by the Account API for the monitor core — `createAccountParams` →
  `setIdentityAddress`/`setServerAddress`/`setRealm`/`setRegisterEnabled` → `createAccount` → `addAccount`,
  diffed per device by `synchronizeDevices(List<Device>)` (`sip-per-device-accounts` 1.1 + 2.1).
- [x] 2.2 The one-shot registration probe runs on the same API surface; `SipProbeResult`
  (Registered/Rejected/NoAnswer/MissingFields) semantics are unchanged.
- [x] 2.3 `Factory.setDebugMode` replaced by `LoggingService` + a `LoggingServiceListener`.

## 3. Validation

Validation transferred to `sip-per-device-accounts` 1.2 / 4.2 (single-PBX registration, two-device P2P + PBX
registration and ring attribution) and `verify-on-device-gates` 2.1 (device-form registration probe reports
Registered/Rejected/NoAnswer). Those gates stay open against real hardware; this change owns none of them.
