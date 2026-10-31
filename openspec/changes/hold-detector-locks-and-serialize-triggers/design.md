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

### D2 — Non-ref-counted locks with a timed acquire, one renewal job, and one deadline

Both locks switch to `setReferenceCounted(false)` and are taken as one pair by
`LockController.acquire(timeoutMs)` instead of the `if (!isHeld) acquire()` dance. **Both halves have to be told
explicitly:** a `WifiLock` is constructed reference counted, so once the `if (!isHeld)` guard is gone, acquiring on
every renewal tick stacks a count that one release cannot zero and the radio never sleeps again. **Only the wake
lock can carry a platform timeout:** `WifiManager.WifiLock` exposes an untimed `acquire()` and nothing else, so the
radio half is bounded by its holder rather than by the framework - the renewal tick that re-acquires also releases
both as soon as no reason is left, which caps an abandoned Wi-Fi hold at one renew interval instead of until
process death. Chosen cadence: **10-minute timeout, 5-minute renew**.

*Why half the timeout:* a Doze- or handler-deferred tick can miss one renewal without leaving an unprotected
window; two missed ticks is still a 10-minute margin, and the worst case is self-healing.
*Alternatives:* one long timeout renewed only on device-list reconcile — rejected, the device list rarely
changes while monitoring, so a lapse would go unnoticed for hours; no timeout for the analyzer hold — rejected,
it is the leak risk this change exists to close.

*One hold decision, read by every entry point.* `holdStillWanted()` is the only place that asks "still needed?":
a reason applies **or** an armed `holdFor` deadline has not passed, and the reconcile, the release path and the
renewal tick all read it. A trigger whose work this phone never observes - a server-side clip, or a doorbell ring
with ring recording off - would otherwise lose the pair at the first tick or the first device-list re-emit, which
is a regression against the unbounded acquire this replaces rather than a tightening of it. `holdFor(durationMs)`
stays one mechanism with the standing hold instead of a separate one: it raises the window to cover the clip and
re-arms its own release from the **surviving** deadline, so a short hold landing inside a long one cannot pull the
release forward.

Every entry point is `@Synchronized`. The callers are the device-list collector, the event queue's collector and
the post-record timer - separate threads of one multi-threaded dispatcher - and both jobs are decided by a
check-then-act on their own fields, so an unsynchronised keeper can leave a second renewal loop running that
nothing can stop. No call inside the monitor suspends.

Teardown is the one caller that must be last. `shutdown()` raises a closed flag, cancels the renewal loop and the
armed safety release, and releases the pair — all under the same monitor a tick takes — so a tick already awake when
the service cancelled its scope either runs wholly before the release or sees the flag after it. The cancelled jobs
are awaited **after** that, under the teardown pass's own deadline: awaiting inside the monitor would deadlock
against the very tick being waited out. The ordering is what matters because the radio half carries no platform
timeout — whatever releases last has to be the last thing that could acquire at all.

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

`service/MonitorEventQueue.kt` owns `Channel<IntercomEvent>(Channel.UNLIMITED)` together with the single
collecting coroutine launched on `serviceScope`; `onEvent` does a non-blocking `trySend`, and that collector is
the only caller of `MonitorEventHandler.handle`. Channel and consumer in one class is what makes the ordering a
property of the queue rather than of the dispatcher, and what lets the contract be tested without a `Context`.
Everything that already funnels through `onEvent` — device
poll loops, the analyzer, `RecorderEvent.Connection`, the SIP ring path — inherits ordering with no further
change. The scope cancellation in `onDestroy` already bounds the collector's lifetime.

*Isolate each event inside the collector.* The collector is the only consumer, so an error that escapes it cancels
that one coroutine and every later event then sits in an unbounded channel that nothing reads: trigger handling ends
for the rest of the process lifetime, silently, while the foreground notification still says the monitor is running.
Each event is therefore handled behind its own failure boundary and logged with the event identity and no credential.
A `Channel.UNLIMITED` never refuses a `trySend`, so `submit()`'s drop warning would be unreachable if nothing closed
the channel: teardown `close()`s it, which makes the branch honest — an event arriving during the device-stop pass is
refused and reported rather than parked in a channel with no reader. The alternative was deleting the branch and
documenting that an unbounded queue never refuses, which would have left a ring during shutdown vanishing without a
line.

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

