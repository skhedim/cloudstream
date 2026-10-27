package com.kloudstream.desktop

import com.kloudstream.desktop.app.Updates
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test fun versionsCompareNumericallyAndDevelopmentBuildsAlwaysUpdate() {
        assertTrue(Updates.isNewer("1.0.10", "1.0.9"))
        assertTrue(Updates.isNewer("1.1", "1.0.42"))
        assertFalse(Updates.isNewer("1.0.6", "1.0.6"))
        assertFalse(Updates.isNewer("1.0.5", "1.0.6"))
        assertTrue(Updates.isNewer("1.0.6", "dev"))
        assertFalse(Updates.isNewer("latest", "1.0.6"))
    }
}
