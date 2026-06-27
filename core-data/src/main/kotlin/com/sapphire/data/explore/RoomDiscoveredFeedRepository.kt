package com.sapphire.data.explore

import com.sapphire.data.db.DiscoveredFeedDao
import com.sapphire.data.db.DiscoveredFeedEntity
import com.sapphire.domain.explore.DiscoveredFeedRepository
import com.sapphire.domain.model.SourceKind
import com.sapphire.domain.util.normalizeSourceUrl
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Room-backed DiscoveredFeedRepository. PK = SHA-256 of the normalized URL so the same
 * feed discovered via different paths dedupes. On re-subscribe (id already exists), bumps
 * subscribe_count instead of overwriting.
 */
class RoomDiscoveredFeedRepository @Inject constructor(
    private val dao: DiscoveredFeedDao,
) : DiscoveredFeedRepository {

    override suspend fun record(
        title: String,
        url: String,
        kind: SourceKind,
        description: String?,
        domainHint: String?,
        language: String?,
    ) = withContext(Dispatchers.IO) {
        // Guarantee a schemeful absolute URL for storage: the discovered rail hands this
        // back to SourceRepository.addSource, whose fetchers require a scheme (OkHttp
        // HttpUrl throws on schemeless input -> PersistentFailure -> FAILED forever).
        // The id stays the hash of the normalized (scheme-dropped) URL so cross-scheme
        // dedup is preserved.
        val schemeful = if (url.contains("://")) url else "https://$url"
        val normalized = normalizeSourceUrl(schemeful).ifBlank { return@withContext }
        val id = sha256(normalized)
        if (dao.exists(id)) {
            dao.incrementSubscribeCount(id)
        } else {
            dao.upsert(
                DiscoveredFeedEntity(
                    id = id,
                    title = title,
                    url = schemeful,
                    kind = kind,
                    description = description,
                    domainHint = domainHint,
                    language = language,
                    discoveredAt = System.currentTimeMillis(),
                    subscribeCount = 1,
                ),
            )
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
