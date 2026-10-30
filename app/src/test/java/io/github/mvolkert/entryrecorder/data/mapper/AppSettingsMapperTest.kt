package io.github.mvolkert.entryrecorder.data.mapper

import com.google.gson.Gson
import io.github.mvolkert.entryrecorder.data.local.entity.AppSettingsEntity
import io.github.mvolkert.entryrecorder.testing.testCredentialCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings row's two mapping directions, pinned for the auto-export pair added by
 * `add-auto-transcode-export`: both mirrors have to survive entity → domain → entity, because the
 * storage screen updates one row by copying it and the finalize branch reads the domain form. The
 * callers that decide which mirror runs trust the mapping to carry exactly what was stored.
 */
class AppSettingsMapperTest {
    private val cipher = testCredentialCipher()

    @Test
    fun `the auto-transcode mirror survives the entity to domain to entity round trip`() {
        val entity = AppSettingsEntity(autoExportOnFinalize = false, autoTranscodeOnFinalize = true)

        val domain = entity.toDomain(cipher)
        val back = domain.toEntity(cipher)

        assertTrue(domain.autoTranscodeOnFinalize)
        assertFalse(domain.autoExportOnFinalize)
        assertTrue(back.autoTranscodeOnFinalize)
        assertFalse(back.autoExportOnFinalize)
    }

    @Test
    fun `the raw mirror survives the round trip while the transcode mirror stays off`() {
        val entity = AppSettingsEntity(autoExportOnFinalize = true, autoTranscodeOnFinalize = false)

        val domain = entity.toDomain(cipher)

        assertTrue(domain.autoExportOnFinalize)
        assertFalse(domain.autoTranscodeOnFinalize)
        assertEquals(entity, domain.toEntity(cipher))
    }

    @Test
    fun `a settings object that never mentions the mirrors maps them off`() {
        // The fresh-install and pre-v25 backup shape: the field is simply absent, so the data-class
        // default is what reaches the domain and then the finalize branch.
        val restored = Gson().fromJson("""{"retentionDays":7}""", AppSettingsEntity::class.java)

        val domain = restored.toDomain(cipher)

        assertFalse(domain.autoTranscodeOnFinalize)
        assertFalse(domain.autoExportOnFinalize)
        assertEquals(7, domain.retentionDays)
    }
}
