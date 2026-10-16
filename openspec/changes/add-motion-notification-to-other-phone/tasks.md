# Tasks

## 1. Decide the transport

- [x] 1.1 Resolve the delivery mechanism with the owner and record it before coding — decided 2026-10-16:
  Python-server relay primary, UnifiedPush then FCM as push fallbacks behind a `MotionAlertTransport` seam.
  UnifiedPush/FCM are Ask-First dependencies.

## 2. Implement (server relay first — no new dependency)

- [x] 2.1 Remote-recipient setting added and verified to persist: `motionAlertEnabled` + `motionAlertRecipient`
  on `AppSettings`/`AppSettingsEntity` (Room v22, real `Migration(21, 22)`, exported `22.json`), mapped in
  `DomainMappers`, editable in `SettingsEngineCard` (switch + recipient field), pinned by a
  `SettingsViewModelTest` that asserts both land on the Room row
- [ ] 2.2 Introduce the `MotionAlertTransport` seam and a server-relay implementation; emit the motion alert
  through it and verify a second phone receives it (needs the server-side relay endpoint)
  Code delivered (transport seam + `ServerRelayMotionAlertTransport`, `ServerClient.relayMotionAlert` /
  `POST /api/alerts/relay`, `IntercomMonitorService.relayMotionAlertToRemote` emitted on MOTION, device-free
  `ServerRelayMotionAlertTransportTest`). Remaining clause — "verify a second phone receives it" — is the
  3.2 two-phone device gate, so this stays open until that passes.
- [ ] 2.3 (Ask-First) Add the UnifiedPush backend, then FCM fallback, only after explicit dependency approval

## 3. Verification

- [x] 3.1 Run `./gradlew compileDebugKotlin lintDebug` green — both BUILD SUCCESSFUL (2026-10-17), plus
  `testDebugUnitTest` for the new device-free transport test
- [ ] 3.2 On-device with two phones: trigger motion and confirm the remote notification arrives

## Workflow follow-up

- Do not archive until task 1.1 has a recorded decision and 3.2 passes.
