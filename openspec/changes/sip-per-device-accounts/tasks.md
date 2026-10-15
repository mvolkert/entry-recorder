# Tasks

## 1. API migration

- [ ] 1.1 Migrate `SipCallManager` from `ProxyConfig` to the Linphone `Account` API (and drop the
  deprecated create/edit/done/addProxyConfig/default/clear + `setDebugMode` usages) and verify registration
  behaves as before on the single-device path

## 2. Per-device cores/accounts

- [ ] 2.1 Give each monitored device its own core/account and verify a P2P device and a PBX device keep
  simultaneous registrations (last-configured no longer wins)
- [ ] 2.2 Preserve the main-thread core lifecycle + no-teardown-in-callback rules from the existing spec and
  verify repeated Test taps produce no `Fatal signal 11`

## 3. Ring attribution

- [ ] 3.1 Replace `handleSipRing`'s by-count heuristic with per-device identity and verify the correct device
  is attributed behind a PBX

## 4. Verification

- [ ] 4.1 Run `./gradlew compileDebugKotlin testDebugUnitTest lintDebug` green
- [ ] 4.2 On-device: two devices (one P2P, one PBX) ring independently and each opens the right call screen

## Workflow follow-up

- Device-unverifiable from CI; archive only after the two-device ring pass in 4.2.
