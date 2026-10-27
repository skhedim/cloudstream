package com.lagradost.cloudstream3.ui.catalog

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager

/**
 * Some extensions put up a donation request on their own, as a dialog fragment
 * (`com.phisher98.donation`, `com.raghav.donation`, `com.cncverse.donation`). Such extensions are
 * only recommended because the request is removed here before it is ever shown: the fragment stops
 * being a dialog as soon as it is created, and is taken away. Settings screens opened from the
 * extensions menu are left alone.
 */
object ExtensionPromptBlocker : Application.ActivityLifecycleCallbacks {
    private const val TAG = "ExtensionPrompts"

    /** The app has no such fragment: any is an extension's. */
    fun isDonationPrompt(fragment: Fragment): Boolean =
        fragment is DialogFragment && fragment.javaClass.simpleName.contains("donation", ignoreCase = true) &&
            !fragment.javaClass.name.startsWith("com.lagradost.")

    private val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        // DialogFragment.onCreate decides whether it is a dialog: undo that right after, before any is built.
        override fun onFragmentCreated(fm: FragmentManager, f: Fragment, savedInstanceState: Bundle?) {
            if (!isDonationPrompt(f)) return
            (f as DialogFragment).showsDialog = false
            fm.beginTransaction().remove(f).commitAllowingStateLoss()
            Log.i(TAG, "Removed ${f.javaClass.name}")
        }
    }

    fun install(application: Application) = application.registerActivityLifecycleCallbacks(this)

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        (activity as? FragmentActivity)?.supportFragmentManager?.registerFragmentLifecycleCallbacks(fragments, true)
    }
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
