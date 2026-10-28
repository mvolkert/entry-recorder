package io.github.mvolkert.entryrecorder.service

/**
 * The one definition of the tag the monitoring path writes under.
 *
 * [IntercomMonitorService] delegates to [MonitorEventHandler], [MonitorLockKeeper] and
 * [TriggerEpisodeRunner], and all four report to logcat under this tag deliberately: an idle, lock or
 * post-record-timing investigation is read as one continuous stream (`adb logcat -s IntercomMonitorService`),
 * and the sequence of a trigger - event, lock, recording, alert, stop - only makes sense in that single
 * stream. The service split kept the tag in each collaborator but re-typed the literal every time, so this
 * constant is what stops one class's lines from silently moving to their own tag.
 */
internal const val MONITOR_LOG_TAG = "IntercomMonitorService"
