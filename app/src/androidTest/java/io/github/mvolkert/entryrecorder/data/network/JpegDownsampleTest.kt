package io.github.mvolkert.entryrecorder.data.network

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * On-device verification of the live-view decode bound (the emulator's real BitmapFactory, so no Robolectric).
 * Pins gate 3.3's numeric claim: a large source still decodes down to a bounded long edge, and a bounded
 * decode never keeps the full-resolution bitmap in the loop that replaces frames several times a second.
 */
@RunWith(AndroidJUnit4::class)
class JpegDownsampleTest {

    private fun jpeg(width: Int, height: Int): ByteArray {
        val src = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { color = Color.RED }
        Canvas(src).apply {
            drawColor(Color.BLUE)
            drawLine(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
        val out = ByteArrayOutputStream()
        src.compress(Bitmap.CompressFormat.JPEG, 90, out)
        src.recycle()
        return out.toByteArray()
    }

    @Test
    fun largeSourceDecodesToBoundedLongEdge() {
        val bytes = jpeg(4000, 3000)
        val decoded = decodeDownsampledJpeg(bytes, DEFAULT_LIVE_MAX_DECODE_EDGE)
            ?: throw AssertionError("expected a decoded bitmap")
        val longEdge = maxOf(decoded.width, decoded.height)
        // inSampleSize is a power of two, so the long edge lands in [720, 2*720): bounded, never full-res.
        assertTrue("long edge $longEdge must be under 2*cap", longEdge < 2 * DEFAULT_LIVE_MAX_DECODE_EDGE)
        assertTrue("long edge $longEdge must actually downscale 4000", longEdge < 4000)
        decoded.recycle()
    }

    @Test
    fun smallSourceStaysFullResolution() {
        val bytes = jpeg(640, 480)
        val decoded = decodeDownsampledJpeg(bytes, DEFAULT_LIVE_MAX_DECODE_EDGE)
            ?: throw AssertionError("expected a decoded bitmap")
        // Already under the cap: no downscale, so the analyzer/recording-quality paths keep native detail.
        assertEquals(640, decoded.width)
        assertEquals(480, decoded.height)
        decoded.recycle()
    }

    @Test
    fun zeroCapReturnsFullResolution() {
        val bytes = jpeg(2000, 1500)
        val decoded = decodeDownsampledJpeg(bytes, 0)
            ?: throw AssertionError("expected a decoded bitmap")
        assertTrue(decoded.width > 1000)
        decoded.recycle()
    }
}
