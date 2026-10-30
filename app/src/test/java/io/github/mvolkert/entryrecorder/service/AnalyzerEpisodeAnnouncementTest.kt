package io.github.mvolkert.entryrecorder.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which analyzer stops have to close the motion episode they interrupted. A reconcile-path stop (analysis turned
 * off, rebuild, device removed) is the only one that needs the synthetic end event, because the recording that
 * episode started still has a stop scheduled that nobody else will reach. Service teardown must not announce:
 * the whole monitor is going away, and a fabricated end there would race the real shutdown of the recorder.
 */
class AnalyzerEpisodeAnnouncementTest {

    @Test
    fun `a reconcile stop of an open episode announces its end`() {
        assertTrue(shouldAnnounceEpisodeEnd(episodeActive = true, shutdown = AnalyzerShutdown.Reconcile))
    }

    @Test
    fun `teardown stops silently`() {
        assertFalse(shouldAnnounceEpisodeEnd(episodeActive = true, shutdown = AnalyzerShutdown.ServiceTeardown))
    }

    @Test
    fun `a stop with no episode open never announces`() {
        assertFalse(shouldAnnounceEpisodeEnd(episodeActive = false, shutdown = AnalyzerShutdown.Reconcile))
        assertFalse(shouldAnnounceEpisodeEnd(episodeActive = false, shutdown = AnalyzerShutdown.ServiceTeardown))
    }
}
