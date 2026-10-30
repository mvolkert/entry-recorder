package io.github.mvolkert.entryrecorder.service

/**
 * A fact that may keep the monitor's CPU and radio awake. Each one is owned by a different class; the enum
 * exists so the hold is decided from one set that the renewal loop and the release path both read, rather than
 * from an OR of lambdas that each caller assembles differently.
 */
enum class LockReason {
    /** A post-record buffer is still scheduled, so a clip has a stop coming that needs the CPU to reach it. */
    PendingPostRecordStop,

    /** The phone itself is writing a clip. */
    LocalRecording,

    /** A monitored device opted into holding the locks through idle periods. */
    KeepLocksHeldDevice,

    /**
     * An in-app motion analyzer is polling. Detection has to happen before any trigger exists, so a phone that
     * is dozing cannot record the motion it was set up to catch; this is the reason that hold belongs to.
     */
    MotionAnalyzerRunning,
}

/** The hold decision, with no Android dependency: any reason at all is enough to stay awake. */
object LockHoldPolicy {

    fun shouldHold(reasons: Set<LockReason>): Boolean = reasons.isNotEmpty()

    /** Assembles the reason set from the four facts, each read from whoever owns it. */
    fun reasonsFor(
        pendingPostRecordStop: Boolean,
        localRecording: Boolean,
        keepLocksHeldDevice: Boolean,
        motionAnalyzerRunning: Boolean,
    ): Set<LockReason> = setOfNotNull(
        LockReason.PendingPostRecordStop.takeIf { pendingPostRecordStop },
        LockReason.LocalRecording.takeIf { localRecording },
        LockReason.KeepLocksHeldDevice.takeIf { keepLocksHeldDevice },
        LockReason.MotionAnalyzerRunning.takeIf { motionAnalyzerRunning },
    )
}
