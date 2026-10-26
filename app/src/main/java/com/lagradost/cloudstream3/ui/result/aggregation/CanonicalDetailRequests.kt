package com.lagradost.cloudstream3.ui.result.aggregation

import com.lagradost.cloudstream3.ui.search.aggregation.AggregatedSearchResponse
import com.lagradost.cloudstream4.aggregation.CanonicalDetailRequest

/** Navigation context of an aggregated search card, with its canonical display title. */
fun CanonicalDetailRequest.Companion.from(card: AggregatedSearchResponse) =
    CanonicalDetailRequest.from(card.media, card.name)