*The row the surviving analyzer carries is stale, and that is a separate bug from the loop.* The analyzer reports
through the lambda it was constructed with and stamps its events from **its own** `device` field, so a surviving
analyzer emits the row that existed when it was built: after an edit that changes identity fields without rebuilding
— which this decision deliberately allows — the handler reads `recordOnRing`, `recordOnMotion` and `recordOnNoise`
straight off the event's row and `TriggerEpisodeRunner` takes `wakeOnMotion` from it, so an alert keeps acting on
switches the user has already flipped. Fix it by dispatching with the row the pool currently keeps, **not** by
widening the key: identity fields never touch the polling loop the key exists to protect, and putting them in it
would reintroduce the rebuild gap this decision removed. Whether the polling loop may also keep running with a
stale effective fps or frame count is the other half of "the row is stale" and lives in `start()`, not here — see
Open questions.

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
- **[The radio half cannot self-expire]** → `WifiManager.WifiLock` has no timed acquire, so an abandoned hold would
  outlive the wake lock it is paired with. The renewal tick releases the pair at the same point it would let the
  wake lock lapse, so the worst case is one renew interval of extra radio, and gate 5.3 is the evidence that an
  idle monitor drops both.
- **[A renewal acquire silently stacks the Wi-Fi reference count]** → the framework defaults a `WifiLock` to
  reference counted, so the acquire-on-every-tick pattern needs `setReferenceCounted(false)` on that half too or
  no single release can drop the radio. Set explicitly in the controller, and gate 5.3 reads `dumpsys wifi` rather
  than trusting `dumpsys power`, which only proves the timed half lapsed.
- **[Unlimited queue grows if a handler blocks on a dead endpoint]** → every handler path is bounded by the
  existing OkHttp timeouts and retry budget, so a stall costs seconds; if the soak ever shows queue growth, cap
  the channel and log a drop rather than dropping silently.
- **[One throwing event ends all trigger handling]** → the single collector is the only consumer, so an escaping
  error cancels it and later events queue to a reader that no longer exists, with the notification still claiming the
  monitor runs. Every event now has its own failure boundary and teardown closes the channel so a refused submit is
  reported; the risk of isolating them is a silently broken handler that keeps firing, so the failure is logged with
  the event identity rather than swallowed.
- **[A surviving analyzer routes alerts by a row the user already edited]** → narrow-key rebuilds keep identity-field
  edits from reaching the dispatch path, since the analyzer stamps its own construction row. The dispatch boundary
  now resolves the row through the pool's live device map; the paired question of a stale fps or frame count in the
  polling loop is **not** covered there and is recorded under Open questions.
- **[Serialization lets one slow device delay another's trigger]** → accepted: the handler already performs a
  settings read per motion episode, so parallel handling was never correct. Kept visible here; the scoring change that
  would revisit it no longer exists (see Open questions), so nothing depends on that follow-up.
- **[A synthetic end truncates a clip that should have kept running after a rebuild]** → the ended event only
  schedules the normal post-record buffer, and the rebuilt analyzer re-fires while motion continues, folding into
  the clip that is still running instead of replacing it.
- **[Reversing `bound-wake-wifi-locks` without its open soak (task 2.2) passing]** → both gates are recorded as
  tasks in this change; neither change archives until the screen-off soak is green. The reconciliation is named in
  this change's `tasks.md` follow-up: once gates 5.2/5.3 pass, `bound-wake-wifi-locks` gets archived or superseded
  so the two opposite lock postures never both sit active in `openspec/changes/`, and `README.md` gains the
  human-facing paragraph on the new power posture for analyzing deployments.

## Migration Plan

No data migration, no schema, no permission and no setting-surface change: revert is a single `git revert`.
Behaviour takes effect the next time the device list reconciles, which happens on service start and on any
device edit, so no restart is required.

## Open questions

- **Where does the motion-scoring half of the stale row go?** Task 4.5 fixed only the identity a device event carries.
  The analyzer's `start()` also captures `effectiveMotionAnalysisFps` and the frame count into the polling loop, and
  the key deliberately excludes both, so those keep running at old values after an edit. That belongs to
  `add-wake-lock-while-on-device-motion-scoring`, which has since been **deleted from `openspec/changes/` and never
  committed** — there is no receiving change, so it needs re-opening on its own rather than folding in here.
- **Should the analyzer's existing per-minute health log become the gate-5.2 evidence for a renewal lapse,** or does
  it need a dedicated line? The health line already prints poll interval and achieved fps and is sufficient at the
  screen-off gate.
