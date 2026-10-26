package com.lagradost.cloudstream4.aggregation

import com.lagradost.cloudstream4.domain.media.CanonicalIdentity
import com.lagradost.cloudstream4.domain.media.CanonicalMedia
import com.lagradost.cloudstream4.domain.media.ExternalMediaId
import com.lagradost.cloudstream4.domain.media.MediaAggregator
import com.lagradost.cloudstream4.domain.media.MediaKind
import com.lagradost.cloudstream4.domain.media.ProviderBinding
import com.lagradost.cloudstream4.domain.media.ProviderKey
import java.security.MessageDigest
import kotlin.math.abs

/** Small, persistable navigation context. Contains page URLs, never episode/link payloads. */
data class DetailBinding(
    val provider: ProviderKey,
    val url: String,
    val editions: Set<String> = emptySet(),
    /** [ProviderIds] value; absent on contexts saved before stable provider IDs. */
    val providerId: String? = null,
) {
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
    /** Every title observed for the work while matching; used to verify loaded detail pages. */
    val aliases: Set<String> = emptySet(),
) {
    companion object {
        fun from(media: CanonicalMedia, title: String = media.title,
            registry: SourceRegistry = AggregationPlatform.registry): CanonicalDetailRequest {
            val ids = runCatching { registry.providers() }.getOrDefault(emptyList()).associate { it.key to it.id }
            return CanonicalDetailRequest(
                CanonicalIdentity.key(media), title, media.year, media.kind, media.externalIds,
                media.bindings.map {
                    DetailBinding(it.provider, it.url, media.bindingEditions[it].orEmpty(), ids[it.provider])
                },
                media.bindingEditions.values.flatten().filterNot { it.startsWith("season:") }.toSet(),
                (media.titles + title).filter { it.isNotBlank() }.toSet(),
            )
        }
    }
}

/** Alias bookmarks/resume URLs back to a canonical context, also after process death.
 * Pin the first canonical identity so learning a TMDB ID does not reset new progress. */
class CanonicalDetailStore(private val store: () -> KeyValueStore = { AggregationPlatform.store }) {
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun alias(apiName: String, url: String) = "canonical_detail_alias/${digest("$apiName\n$url")}"
    private fun idAlias(id: ExternalMediaId, edition: Set<String>) =
        "canonical_detail_id/${id.namespace}/${id.value}/${digest(edition.sorted().joinToString(","))}"
    private fun read(identity: String): CanonicalDetailRequest? =
        store().get("canonical_detail/${digest(identity)}", CanonicalDetailRequest::class.java)

    @Synchronized
    fun pin(request: CanonicalDetailRequest): CanonicalDetailRequest {
        val previous = request.bindings.mapNotNull { find(it.provider.name, it.url) } +
            request.externalIds.mapNotNull { id ->
                store().get(idAlias(id, request.edition), String::class.java)?.let(::read)
            }
        val existing = previous.firstOrNull { previous ->
                previous.edition == request.edition &&
                    MediaAggregator.family(previous.kind) == MediaAggregator.family(request.kind) &&
                    (previous.year == null || request.year == null || abs(previous.year - request.year) <= 1 ||
                        previous.externalIds.intersect(request.externalIds).isNotEmpty()) &&
                    previous.externalIds.none { old -> request.externalIds.any {
                        it.namespace == old.namespace && it != old
                    } }
        }
        return request.copy(identity = existing?.identity ?: request.identity,
            bindings = (request.bindings + existing?.bindings.orEmpty()).distinctBy { it.mediaBinding() },
            aliases = request.aliases + existing?.aliases.orEmpty())
    }

    @Synchronized
    fun remember(request: CanonicalDetailRequest): CanonicalDetailRequest {
        val pinned = request.copy(identity = pin(request).identity)
        store().set("canonical_detail/${digest(pinned.identity)}", pinned)
        pinned.bindings.forEach {
            store().set(alias(it.provider.name, it.url), pinned.identity)
        }
        pinned.externalIds.forEach { store().set(idAlias(it, pinned.edition), pinned.identity) }
        return pinned
    }

    fun find(apiName: String, url: String): CanonicalDetailRequest? {
        val identity = store().get(alias(apiName, url), String::class.java) ?: return null
        return read(identity)
    }

    companion object Default {
        private val instance = CanonicalDetailStore()
        fun pin(request: CanonicalDetailRequest) = instance.pin(request)
        fun remember(request: CanonicalDetailRequest) = instance.remember(request)
        fun find(apiName: String, url: String) = instance.find(apiName, url)
    }
}
