# Tasks

## 1. API migration

- [x] 1.1 Migrate `SipCallManager` from `ProxyConfig` to the Linphone `Account` API (drop
  `createProxyConfig/edit/done/addProxyConfig/default/clearProxyConfig` and `Factory.setDebugMode`; PBX
  devices are now `createAccountParams → setIdentityAddress/setServerAddress/setRealm/setRegisterEnabled →
  createAccount → addAccount`, and logging goes through `LoggingService` + a `LoggingServiceListener`)
- [ ] 1.2 On-device: single-PBX-device registration behaves as before (Registered / Rejected / NoAnswer)

## 2. Per-device accounts

- [x] 2.1 Replace the per-device `configureDeviceSip` (which wiped the core so the last device won) with a
  single `synchronizeDevices(List<Device>)` that diffs one `Account` per PBX device by id, so P2P and PBX
  devices coexist and removing/disabling a device only retires that device's account + `AuthInfo`
- [x] 2.2 Preserve the main-thread core lifecycle + no-teardown-in-callback rules; the inbound port follows
  the peer-to-peer device on the shared core rather than being wiped on every reconfigure

## 3. Ring attribution

- [x] 3.1 Replace `handleSipRing`'s by-count heuristic (`?: devices.singleOrNull()`) with identity: the
  monitor resolves the ringing device from the peer-to-peer caller address or the dialed extension
  (`callLog.toAddress.username → device.sipUser`) and hands `onIncomingCall` a concrete `deviceId`; a ring
  that matches nothing is dropped rather than guessed

## 4. Verification

- [x] 4.1 Run `./gradlew compileDebugKotlin testDebugUnitTest lintDebug` green
- [ ] 4.2 On-device: two devices (one P2P, one PBX) keep simultaneous registrations and each ring opens the
  right call screen

## Workflow follow-up

- CI-verified code-complete; the Account-API migration, per-device accounts and identity attribution are
  implemented. Device-unverifiable from CI: archive only after the two-device registration + ring pass in
  1.2 / 4.2.
- Absorbs the `migrate-linphone-account-api` backlog change (same `ProxyConfig → Account` + `setDebugMode`
  migration on this file); that change is now marked superseded.
