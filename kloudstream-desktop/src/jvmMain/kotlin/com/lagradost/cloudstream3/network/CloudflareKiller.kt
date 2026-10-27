package com.lagradost.cloudstream3.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Desktop CloudflareKiller: Android solves challenges in a WebView, which desktop does not
 * have. Requests pass through unchanged; sites behind an active challenge fail as unavailable.
 */
class CloudflareKiller : Interceptor {
    val savedCookies: MutableMap<String, Map<String, String>> = mutableMapOf()

    fun getCookieHeaders(url: String): okhttp3.Headers = okhttp3.Headers.Builder().build()

    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
