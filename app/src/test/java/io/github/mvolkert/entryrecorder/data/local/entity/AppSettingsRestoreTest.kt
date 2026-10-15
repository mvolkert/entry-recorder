package io.github.mvolkert.entryrecorder.data.local.entity

import com.google.gson.Gson
import io.github.mvolkert.entryrecorder.util.CleanupPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * What a restore may and may not trust in a backup file. Gson writes only the fields the file mentions, so
 * a quota can arrive as an explicit `0` from an older or hand-edited file — and `0` in the cleanup
 * arithmetic reads as "the archive may hold nothing". [normalizedForRestore] is the one place that decides
 * which values get repaired, so the decisions are pinned here rather than inferred from a comment.
 */
class AppSettingsRestoreTest {

    @Test
    fun `a backup file that says nothing leaves a usable quota`() {
        val restored = Gson().fromJson("{}", AppSettingsEntity::class.java).normalizedForRestore()
        assertEquals(AppSettingsEntity.DEFAULT_INTERNAL_QUOTA_MB, restored.internalQuotaMb)
        assertEquals(AppSettingsEntity.DEFAULT_EXTERNAL_QUOTA_MB, restored.externalQuotaMb)
    }

    @Test
    fun `a quota a backup file spells out as zero is repaired`() {
        // The dangerous value: a quota of 0 would read as "delete the whole archive", so a file that carries
        // one — old, edited or simply corrupt — comes back as the default instead. CleanupPolicy keeps even
        // a raw 0 inert, so this is defence in depth in front of the arithmetic, not the only guard.
        val restored = Gson()
            .fromJson("""{"internalQuotaMb":0,"externalQuotaMb":-1}""", AppSettingsEntity::class.java)
            .normalizedForRestore()
        assertEquals(AppSettingsEntity.DEFAULT_INTERNAL_QUOTA_MB, restored.internalQuotaMb)
        assertEquals(AppSettingsEntity.DEFAULT_EXTERNAL_QUOTA_MB, restored.externalQuotaMb)
        assertEquals(0L, CleanupPolicy.bytesToFree(500L * 1024L * 1024L, 0L))
    }

    @Test
    fun `a quota the user dialed in survives a restore untouched`() {
        val stored = AppSettingsEntity(internalQuotaMb = 700L, externalQuotaMb = 1_200L)
        val restored = Gson().fromJson(Gson().toJson(stored), AppSettingsEntity::class.java).normalizedForRestore()
        assertEquals(700L, restored.internalQuotaMb)
        assertEquals(1_200L, restored.externalQuotaMb)
    }

    @Test
    fun `a quota above the cap is left as it was rather than silently clamped`() {
        // Deliberate: the range's upper end is a cap on what a stepper may dial in, not a sign that the
        // number is junk, and quietly rewriting a restored 200 GB to 100 GB would change a limit the user
        // set. Such a quota purges nothing and the settings row says so out loud instead.
        val tooBig = CleanupPolicy.MAX_QUOTA_MB + 1024L
        val restored = AppSettingsEntity(internalQuotaMb = tooBig).normalizedForRestore()
        assertEquals(tooBig, restored.internalQuotaMb)
        assertFalse(CleanupPolicy.inRange(restored.internalQuotaMb))
        assertEquals(0L, CleanupPolicy.bytesToFree(1L * 1024L * 1024L * 1024L, restored.internalQuotaMb))
    }

    @Test
    fun `a restore never moves a cleanup switch towards deleting`() {
        // Turning cleanup on is something only the user at the screen may do; normalization repairs quotas
        // and must leave both switches exactly as the file had them.
        val silent = Gson().fromJson("{}", AppSettingsEntity::class.java).normalizedForRestore()
        assertFalse(silent.externalCleanupEnabled)
        val optedOut = Gson()
            .fromJson(
                """{"internalCleanupEnabled":false,"externalCleanupEnabled":false}""",
                AppSettingsEntity::class.java,
            )
            .normalizedForRestore()
        assertFalse(optedOut.internalCleanupEnabled)
        assertFalse(optedOut.externalCleanupEnabled)
    }

    @Test
    fun `a backup file that predates dynamic color keeps the curated accents`() {
        // Pins the opt-in default for add-material-you-dynamic-color: a file written before the flag
        // existed must not wake wallpaper colours on its own, and Gson's primitive default is the only
        // thing guaranteeing that — one flipped switch would override accents the user chose.
        val restored = Gson().fromJson("{}", AppSettingsEntity::class.java)
        assertFalse(restored.themeUseDynamicColor)
    }
}
