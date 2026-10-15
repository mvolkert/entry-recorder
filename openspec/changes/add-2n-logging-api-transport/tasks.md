# Tasks

## 1. Transport

- [ ] 1.1 Implement Logging API subscribe/pull/unsubscribe loop in `TwoNIPVersoDevice` and verify the
  channel id is obtained and released on session teardown
- [ ] 1.2 Drive the subscribe filter from `requestedEventNames()` and verify a triggers-off session opens
  no subscription
- [ ] 1.3 Add expiry re-subscribe and verify a pull that lets `duration` lapse re-acquires a channel

## 2. Decode

- [ ] 2.1 Route drained log entries through `handleRaw2NEvent` and verify ring/motion/noise map to the
  existing `IntercomEvent` types
- [ ] 2.2 Remove the dead `/api/event/*` SSE and `/api/motion|noise/status` poll paths and verify
  `compileDebugKotlin` + `lintDebug` stay green

## 3. Verification

- [ ] 3.1 Run `./gradlew compileDebugKotlin testDebugUnitTest lintDebug` green
- [ ] 3.2 On the Verso: enable the Logging service for the app's API user and confirm `/api/log/subscribe?filter=KeyPressed`
  returns a `result.id` (this is the prerequisite gate)
- [ ] 3.3 Capture one real `/api/log/pull` body of a doorbell press and finalize the decode against it

## Workflow follow-up

- Archive only after tasks 3.2 and 3.3 pass on hardware; if the endpoint still refuses after enabling
  the service, revert the decision to the delete-the-HTTP-path branch instead.
