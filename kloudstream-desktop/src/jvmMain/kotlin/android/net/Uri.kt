package android.net

import java.net.URI
import java.net.URLDecoder

/** Android's Uri: the encode/decode helpers and the parsing accessors extensions use. */
open class Uri private constructor(private val text: String) {
    private val uri: URI? = runCatching { URI(text) }.getOrNull()

    open fun getScheme(): String? = uri?.scheme
    open fun getHost(): String? = uri?.host
    open fun getPort(): Int = uri?.port ?: -1
    open fun getPath(): String? = uri?.path
    open fun getQuery(): String? = uri?.query
    open fun getEncodedQuery(): String? = uri?.rawQuery
    open fun getFragment(): String? = uri?.fragment
    open fun getLastPathSegment(): String? = getPathSegments().lastOrNull()
    open fun getPathSegments(): List<String> = getPath()?.split('/')?.filter { it.isNotEmpty() }.orEmpty()
    open fun getQueryParameter(key: String): String? = queryPairs().firstOrNull { it.first == key }?.second
    open fun getQueryParameters(key: String): List<String> = queryPairs().filter { it.first == key }.map { it.second }
    open fun getQueryParameterNames(): Set<String> = queryPairs().map { it.first }.toSet()
    private fun queryPairs(): List<Pair<String, String>> = getEncodedQuery()?.split('&')?.filter { it.isNotEmpty() }?.map {
        val name = it.substringBefore('='); val value = if ('=' in it) it.substringAfter('=') else ""
        decode(name).orEmpty() to decode(value).orEmpty()
    }.orEmpty()
    override fun toString(): String = text
    override fun equals(other: Any?) = other is Uri && other.text == text
    override fun hashCode() = text.hashCode()

    companion object {
        private const val ALLOWED = "_-!.~'()*"

        @JvmStatic fun parse(uriString: String): Uri = Uri(uriString)

        @JvmStatic fun encode(s: String?): String? = encode(s, null)

        /** Leaves letters, digits and `_-!.~'()*` (plus [allow]) intact; the rest is UTF-8 %-escaped. */
        @JvmStatic fun encode(s: String?, allow: String?): String? {
            s ?: return null
            val out = StringBuilder(s.length)
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = (b.toInt() and 0xFF).toChar()
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in ALLOWED || (allow != null && c in allow)) out.append(c)
                else out.append('%').append("%02X".format(b.toInt() and 0xFF))
            }
            return out.toString()
        }

        /** %-decoding only: unlike form decoding, '+' stays a plus sign. */
        @JvmStatic fun decode(s: String?): String? = s?.let { URLDecoder.decode(it.replace("+", "%2B"), Charsets.UTF_8) }
    }
}
