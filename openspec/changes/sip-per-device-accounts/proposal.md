# Proposal

## Why

SIP runs on one global Linphone core that `updateMonitoredDevices` reconfigures per device, so with multiple
devices the last one wins and peer-to-peer cannot coexist with PBX. It also uses the deprecated `ProxyConfig`
API, and `handleSipRing` attributes a ring by device *count* rather than identity behind a PBX.

## What Changes

- Migrate Linphone `ProxyConfig` → `Account` API (plus `createProxyConfig/edit/done/addProxyConfig/
  defaultProxyConfig/clearProxyConfig`, `setDebugMode`).
- Move to per-device cores/accounts so P2P and PBX devices coexist.
- Make ring attribution identity-based once per-device identity exists (closes the by-count heuristic).

## Capabilities

### Modified Capabilities
- `sip`: peer-to-peer and PBX can coexist across devices; ring attribution becomes identity-based.

## Impact

- Code: `sip/SipCallManager.kt`, `service/IntercomMonitorService.kt`.
- Large, device-unverifiable refactor; needs a real multi-device PBX pass. Sequenced after the SIP test gate.
