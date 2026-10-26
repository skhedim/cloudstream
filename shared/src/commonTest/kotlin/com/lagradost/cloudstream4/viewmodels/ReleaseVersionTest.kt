package com.lagradost.cloudstream4.viewmodels

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseVersionTest {
    @Test
    fun onlyNewerVersionsAreOffered() {
        assertTrue(ReleaseVersion.isNewer("1.0.10", "1.0.9"))
        assertTrue(ReleaseVersion.isNewer("1.1.0", "1.0.99"))
        assertTrue(ReleaseVersion.isNewer("2.0.0", "1.99.999"))
        assertFalse(ReleaseVersion.isNewer("1.0.9", "1.0.10"))
        assertFalse(ReleaseVersion.isNewer("1.0.10", "1.0.10"))
    }

    @Test
    fun malformedVersionsAreNotInstalled() {
        for (remote in listOf("", "CloudStream.apk", "1.0.2-beta", "1.2", "1.0.9999999999999999999999")) {
            assertFalse(ReleaseVersion.isNewer(remote, "1.0.1"))
        }
        assertFalse(ReleaseVersion.isNewer("1.0.2", "invalid"))
    }
}
