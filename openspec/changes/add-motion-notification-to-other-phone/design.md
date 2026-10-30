# Design

## Context

See proposal.md - Why. The monitoring phone detects motion locally; the question is only how to reach a
second phone. Until this pass the change had a sender and no receiver: `POST /api/alerts/relay` logged the
alert and answered `{"status":"relayed"}` without dialling anybody, so nothing could ever buzz.

## Goals / Non-Goals

**Goals:** deliver a motion alert to one configured remote phone, and let a phone receive such an alert
while it is idle.
**Non-Goals:** not a general multi-user push system; no change to on-device motion detection; no inbound
connection to the monitoring phone.

## Decisions

- **Transport (owner, 2026-10-16):** a pluggable `MotionAlertTransport` seam with an ordered delivery
  ladder — (a) **primary**: relay through the existing authenticated Python server (already the
  event-transport backend, works behind NAT); (b) **vendor-free push**: UnifiedPush when the recipient
  phone has a distributor and the server path is unavailable; (c) **fallback**: FCM where UnifiedPush isn't
  an option. The spec requirement stays transport-agnostic; only the seam + the backend order change.
- **UnifiedPush was approved (owner, 2026-10-30), and it is used on the receiving side, not as rung (b).**
  The sender keeps posting to the server; the *server* performs the final hop to the recipient's push
  endpoint. Rationale: the phone gains no second outbound destination, so the `harden-network-transport`
  posture (LAN cleartext + WAN TLS, system trust anchors only) and the single origin-keyed
  `PythonServerLink` edge stay intact, and the "dial an address a user pasted" surface sits in one place
  where it can be guarded. Rung (b) as originally worded is therefore not built; rung (c) FCM stays open
  and still Ask-First gated.
- **`motionAlertRecipient` changed meaning** from a free-text label to the recipient phone's push endpoint
  URL. The feature had never delivered anything, so no working configuration or user data was at risk.
- **Payload contract** — the server POSTs this as the raw request body and the distributor hands the bytes
  to `PushService.onMessage`: `{"kind":"entry_recorder.motion","deviceName":"<camera name>","monitorName":
  "<sender label or empty>","timestampMs":<epoch ms>}`. The receiver drops an unknown `kind`, a missing
  `kind`, malformed JSON, a blank `deviceName` and any message the distributor did not decrypt
  (`decrypted == false`); it never guesses at a body. A missing or non-positive `timestampMs` is stamped
  with arrival time rather than dropping the alert. `MOTION_ALERT_KIND` in
  `server/entry_recorder_server/alert_relay.py` and `push/RemoteMotionAlertHandler.kt` must stay in step.
- **Endpoint validation on the server** (`alert_relay.validate_push_endpoint`): `https` required, no
  userinfo, non-empty host, and loopback / link-local / RFC1918 / `::1` targets refused unless
  `ALLOW_INSECURE_PUSH_ENDPOINTS=true` (the escape hatch for a self-hosted LAN distributor). A rejected
  address answers `400` with the reason and is never dialed; a distributor that answers non-2xx, times out
  or refuses the connection answers `502`.
- **Receiver state lives in SharedPreferences, not Room.** `AppSettingsEntity` rows participate in
  backup/restore, and an install-specific push endpoint restored onto a different device is a dead address;
  re-registration re-derives it. Mirrors the existing `AutoBootStore` / `UiModePrefs` pattern, so no schema
  migration.
- **Receiving is explicit opt-in.** Registration with a push distributor is a user-visible side effect, so
  it happens only after "Receive motion alerts on this phone" is turned on. `BootReceiver` re-registers
  after a reboot when the flag is set, going through the ack'd distributor so the user is not asked to pick
  one again.
- **Web-Push distributors cannot be served by this relay and say so.** If the endpoint carries a
  `pubKeySet`, a plain POST body is not something that distributor can decrypt, so the settings screen
  reports the limitation instead of offering a useless address to copy.
- **Only a transport failure may declare the server lost.** The relay call feeds the shared
  `PythonServerLink` clock like every other server call: a `400`/`502` answer is a reachable server that
  refused to deliver, so `TriggerEpisodeRunner` logs it as a delivery warning. An `IOException` is the only
  path that can open an outage. The failure line names the recipient's *origin* only.
- **The recipient is a bearer address, so it is redacted everywhere but the setting itself.** The token
  rides in the URL path, so server logs, app logs and `AppSettings.toString()` print `scheme://host` (or
  `<redacted>`), and an unparsable value is replaced by a fixed marker rather than echoed. Settings backup
  still carries the full value — the sender needs it after a restore.

## Risks / Trade-offs

- [The server dials a user-supplied URL] → validated as above before any request; single hop, one place to
  audit; no credentials attached to the outbound call beyond the body.
- [Delivery is best-effort and the monitor loop must not stall] → fire-and-report: the relay returns a
  `Result`, an 8 s timeout bounds the server's outbound call, and a failure only ever logs.
- [A distributor delivers nothing while the receiving phone's app is idle] → the connector's service is
  woken by the distributor; re-registration on boot keeps the endpoint fresh.
- [Two relays of the same burst can collide with local notifications] → remote alerts use a notification id
  range (`20000 + masked camera-name hash`) that cannot overlap the local event ids (`≤ 13999`), while
  repeat bursts from one camera collapse into one notification.

## Open Questions

- ~~Which transport delivers to the second phone?~~ Resolved 2026-10-16 (server relay primary; UnifiedPush
  then FCM as push fallbacks) and re-resolved 2026-10-30: the server performs the final hop to a
  UnifiedPush endpoint, so the receiving leg is the connector and no app-side push sender exists. See
  Decisions.
- ~~Are the UnifiedPush / FCM dependencies approved?~~ UnifiedPush answered 2026-10-30 (approved) and is
  in as `org.unifiedpush.android:connector`. FCM remains unapproved and is the only open Ask-First
  question in this change.
