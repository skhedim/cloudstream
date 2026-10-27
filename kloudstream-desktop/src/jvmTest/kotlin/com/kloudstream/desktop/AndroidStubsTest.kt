package com.kloudstream.desktop

import android.graphics.Color
import android.net.Uri
import android.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Android API stand-ins must behave like Android where extensions rely on it. */
class AndroidStubsTest {
    @Test fun base64DecodesLikeAndroidWithoutPaddingAcrossLinesAndInBothAlphabets() {
        val bytes = "KloudStream ✓".toByteArray()
        val padded = java.util.Base64.getEncoder().encodeToString(bytes)
        assertArrayEquals(bytes, Base64.decode(padded, Base64.DEFAULT))
        assertArrayEquals(bytes, Base64.decode(padded.trimEnd('='), Base64.DEFAULT))
        assertArrayEquals(bytes, Base64.decode(padded.chunked(4).joinToString("\n"), Base64.DEFAULT))
        val urlSafe = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(-5, -1, -2))
        assertArrayEquals(byteArrayOf(-5, -1, -2), Base64.decode(urlSafe, Base64.URL_SAFE))
        assertArrayEquals(byteArrayOf(-5, -1, -2), Base64.decode(urlSafe, Base64.DEFAULT))
    }

    @Test fun base64EncodesWithAndroidFlags() {
        val long = ByteArray(80) { it.toByte() }
        val wrapped = Base64.encodeToString(long, Base64.DEFAULT)
        assertEquals(listOf(76, 32), wrapped.trimEnd('\n').split('\n').map { it.length })
        assertEquals('\n', wrapped.last())
        assertEquals("+/8", Base64.encodeToString(byteArrayOf(-5, -1), Base64.NO_WRAP or Base64.NO_PADDING))
        assertEquals("-_8=", Base64.encodeToString(byteArrayOf(-5, -1), Base64.NO_WRAP or Base64.URL_SAFE))
    }

    @Test fun uriEncodingKeepsAndroidsUnreservedSetAndDecodingKeepsPlusSigns() {
        assertEquals("Le%20Roi%20Lion%20(2019)%20%C3%A9t%C3%A9", Uri.encode("Le Roi Lion (2019) été"))
        assertEquals("a-b_c.d!e~f*g'h", Uri.encode("a-b_c.d!e~f*g'h"))
        assertEquals("a/b", Uri.encode("a/b", "/"))
        assertEquals("1+1 = 2", Uri.decode("1+1%20=%202"))
        val uri = Uri.parse("https://example.org/watch/v?id=42&title=Le%20Roi")
        assertEquals("example.org", uri.getHost())
        assertEquals("42", uri.getQueryParameter("id"))
        assertEquals("Le Roi", uri.getQueryParameter("title"))
        assertEquals("v", uri.getLastPathSegment())
    }

    @Test fun colorsParseLikeAndroid() {
        assertEquals(0xFFE50914.toInt(), Color.parseColor("#E50914"))
        assertEquals(0x80E50914.toInt(), Color.parseColor("#80E50914"))
        assertEquals(Color.WHITE, Color.parseColor("white"))
    }
}
