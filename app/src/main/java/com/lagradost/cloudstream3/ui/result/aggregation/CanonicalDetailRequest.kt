package com.lagradost.cloudstream3.ui.result.aggregation

import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.ui.search.aggregation.AggregatedSearchResponse
import com.lagradost.cloudstream4.domain.media.*
import java.security.MessageDigest

/** Small, persistable navigation context. Contains page URLs, never episode/link payloads. */
data class DetailBinding(val provider: ProviderKey, val url: String, val editions: Set<String> = emptySet()) {
    fun mediaBinding() = ProviderBinding(provider, url)
    val scopedSeason: Int? get() = editions.singleOrNull()?.removePrefix("season:")
        ?.takeIf { editions.single().startsWith("season:") }?.toIntOrNull()?.takeIf { it >= 0 }
}

data class CanonicalDetailRequest(
    val identity: String,
    val title: String,
    val year: Int?,
    val kind: MediaKind,
    val externalIds: Set<ExternalMediaId>,
    val bindings: List<DetailBinding>,
    val edition: Set<String> = emptySet(),
) {
    companion object {
        fun from(card: AggregatedSearchResponse) = CanonicalDetailRequest(
            CanonicalIdentity.key(card.media), card.name, card.media.year, card.media.kind,
            card.media.externalIds, card.media.bindings.map {
                DetailBinding(it.provider, it.url, card.media.bindingEditions[it].orEmpty())
            },
            card.media.bindingEditions.values.flatten().filterNot { it.startsWith("season:") }.toSet(),
        )
    }
}

/** Alias bookmarks/resume URLs back to a canonical context, also after process death.
 * Pin the first canonical identity so learning a TMDB ID does not reset new progress. */
object CanonicalDetailStore {
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun alias(apiName: String, url: String) = "canonical_detail_alias/${digest("$apiName\n$url")}"
    private fun idAlias(id: ExternalMediaId, edition: Set<String>) =
        "canonical_detail_id/${id.namespace}/${id.value}/${digest(edition.sorted().joinToString(","))}"
    private fun read(identity: String): CanonicalDetailRequest? =
        CloudStreamApp.getKey("canonical_detail/${digest(identity)}")

    @Synchronized
    fun pin(request: CanonicalDetailRequest): CanonicalDetailRequest {
        val previous = request.bindings.mapNotNull { find(it.provider.name, it.url) } +
            request.externalIds.mapNotNull { id ->
                CloudStreamApp.getKey<String>(idAlias(id, request.edition))?.let(::read)
            }
        val existing = previous.firstOrNull { previous ->
                previous.edition == request.edition &&
                    MediaAggregator.family(previous.kind) == MediaAggregator.family(request.kind) &&
                    (previous.year == null || request.year == null || previous.year == request.year ||
                        previous.externalIds.intersect(request.externalIds).isNotEmpty()) &&
                    previous.externalIds.none { old -> request.externalIds.any {
                        it.namespace == old.namespace && it != old
                    } }
        }
        return request.copy(identity = existing?.identity ?: request.identity,
            bindings = (request.bindings + existing?.bindings.orEmpty()).distinctBy { it.mediaBinding() })
    }

    @Synchronized
    fun remember(request: CanonicalDetailRequest): CanonicalDetailRequest {
        val pinned = request.copy(identity = pin(request).identity)
        CloudStreamApp.setKey("canonical_detail/${digest(pinned.identity)}", pinned)
        pinned.bindings.forEach {
            CloudStreamApp.setKey(alias(it.provider.name, it.url), pinned.identity)
        }
        pinned.externalIds.forEach { CloudStreamApp.setKey(idAlias(it, pinned.edition), pinned.identity) }
        return pinned
    }

    fun find(apiName: String, url: String): CanonicalDetailRequest? {
        val identity = CloudStreamApp.getKey<String>(alias(apiName, url)) ?: return null
        return read(identity)
    }
}
