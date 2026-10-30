# Tasks

## 1. Decide the transport

- [x] 1.1 Resolve the delivery mechanism with the owner and record it before coding — decided 2026-10-16:
  Python-server relay primary, UnifiedPush then FCM as push fallbacks behind a `MotionAlertTransport` seam.
  UnifiedPush/FCM are Ask-First dependencies. UnifiedPush approved 2026-10-30 and used on the **receiving**
  leg (the server performs the final hop); FCM is still unapproved, so 2.3 stays open.

## 2. Implement the sender (server relay rung)

- [x] 2.1 Remote-recipient setting added and verified to persist: `motionAlertEnabled` + `motionAlertRecipient`
  on `AppSettings`/`AppSettingsEntity` (Room v22, real `Migration(21, 22)`, exported `22.json`), mapped in
  `DomainMappers`, editable in `SettingsEngineCard` (switch + recipient field), pinned by a
  `SettingsViewModelTest` that asserts both land on the Room row
- [x] 2.2 `MotionAlertTransport` seam with `ServerRelayMotionAlertTransport` as the only implemented rung:
  on a MOTION trigger the monitor posts the camera name, timestamp and the recipient's push address to
  `POST /api/alerts/relay`, and a failed send is reported as a missed delivery the caller logs. Verified
  device-free by `ServerRelayMotionAlertTransportTest` (every field forwarded, a failure surfaces as a
  failed `Result`). "A second phone receives it" is the 7.3 device gate, not this task.
- [ ] 2.3 (Ask-First) Add the FCM fallback rung only after explicit dependency approval — UnifiedPush does
  not close this task

## 3. Server dispatch — the endpoint that previously only logged

- [x] 3.1 `server/entry_recorder_server/alert_relay.py` with pure functions: `validate_push_endpoint`
  (https required, credentials rejected, loopback/link-local/RFC1918/reserved targets refused, reason enum),
  `redact_endpoint` (scheme + host, fixed marker for unparsable input), `motion_alert_payload` +
  `build_alert_body` (the JSON contract), `post_to_endpoint` (any non-2xx or transport error raises
  `PushDeliveryError`)
- [x] 3.2 `ALLOW_INSECURE_PUSH_ENDPOINTS` config setting (default false) beside the TLS settings, so a
  self-hosted LAN distributor stays reachable for the operator who opts in
- [x] 3.3 `/api/alerts/relay` dispatches instead of pretending: keeps `verify_api_key`, answers `400` with
  the rejection reason without dialling, posts through `run_in_threadpool` with an 8 s timeout so the
  blocking call never stalls the event loop, answers `502` when the distributor refuses, returns
  `{"status":"delivered","recipient_host":<redacted>}`, and logs only the redacted host. The false
  "accepts and dispatches it" comment is gone; `MotionAlertRequest` documents the recipient as a UnifiedPush
  endpoint
- [x] 3.4 Live-server check against a local echo target (2026-10-30, `ALLOW_INSECURE_PUSH_ENDPOINTS=true`):
  the echo target received the contract body, an `http://127.0.0.1` recipient answered `400`, an unreachable
  endpoint answered `502`, an unauthenticated call answered `401`, and the server log showed only
  `http://127.0.0.1:8009` — never the token in the path

## 7. Verification

- [ ] 7.1 `./gradlew compileDebugKotlin` and `:app:assembleDebug` green with the connector in place, plus
  `:app:processDebugMainManifest` read for the new service
- [ ] 7.2 `./gradlew :app:testDebugUnitTest` and `:app:lintDebug` green — baseline is 42 suites / 296 tests /
  0 failures, so any delta must be new passing tests
- [ ] 7.3 On-device with two phones: a distributor installed (ntfy or UnifiedPush.Fyi) on the receiving
  phone, motion triggered on the monitoring phone, and the remote notification observed arriving. This is
  the gate the old 3.2 worded as "confirm the remote notification arrives"; it cannot pass without physical
  phones and stays open
- [ ] 7.4 On-device: the same alert against a distributor that is installed but not reachable answers `502`
  and is logged as a missed delivery
- [ ] 7.5 `openspec validate add-motion-notification-to-other-phone` passes

## Workflow follow-up

- Groups 4 to 6 (receiving path, settings UI, delivery-vs-link-loss) are the work this decision unlocked;
  they land as the code lands.
- Do not archive until 7.3 and 7.4 pass on hardware; 2.3 stays open behind Ask-First.
