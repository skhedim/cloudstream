package com.lagradost.cloudstream3

import android.app.Application
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.ui.catalog.ExtensionPromptBlocker
import org.example.donation.DonationDialogFragment
import org.example.donation.SettingsDialogFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ExtensionPromptBlockerTest {
    @Test fun donationRequestsFromExtensionsNeverShowButTheirSettingsDo() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        ExtensionPromptBlocker.onActivityCreated(activity, null)
        val fragments = activity.supportFragmentManager

        val prompt = DonationDialogFragment()
        prompt.show(fragments, "donation")
        fragments.executePendingTransactions()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ShadowDialog.getShownDialogs().isEmpty())
        assertFalse(prompt.dialog?.isShowing == true)
        assertNull(fragments.findFragmentByTag("donation"))

        val settings = SettingsDialogFragment()
        settings.show(fragments, "settings")
        fragments.executePendingTransactions()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(fragments.findFragmentByTag("settings"))
        assertEquals(listOf(settings.dialog), ShadowDialog.getShownDialogs())
    }
}
