# Design

## Context

See `proposal.md — Why` for the motivation. The constraints that shape the approach:

- `MonitorLockKeeper` is deliberately built by the service rather than injected, because a graph singleton
  would tempt a second lock pair held past the service's death (`MonitorLockKeeper` class doc). Any new hold
  reason therefore has to arrive as an input to that one owner, not as a second holder.
- Evidence for "still needs the locks" is split across three owners today: pending post-record stops live in
  `TriggerEpisodeRunner`, local recordings in `DeviceMonitorPool`/`RecordingController`, and the idle opt-in in
  the device list the service reconciles. `MonitorLockKeeper` reaches them through one lambda.
- `serviceScope` is `CoroutineScope(Dispatchers.IO + SupervisorJob())`. `Dispatchers.IO` is
  multi-threaded, so `launch` per event gives neither ordering nor mutual exclusion.
- `OnDeviceMotionAnalyzer` is rebuilt whenever its captured `Device` differs from the new row, and its
  `isMotionActive` flag is private. The analyzer's episode state lives only inside the analyzer.
- Tests are device-free and deterministic: JUnit, `kotlinx-coroutines-test`, Turbine; no Robolectric and no
  new test dependencies. Android framework calls must sit behind a seam to be testable at all.

## Goals / Non-Goals

**Goals:**

- One lock owner, several *reasons*, each with a single release path.
- Ordering guarantee expressed as a visible queue, not as a dispatcher property that is easy to undo.
- Every new decision (hold? rebuild? announce an end?) lives in a pure, JVM-testable form; the Android
  mechanics stay thin wrappers around those decisions.

**Non-Goals:**

- No change to detection sensitivity, the analysis grid, thresholds or pre-roll content (separate change).
- No new user-facing setting, screen, permission or stored column. `keepLocksHeld` keeps its semantics.
- No attempt to make the 2N Logging-API pull loop crash-proof or to recover events lost during its backoff.

## Decisions

### D1 — Analyzer presence feeds the existing idle-hold input, as a reason supplier

`DeviceMonitorPool` exposes `anyAnalyzerRunning(): Boolean` (evidence: `activeMotionAnalyzers.isNotEmpty()`,
which is populated only for `recordOnMotionOnDevice` rows). The service then passes one combined reason
supplier to `MonitorLockKeeper` — pending stop, local recording, idle opt-in, or an analyzer running — replacing
the current two-part `continuousHold` field plus `workOutstanding` lambda.

*Why not let the analyzer acquire its own lock pair:* two holders of one wake lock means ref-counting and
re-opens the exact "lock held past the service's death" problem the class was extracted to prevent.
*Why not OR the flag into `setHoldThroughIdle` at the call site:* that keeps two sources of truth for one
decision and leaves the renew loop (D2) consulting different evidence than the release path.

### D2 — Non-ref-counted locks with a timed acquire and one renewal job

Both locks switch to `setReferenceCounted(false)` and `acquire(timeoutMs)` instead of the
`if (!isHeld) acquire()` dance. A single `renewalJob` on the service scope re-acquires every
`LOCK_RENEW_INTERVAL_MS` while the reason supplier still says "hold", and calls `releaseIfIdle()` when it no
longer does. Chosen cadence: **10-minute timeout, 5-minute renew**.

*Why half the timeout:* a Doze- or handler-deferred tick can miss one renewal without leaving an unprotected
window; two missed ticks is still a 10-minute margin, and the worst case is self-healing.
*Alternatives:* one long timeout renewed only on device-list reconcile — rejected, the device list rarely
changes while monitoring, so a lapse would go unnoticed for hours; no timeout for the analyzer hold — rejected,
it is the leak risk this change exists to close.
`holdFor(durationMs)` keeps its per-trigger behaviour and becomes an extension of the same timed acquire rather
than a separate mechanism.

### D3 — Battery-exemption diagnostic behind an injected probe, edge-triggered

A `BatteryOptimizationProbe` interface (`fun isIgnoringOptimizations(): Boolean`) with a PowerManager-backed
implementation bound in the existing Hilt `BindsModule`, injected into the service. The monitor reports it the
same way every other health signal in this codebase is reported: **on the edge only**, comparing against the last
reported value during a device-list reconcile — WARN when not exempt, INFO when exempt or when the platform
service is unavailable. `PermissionChecker.isBatteryExempt()` is reused by having the new probe own the
PowerManager call and letting the onboarding checker delegate to it, so the platform read exists once.

