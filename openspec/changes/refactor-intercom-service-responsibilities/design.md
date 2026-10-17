# Design

## Context

See proposal.md — Why. `IntercomMonitorService` implements `IntercomEventListener`, so device callbacks
(`onEvent`) land directly on the service and fan out to lock, recording, alert, and SIP handling. The class
builds `serviceScope` lazily because Hilt field injection runs after the constructor — a constraint every
extracted collaborator inherits. `TriggerCoordinator` (pure, already unit-tested) holds the routing policy;
the service residue is all Android-bound side effects.

## Goals / Non-Goals

**Goals:**
- Each extracted class owns exactly one of: lock policy, device/analyzer pool, trigger-episode side effects.
- The service remains the only place that touches `Service` lifecycle, `startForeground`, and listener wiring.
- Zero behaviour delta: same call order, same dispatchers, same logs.

**Non-Goals:**
- No change to `TriggerCoordinator` rules, `RecordingController`, `SipCallManager`, or notification content.
- No new concurrency: collaborators reuse the service-provided scope/dispatcher instead of owning their own.

## Decisions

- **Collaborators are plain classes built by the service, not Hilt singletons.** They need the lazily-built
  `serviceScope` and per-callback context; injecting them via Hilt would duplicate scope plumbing and tempt
  each class to own its lifecycle. Alternative considered: `@Inject` constructors with a scope qualifier —
  rejected because the scope is tied to the service instance lifetime.
- **`MonitorLockKeeper` takes Context for `PowerManager`/`WifiManager`** and exposes `acquireForTrigger`,
  `holdContinuous`, `holdFor(durationMs)`, `releaseIfIdle`, `releaseAll` — mirroring today's private methods
  one-to-one so the diff is mechanical.
- **`DeviceMonitorPool.sync(devices)` absorbs the whole `updateMonitoredDevices` body** including the
  analyzer-rebuild rule and the four per-device cleanup calls (`monitorStatusRegistry.remove`,
  `pendingPostRecordStops` cancel via callback, `triggerCoordinator.forgetDevice`,
  `snapshotClient.forgetDevice`). The pool receives `triggerCoordinator` and registry directly; the
  post-record-stop cancellation is reached through a small lambda the service passes, keeping the pending-stop
  map inside `TriggerEpisodeRunner` where the timers live.
- **`TriggerEpisodeRunner` owns `pendingPostRecordStops`** because every entry in it is a stop timer the
  runner itself schedules; the pool's cleanup lambda calls back into it. Splitting the map from the timers
  would recreate the current scatter.
- **Extract in dependency order** (locks → pool → runner) so each step compiles standalone and the on-device
  gate can bisect regressions to one extraction.

## Risks / Trade-offs

- [Order-sensitive cleanup in `updateMonitoredDevices` silently changes] → Keep the merged loop bodies
  verbatim during the move; review the diff line-by-line against the original; on-device add/remove/disable
  cycle in the acceptance gate.
- [Lazy `serviceScope` becomes inaccessible after the move] → Pass the scope into collaborator constructors
  at first use (after injection), never from a property initialiser; same lateinit hazard, same workaround.
- [Three new files in `service/` blur package naming] → Acceptable at this size; a `service.internal` package
  is premature for one split.

## Migration Plan

Pure in-module refactor; no data or API migration. Rollback = revert commit; extractions are staged one task
group at a time so any stage can ship independently.

## Open Questions

- None blocking. Whether `handleSipRing` notification-building moves to `NotificationHelper` overloads can be
  decided during implementation; it does not affect the split.
