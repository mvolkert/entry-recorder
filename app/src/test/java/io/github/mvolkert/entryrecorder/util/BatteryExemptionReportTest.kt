package io.github.mvolkert.entryrecorder.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The battery-exemption line the monitor logs is edge-triggered: a phone that stays subject to battery
 * optimization warns once rather than on every device-list emission, and the warning comes back if the user
 * toggles the exemption and takes it away again. The first read counts as a change, otherwise a detector that
 * has always been dozing would never say so.
 */
class BatteryExemptionReportTest {

    private val report = BatteryExemptionReport()

    @Test
    fun `the first read of a phone that is not exempt warns`() {
        assertEquals(BatteryExemptionNotice.NotExempt, report.noticeFor(exempt = false))
    }

    @Test
    fun `the first read of an exempt phone reports the good state once`() {
        assertEquals(BatteryExemptionNotice.NowExempt, report.noticeFor(exempt = true))
        assertEquals(BatteryExemptionNotice.None, report.noticeFor(exempt = true))
    }

    @Test
    fun `an unchanged state stays silent however often it is read`() {
        report.noticeFor(exempt = false)

        repeat(5) {
            assertEquals(BatteryExemptionNotice.None, report.noticeFor(exempt = false))
        }
    }

    @Test
    fun `a state flip is reported in both directions`() {
        report.noticeFor(exempt = false)

        assertEquals(BatteryExemptionNotice.NowExempt, report.noticeFor(exempt = true))
        assertEquals(BatteryExemptionNotice.NotExempt, report.noticeFor(exempt = false))
        assertEquals(BatteryExemptionNotice.None, report.noticeFor(exempt = false))
    }
}
