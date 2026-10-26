package com.lagradost.cloudstream4.domain.media

/** Pure-Kotlin folding tables for [MediaAggregator.matchKey] (no java.text in common code). */
internal object TitleFolding {
    private val accents: Map<Char, String> = buildMap {
        fun map(from: String, to: String) = from.forEach { put(it, to); put(it.uppercaseChar(), to) }
        map("àáâãäåāăą", "a"); map("çćĉċč", "c"); map("ďđ", "d"); map("èéêëēĕėęě", "e")
        map("ĝğġģ", "g"); map("ĥħ", "h"); map("ìíîïĩīĭįı", "i"); map("ĵ", "j"); map("ķ", "k")
        map("ĺļľŀł", "l"); map("ñńņňŉ", "n"); map("òóôõöøōŏő", "o"); map("ŕŗř", "r")
        map("śŝşšș", "s"); map("ţťŧț", "t"); map("ùúûüũūŭůűų", "u"); map("ŵ", "w")
        map("ýÿŷ", "y"); map("źżž", "z"); map("œ", "oe"); map("æ", "ae"); put('ß', "ss")
    }

    fun foldAccents(title: String): String = buildString(title.length) {
        title.forEach { char -> append(accents[char] ?: char) }
    }

    /** I, V and X alone are words and initials too often ("I, Robot", "Malcolm X"). */
    val roman = mapOf("ii" to "2", "iii" to "3", "iv" to "4", "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9")
}
