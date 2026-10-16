# Tasks

## 1. Decide the transport

- [x] 1.1 Resolve the delivery mechanism with the owner and record it before coding — decided 2026-10-16:
  Python-server relay primary, UnifiedPush then FCM as push fallbacks behind a `MotionAlertTransport` seam.
  UnifiedPush/FCM are Ask-First dependencies.

## 2. Implement (server relay first — no new dependency)

- [ ] 2.1 Add a remote-recipient setting and verify it persists
- [ ] 2.2 Introduce the `MotionAlertTransport` seam and a server-relay implementation; emit the motion alert
  through it and verify a second phone receives it (needs the server-side relay endpoint)
- [ ] 2.3 (Ask-First) Add the UnifiedPush backend, then FCM fallback, only after explicit dependency approval

## 3. Verification

- [ ] 3.1 Run `./gradlew compileDebugKotlin lintDebug` green
- [ ] 3.2 On-device with two phones: trigger motion and confirm the remote notification arrives

## Workflow follow-up

- Do not archive until task 1.1 has a recorded decision and 3.2 passes.
