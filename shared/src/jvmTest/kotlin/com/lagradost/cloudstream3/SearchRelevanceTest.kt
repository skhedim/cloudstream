package com.lagradost.cloudstream3

import com.lagradost.cloudstream4.aggregation.CanonicalSearchCard
import com.lagradost.cloudstream4.aggregation.SearchRelevance
import com.lagradost.cloudstream4.domain.media.*
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchRelevanceTest {
    private fun card(title: String, vararg providers: String, kind: MediaKind = MediaKind.Movie) = CanonicalSearchCard(
        CanonicalMedia(title.hashCode().toLong(), title, null, kind, emptySet(),
            providers.map { ProviderBinding(ProviderKey(it, "https://$it.example"), "/$title") }, emptySet(),
            titles = setOf(title)), emptyList())

    @Test fun theExactTitleLeadsWhicheverProviderAnsweredFirst() {
        // Order as interleaved from the first providers to answer.
        val cards = listOf(card("Arsène Lupin", "A"), card("Lupin III", "B"), card("Lupin", "A", kind = MediaKind.Series),
            card("Les Aventures d'Arsène Lupin", "B"), card("Lupin", "A", "B", "C", "D", kind = MediaKind.Series))
        assertEquals(listOf("Lupin" to 4, "Lupin" to 1, "Lupin III" to 1, "Arsène Lupin" to 1, "Les Aventures d'Arsène Lupin" to 1),
            SearchRelevance.rank("lupin", cards).map { it.media.title to it.media.bindings.size })
    }

    @Test fun accentsPunctuationAndSequelNumbersDoNotHideTheExactTitle() {
        assertEquals(SearchRelevance.EXACT, SearchRelevance.tier("Dune Deuxieme partie", listOf("Dune : Deuxième partie")))
        assertEquals(SearchRelevance.EXACT, SearchRelevance.tier("gladiator 2", listOf("Gladiator II")))
        assertEquals(SearchRelevance.PREFIX, SearchRelevance.tier("Dune", listOf("Dune : Deuxième partie")))
        assertEquals(SearchRelevance.CONTAINS, SearchRelevance.tier("lion", listOf("Le Roi Lion")))
        assertEquals(SearchRelevance.OTHER, SearchRelevance.tier("lion", listOf("Lionel")))
        // No query, or an empty one: the providers' order is kept.
        val cards = listOf(card("B", "x"), card("A", "y"))
        assertEquals(cards, SearchRelevance.rank(null, cards))
        assertEquals(cards, SearchRelevance.rank(" ", cards))
    }
}
