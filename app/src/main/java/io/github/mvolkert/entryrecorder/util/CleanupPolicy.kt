package io.github.mvolkert.entryrecorder.util

/**
 * The single source of truth for how much storage headroom the user asks for and how much a cleanup
 * pass actually reclaims. The same step drives the settings steppers and the worker's purge, so the
 * number on screen is the number the purge aims for.
 */
object CleanupPolicy {
    /** Quota granularity: 100 MB both for the stepper taps and for the space a pass leaves below the limit. */
    const val STEP_MB: Long = 100L

    const val MIN_QUOTA_MB: Long = 100L
    const val MAX_QUOTA_MB: Long = 102_400L

    private const val BYTES_PER_MB = 1024L * 1024L

    /**
     * [quotaMb] moved by [deltaSteps] stepper taps, clamped into the selectable range.
     *
     * A stored value that is not in range — a `0` backfilled from a legacy limit that was itself `0`, or one
     * above the cap from the days when the stepper had no maximum — does not step from where it sits.
     * Clamping such a base into the range would land it on the smallest limit in either direction, and the
     * smallest limit is the most destructive quota there is: one tap on "Increase" would empty the archive.
     * A tap therefore starts from [unusableFallbackMb], the default for that scope, while the row says out
     * loud that the stored value was doing nothing.
     */
    fun nextQuotaMb(quotaMb: Long, deltaSteps: Int, unusableFallbackMb: Long): Long {
        val base = if (inRange(quotaMb)) quotaMb else unusableFallbackMb
        return (base + deltaSteps * STEP_MB).coerceIn(MIN_QUOTA_MB, MAX_QUOTA_MB)
    }

    fun inRange(quotaMb: Long): Boolean = quotaMb in MIN_QUOTA_MB..MAX_QUOTA_MB

    /**
     * How many bytes a cleanup pass has to reclaim to get [usedBytes] back under a [quotaMb] limit.
     *
     * Two deliberate margins: a pass only starts once the overflow reaches a full step (otherwise every
     * daily run would delete something over a megabyte of churn), and it aims one step *below* the
     * quota so the next clip does not immediately overflow again. The target never drops below half the
     * quota, so the smallest selectable limit does not mean "purge everything".
     *
     * A quota outside [MIN_QUOTA_MB]`..`[MAX_QUOTA_MB] frees nothing at all rather than being treated as
     * zero: a leftover `0` (an old backup file could carry one into the database before this range
     * existed) would otherwise read as "delete every unprotected recording" — the one answer the user
     * never meant to give.
     */
    fun bytesToFree(usedBytes: Long, quotaMb: Long, stepMb: Long = STEP_MB): Long {
        if (!inRange(quotaMb)) return 0L
        val quotaBytes = quotaMb * BYTES_PER_MB
        val stepBytes = stepMb * BYTES_PER_MB
        if (usedBytes - quotaBytes < stepBytes) return 0L
        val targetBytes = (quotaBytes - stepBytes).coerceAtLeast(quotaBytes / 2)
        return (usedBytes - targetBytes).coerceAtLeast(0L)
    }

    /**
     * The quota expressed in whole gigabytes, or null when it is not a clean GB value. Lets the row show
     * `10GB` where it reads better and `2500MB` everywhere else; the caller owns the localized unit.
     */
    fun wholeGigabytes(quotaMb: Long): Long? =
        if (quotaMb >= 1024L && quotaMb % 1024L == 0L) quotaMb / 1024L else null
}
