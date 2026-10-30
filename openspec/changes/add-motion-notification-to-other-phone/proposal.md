# Proposal

## Why

The owner wants motion detected on the monitored device to also notify a second smartphone, so a
detection-first phone can alert the person who is away from it. The setting and the sender already exist,
but the server endpoint they post to only logged the alert, and no code on any phone could receive one —
so the feature could not be completed by turning a dial on a device.

## What Changes

- The Python server actually delivers a relayed motion alert to the recipient push address the app sends,
  refusing an address it will not dial.
- A phone can opt in to receiving relayed motion alerts through an installed UnifiedPush distributor and
  raise a notification naming the camera that fired.
- The remote-recipient setting changes meaning from a free-text label to the other phone's push alert
  address, validated in the UI and redacted in logs.
- A missed delivery stops being reported as a lost connection to the server.

**Out of scope:** a general multi-user push system, changes to on-device motion detection, FCM (still an
unapproved Ask-First dependency), any inbound connection to the monitoring phone, and stripping the
recipient from settings backup (the sender needs it after a restore).

## Capabilities

### Modified Capabilities
- `alerts-lockscreen`: motion can raise a notification on another device, not only the monitoring phone, and
  a phone can receive such a notification while idle.
- `server-backend`: the relay endpoint performs the outbound delivery to a caller-supplied push address
  instead of acknowledging without dispatching.

## Impact

- Code: monitor trigger path (sender, unchanged destination), new `push/` receiving path, notification
  helper, boot receiver, settings screen, `server/entry_recorder_server/alert_relay.py` + the relay route.
- New dependency: `org.unifiedpush.android:connector` — approved by the owner on 2026-10-30 (Ask-First).
  No new permission: `POST_NOTIFICATIONS` is already declared; the push service is non-exported.
- No database change: receiver state lives in SharedPreferences, so a restored backup cannot hand another
  phone a dead address.
- Verification: Gradle compile/unit-test/lint plus a live server check against a local echo target; the
  two-phone gates (a distributor installed, the notification observed, a dead distributor answering `502`)
  need physical hardware and stay open.
