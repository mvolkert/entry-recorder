# Design

## Context

See `proposal.md` - Why. This pass was specced as a code-smell sweep with no behaviour delta, and it predates
the two decompositions that reshaped the files it names (`refactor-intercom-service-responsibilities` split
`IntercomMonitorService` into `MonitorEventHandler`, `MonitorLockKeeper` and `TriggerEpisodeRunner`;
`split-sip-call-manager-responsibilities` split `SipCallManager` into itself plus `SipDeviceAccountSync`).

That ordering is the constraint this design has to handle: seven of the eight proposal items landed through
this change or through the work that followed it, and one - a single shared log tag for the monitoring path -
regressed when the collaborators were created, each re-typing the literal instead of referencing one
definition. The change therefore has a small amount of real work left, plus the task record that lets it be
verified and archived.

No on-device or live-server verification is needed here: nothing in this pass changes what the app does, and
the two items with device-only consequences (the `flowOn` removal, the `@Volatile` fields) were landed by
other changes that carry their own gates.

## Goals / Non-Goals

**Goals:**
- One definition for the monitoring log tag, referenced by the service and its three collaborators, so the
  tag cannot drift per class.
- Each proposal item recorded as its own verifiable task with the code evidence that closes it, so a reader
  can tell what was done here from what was done by the changes that came after.
- `compileDebugKotlin`, `testDebugUnitTest` and `lintDebug` green with no test fixture changed.

**Non-Goals:**
- Expanding the wildcard imports this proposal already excludes - the three Room DAOs' `androidx.room.*` and
  `JpegFramePlayer.kt`'s Compose wildcards. No code-style tooling is configured in this project, so `lintDebug`
  cannot enforce import layout and changing them is churn with no check behind it.
- Renaming or restructuring the tag's ~28 call sites. Only the four declarations change.
- Further splitting `IntercomMonitorService` or `SipCallManager` to hit another change's size target; that is
  owned by the decomposition changes, not by hygiene.
- Any behaviour, dependency, schema or manifest change.

## Decisions

**Shared value, local names.** `service/MonitorLogTag.kt` declares `internal const val MONITOR_LOG_TAG`, and
each class keeps its own `TAG`/`tag` member sourced from it (`private const val TAG = MONITOR_LOG_TAG` in the
service's companion, `private val tag = MONITOR_LOG_TAG` in the three collaborators). Rewriting every call
site to use the shared name was rejected: it is a larger diff for the same guarantee, and the local name is
what makes each file's `Timber.tag(tag)` reads stay unchanged. A `const val` in the service's companion object
alone was also rejected - it would make three collaborators depend on the service class that depends on them.

**`internal`, top level, in the `service` package.** The tag is only meaningful to the monitoring path, so it
is not promoted to a cross-cutting `util` constant (where a name like `LOG_TAG` invites unrelated reuse) and
not made public. All four consumers already live in `io.github.mvolkert.entryrecorder.service`, so no import
changes are needed.

**The value stays byte-identical.** `"IntercomMonitorService"` is what `adb logcat -s IntercomMonitorService`
and the existing `FileTree` exports are read with, and the whole point of the shared tag is one continuous
stream per trigger. Renaming it to match class names would be a behaviour change this pass forbids, so the
constant's KDoc records why the string differs from the class writing it.

## Risks / Trade-offs

- **A collaborator added later declares its own literal again.** Mitigated by the constant existing and by
  `MonitorLogTag.kt`'s KDoc naming the classes that must use it; not machine-checked, because no style
  tooling is configured here.
- **Three collaborators keep a `private val` rather than a `const val`.** They are ordinary classes with no
  companion to hold a const, and a `val` initialised from a compile-time constant costs nothing meaningful
  while keeping the call sites untouched.
- **The proposal's file list has aged.** Two of the five files it names for import expansion live at different
  paths now (`ui/components/RtspVideoPlayer.kt`, `video/OnDeviceMotionAnalyzer.kt`); the task records the
  current paths so the evidence stays checkable.

## Verification

- `./gradlew compileDebugKotlin testDebugUnitTest lintDebug` green (Windows: `gradlew.bat` with the same
  tasks), with the existing suite counts unchanged - this pass adds no test because it asserts nothing about
  behaviour, and the tag value is proven by grep rather than by a JVM test.
