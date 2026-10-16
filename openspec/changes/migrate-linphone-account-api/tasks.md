# Tasks

Backlog — blocked on real-device/PBX validation capacity. No implementation started.

## 1. Preparation

- [ ] 1.1 Inventory every deprecated Core/ProxyConfig/Factory call in `SipCallManager` (monitor core, `configureDeviceSip`, registration probe core).
- [ ] 1.2 Confirm the bundled Linphone SDK version already exposes the Account API (compile probe before migrating).

## 2. Migration

- [ ] 2.1 Replace ProxyConfig handling with `AccountFactory`/`AccountManager` for the monitor core (per-device accounts, PBX registration).
- [ ] 2.2 Port the one-shot registration probe to the same API surface, preserving `SipProbeResult` semantics.
- [ ] 2.3 Replace `Factory.setDebugMode` with the supported logging config (debug builds only).

## 3. Validation

- [ ] 3.1 On-device: 2N peer-to-peer ring/answer and audio routing unchanged.
- [ ] 3.2 On-device: PBX registration and incoming ring → monitor service debounce path unchanged.
- [ ] 3.3 Device-form registration probe reports Registered/Rejected/NoAnswer as before.
