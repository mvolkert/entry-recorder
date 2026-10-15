# Proposal

## Why

On Verso FW 2.50.1.76.4 the app's HTTP event path is fiction: `/api/event/*` does not exist (the
SSE subscribe answers `code 2 invalid request path`) and the polling fallback hits `/api/motion/status`
and `/api/noise/status`, which are also not 2N paths. Result: camera-motion and record-on-noise can
never fire over HTTP, so rings fall back to the SIP INVITE and motion falls back to the in-app
analyzer. 2N's real event mechanism is the **Logging API**. The owner decided (2026-10-07) to adopt it
rather than delete the HTTP event path.

## What Changes

- Replace the invented `/api/event/subscribe` + `/api/motion|noise/status` transport with the 2N
  Logging API: `POST/GET /api/log/subscribe?filter=…&include=new&duration=90`, drain
  `/api/log/pull?channel=<id>`, release `/api/log/unsubscribe`, re-subscribe on expiry.
- Reuse `handleRaw2NEvent`'s decode (log entries carry the same `event` + `params` names), adapted to
  the real `pull` body.
- Gate the pull set on `requestedEventNames()` so only triggers the user enabled are subscribed.
- **BREAKING** (deployment): requires the Logging service enabled for the app's API user; over HTTP
  the endpoints answer `code 7 invalid connection type` (HTTPS required) and over HTTPS the stored
  admin credentials get `401 code 9` until the service is granted — see the Phase G prerequisite gate.

## Capabilities

### Modified Capabilities
- `event-transport`: add the Logging API pull transport; make trigger-gating govern it too.

## Impact

- Code: `data/device/TwoNIPVersoDevice.kt`, `service/IntercomMonitorService.kt`.
- Acceptance: on-device gate — one real `/api/log/pull` body of a doorbell press is the spec for the
  decode rewrite and is not yet captured.
- Trade-off (accepted): one small HTTP request every 1–2s per device, 24/7, on the Verso's ≈5.9 req/s
  serial ceiling shared with live/recording snapshots.
