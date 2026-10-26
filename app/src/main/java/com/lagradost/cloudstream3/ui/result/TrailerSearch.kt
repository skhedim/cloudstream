package com.lagradost.cloudstream3.ui.result

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.fragment.app.Fragment
import com.lagradost.cloudstream3.utils.AppContextUtils.openWebView
import java.util.Locale

/** A search, not a guessed trailer URL. Android uses the user's YouTube/browser handler. */
object TrailerSearch {
    fun intent(title: String, year: Int?, french: Boolean = Locale.getDefault().language == "fr"): Intent {
        val query = listOfNotNull(title.trim(), year?.takeIf { it in 1800..2999 }?.toString(),
            if (french) "bande annonce officielle" else "official trailer").joinToString(" ")
        return Intent(Intent.ACTION_VIEW, Uri.Builder().scheme("https").authority("www.youtube.com")
            .path("/results").appendQueryParameter("search_query", query).build())
    }
    fun open(fragment: Fragment, title: String, year: Int?) {
        if (title.isBlank()) return
        val intent = intent(title, year)
        try { fragment.startActivity(intent) }
        catch (_: ActivityNotFoundException) { openWebView(fragment, intent.data.toString()) }
        catch (_: SecurityException) { openWebView(fragment, intent.data.toString()) }
    }
}