*Alternative — periodic rate-limited logging:* rejected in favour of the edge idiom already used by
`ConnectionEdgeTracker`, and it needs no timer. *Alternative — surface it in the UI:* out of scope.
The probe is read-only; no `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, matching the existing onboarding decision.

### D4 — One `Channel` and one collecting coroutine for all device events

`IntercomMonitorService` owns `Channel<IntercomEvent>(Channel.UNLIMITED)`; `onEvent` does a non-blocking
`trySend`, and a single `serviceScope.launch { for (event in channel) eventHandler.handle(event) }` is the only
caller of `MonitorEventHandler.handle`. Everything that already funnels through `onEvent` — device
poll loops, the analyzer, `RecorderEvent.Connection`, the SIP ring path — inherits ordering with no further
change. The scope cancellation in `onDestroy` already bounds the collector's lifetime.

*Why `UNLIMITED` over `BUFFERED`:* dropping a trigger event is the failure being fixed, and a burst is small.
`Channel.BUFFERED` (64) can silently drop under a reconnect storm.
*Alternative — `ioDispatcher.limitedParallelism(1)` with the existing per-event `launch`:* genuinely
preserves FIFO order and is a smaller diff, but rejected because the ordering contract becomes invisible, the
queue depth disappears, and it is not testable with Turbine.
`MonitorEventHandler` is untouched: it already owns no scope and runs on the caller's coroutine, which is now
exactly one coroutine.

### D5 — The analyzer declares the configuration it consumes; the pool compares that key

`OnDeviceMotionAnalyzer` gains a `keyFor(device)` companion derivation plus an internal
`MotionAnalysisKey(deviceId, snapshotUrl, username, password, motionSensitivity, effectiveMotionPreRollFrames)`.
`DeviceMonitorPool.sync` rebuilds when the key changes instead of when the whole row differs, and keeps the
existing analyzer otherwise. Defining the key next to the analyzer means a future analyzer that reads another
field changes one file and its test, rather than silently keeping the old value.

`isMotionActive` is exposed as `@Volatile val isEpisodeActive: Boolean`, matching how the pre-roll ring is
already read across threads.

### D6 — One stop path that closes an active episode

`DeviceMonitorPool` routes every analyzer stop through one private helper that calls `stop()` and, when
`isEpisodeActive`, emits `IntercomEvent.MotionOnDeviceEnded(analyzer.device)` to the listener. It covers the
reconcile paths: analysis turned off, rebuild, and device removed.

`stopAnalyzers()` on service teardown deliberately does **not** emit: `onDestroy` cancels `serviceScope` first,
so an emitted event would be discarded, and a clip that outlives the service is already bounded by its armed
`maxDuration`. The synthetic end routes through the D4 channel, so it lands after any event the analyzer already
queued — an episode can never be closed out of order relative to its own start.

## Risks / Trade-offs

- **[Continuous hold drains a phone that is not a dedicated detector]** → the hold is tied to an analyzer
  actually running, which is still a per-device opt-in, so disabling in-app analysis is the opt-out; the soak
  gate measures idle drain and the README documents the cost after ship.
- **[A timed acquire lapses between renewals under aggressive Doze]** → renew interval at half the timeout (D2),
  and the analyzer's existing per-minute health line (`poll …ms`, achieved fps) is the evidence to check at the gate.
- **[Unlimited queue grows if a handler blocks on a dead endpoint]** → every handler path is bounded by the
  existing OkHttp timeouts and retry budget, so a stall costs seconds; if the soak ever shows queue growth, cap
  the channel and log a drop rather than dropping silently.
- **[Serialization lets one slow device delay another's trigger]** → accepted: the handler already performs a
  settings read per motion episode, so parallel handling was never correct. Kept visible here and listed as a
  follow-up observation for the scoring change.
- **[A synthetic end truncates a clip that should have kept running after a rebuild]** → the ended event only
  schedules the normal post-record buffer, and the rebuilt analyzer re-fires while motion continues, folding into
  the clip that is still running instead of replacing it.
- **[Reversing `bound-wake-wifi-locks` without its open soak (task 2.2) passing]** → both gates are recorded as
  tasks in this change; neither change archives until the screen-off soak is green.

## Migration Plan

No data migration, no schema, no permission and no setting-surface change: revert is a single `git revert`.
Behaviour takes effect the next time the device list reconciles, which happens on service start and on any
device edit, so no restart is required.
