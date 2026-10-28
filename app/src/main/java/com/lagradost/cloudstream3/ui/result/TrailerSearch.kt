package com.lagradost.cloudstream3.ui.result

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.fragment.app.Fragment
import com.lagradost.cloudstream3.utils.AppContextUtils.openWebView
import com.lagradost.cloudstream4.aggregation.Trailers
import java.util.Locale

/** A search, not a guessed trailer URL. Android uses the user's YouTube/browser handler. */
object TrailerSearch {
    fun intent(title: String, year: Int?, french: Boolean = Locale.getDefault().language == "fr"): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(Trailers.searchUrl(title, year, french)))
    fun open(fragment: Fragment, title: String, year: Int?) {
        if (title.isBlank()) return
        val intent = intent(title, year)
        try { fragment.startActivity(intent) }
        catch (_: ActivityNotFoundException) { openWebView(fragment, intent.data.toString()) }
        catch (_: SecurityException) { openWebView(fragment, intent.data.toString()) }
    }
}
