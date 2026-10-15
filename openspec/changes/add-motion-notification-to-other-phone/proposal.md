# Proposal

## Why

The owner wants motion detected on the monitored device to also notify a second smartphone, so a
detection-first phone can alert the person who is away from it.

## What Changes

- Add a configurable remote recipient that receives a motion notification from the monitoring device.

## Capabilities

### Modified Capabilities
- `alerts-lockscreen`: motion can raise a notification on another device, not only the monitoring phone.

## Impact

- Code: monitor service + notification path; needs a transport decision (see design open question).
- Possibly new permission / server round-trip — confirm before implementation.

## Note

This is a newly requested feature with an undecided delivery mechanism; the mechanism must be chosen
before tasks are executed.
