package io.github.mvolkert.entryrecorder.worker

import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue the export spec asks the auto-transcode mirror to provide, pinned as the data
 * [AndroidAutoExportScheduler] hands WorkManager: one shared chain, appended to rather than replaced. The
 * serialization itself — one encode at a time, submission order, surviving process death — is WorkManager's
 * behaviour and can only be observed on device (gate 5.3); what this test guards is the descriptor drifting to
 * a policy that would run encodes in parallel or drop the ones already waiting.
 */
class AutoExportQueueTest {

    @Test
    fun `automatic exports append to the chain instead of replacing or parallelising it`() {
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, AUTO_EXPORT_QUEUE.enqueuePolicy)
    }

    @Test
    fun `automatic exports share one chain with the recording-scoped worker`() {
        // The broadcast trigger enqueues on this same constant, so the two writers of the SAF folder can never
        // run at the same time.
        assertEquals(ExportTriggerWorker.UNIQUE_WORK_NAME, AUTO_EXPORT_QUEUE.uniqueWorkName)
    }

    @Test
    fun `the absent-recording sentinel stays negative so a refused insert id cannot be read as one`() {
        // Room answers -1 for an insert that did not take a row. The finalize site refuses to mirror such a
        // clip; this constant staying negative is what keeps an absent scope distinguishable from that answer
        // if the guard were ever loosened to a null check.
        assertTrue(ExportTriggerWorker.NO_RECORDING_ID < 0L)
    }
}
