package com.sapphire.data.source

import com.sapphire.data.db.CategoryEntity
import com.sapphire.data.db.SourceDao
import com.sapphire.data.db.SourceEntity
import com.sapphire.data.db.TopicEntity
import com.sapphire.domain.model.Category
import com.sapphire.domain.model.HealthState
import com.sapphire.domain.model.Source
import com.sapphire.domain.model.SourceKind
import com.sapphire.domain.source.SourceCounts
import com.sapphire.domain.source.SourceFolderNode
import com.sapphire.domain.source.SourceNode
import com.sapphire.domain.source.SourceTreeNode
import com.sapphire.domain.source.SourceRepository
import com.sapphire.domain.source.SourceRepository.Outcome
import com.sapphire.domain.explore.DiscoveredFeedRepository
import com.sapphire.domain.util.IdGenerator
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Room-backed [SourceRepository]. Assembles the single-level tree by combining categories
 * and sources; writes run on IO. [moveSource]/[addSource]/[updateSource] surface
 * unique-index conflicts via [Outcome.Conflict] instead of throwing — the caller checks
 * the target row in advance and reports the existing category.
 */
class RoomSourceRepository @Inject constructor(
    private val dao: SourceDao,
    private val feedDao: com.sapphire.data.db.FeedDao,
    private val ids: IdGenerator,
    private val discovered: DiscoveredFeedRepository,
) : SourceRepository {
    override fun observeTree(): Flow<List<SourceFolderNode>> =
        combine(
            dao.observeCategories(),
            dao.observeSources(),
            feedDao.observeCategoryCounts(),
            feedDao.observeSourceCounts(),
        ) { catRows, sourceRows, catCountRows, srcCountRows ->
            val srcCounts = srcCountRows.associateBy { it.sourceId }

            fun srcNode(s: SourceEntity) = SourceNode(s.toDomain(), srcCounts[s.id].toCounts())

            // Flat model: every persisted category is L1. Virtual domain sub-folders are
            // computed here (presentation-only) — sources sharing a registered domain host
            // (≥2) group into a synthetic child folder; singletons stay on the L1. Because
            // this is view-time, move/remove auto-recompenses and a singleton auto-flattens.
            catRows.map { l1 ->
                val all = (sourceRows.filter { it.categoryId == l1.id })
                val byDomain = all.groupBy { hostOf(it.url) }
                val direct = mutableListOf<SourceEntity>()
                val children = mutableListOf<SourceFolderNode>()
                var childSort = 0
                for ((host, srcs) in byDomain) {
                    if (host != null && srcs.size >= 2) {
                        children += SourceFolderNode(
                            category = syntheticDomainCategory(l1, host, childSort++),
                            sources = srcs.map { srcNode(it) },
                        )
                    } else {
                        direct += srcs
                    }
                }
                // Name-sorted interleaving of direct sources and domain groups, so a
                // group sits among singletons by name rather than lumping at the end.
                val leaves = direct.map { SourceTreeNode.Leaf(srcNode(it)) }
                val groups = children.map { SourceTreeNode.Group(it) }
                val entries = (leaves + groups).sortedBy { it.name.lowercase() }
                SourceFolderNode(
                    category = l1.toDomain(),
                    sources = direct.map { srcNode(it) },
                    children = children,
                    entries = entries,
                )
            }
        }

    override fun observeHasTopic(): Flow<Boolean> = dao.observeHasTopic()

    override suspend fun currentTopicId(): String? = withContext(Dispatchers.IO) {
        dao.firstTopicId()
    }

    override suspend fun addSource(
        categoryId: String,
        url: String,
        title: String,
        kind: SourceKind,
    ): Outcome = withContext(Dispatchers.IO) {
        val rowId = dao.insertSource(newSource(categoryId, url, title, kind))
        if (rowId == -1L) {
            Outcome.Conflict(categoryId)
        } else {
            runCatching {
                discovered.record(
                    title = title,
                    url = url,
                    kind = kind,
                    description = null,
                    domainHint = null,
                    language = null,
                )
            }
            Outcome.Ok
        }
    }

    override suspend fun updateSource(
        id: String,
        title: String,
        url: String,
        kind: SourceKind,
        enabled: Boolean,
    ): Outcome = withContext(Dispatchers.IO) {
        val cat = dao.sourceCategoryId(id) ?: ""
        if (dao.urlTakenInCategory(cat, url, id)) {
            Outcome.Conflict(cat)
        } else {
            dao.updateSource(id, title, url, kind, enabled)
            Outcome.Ok
        }
    }

    override suspend fun moveSource(id: String, toCategoryId: String): Outcome = withContext(Dispatchers.IO) {
        val url = dao.sourceUrl(id) ?: return@withContext Outcome.Ok
        if (dao.urlTakenInCategory(toCategoryId, url, id)) {
            Outcome.Conflict(toCategoryId)
        } else {
            dao.moveSource(id, toCategoryId)
            Outcome.Ok
        }
    }

    override suspend fun deleteSource(id: String) = withContext(Dispatchers.IO) {
        dao.deleteSource(id)
    }

    override suspend fun addCategory(topicId: String, name: String): String = withContext(Dispatchers.IO) {
        val sortOrder = dao.maxSortOrder(topicId) + 1
        val id = ids.uuid()
        dao.insertCategory(
            CategoryEntity(
                id = id,
                topicId = topicId,
                level = 1,
                parentId = null,
                name = name,
                sortOrder = sortOrder,
            ),
        )
        id
    }

    override suspend fun renameCategory(id: String, name: String) = withContext(Dispatchers.IO) {
        dao.renameCategory(id, name)
    }

    override suspend fun deleteCategory(id: String) = withContext(Dispatchers.IO) {
        dao.deleteCategory(id)
    }

    override suspend fun importFromOpml(stream: InputStream): Int = withContext(Dispatchers.IO) {
        val parsed = OpmlParser.parse(stream)
        // Merge into the first existing topic (so re-imports and the seed topic share a
        // space); create one only if the DB is empty.
        val topicId = dao.firstTopicId() ?: run {
            val id = ids.uuid()
            dao.insertTopic(TopicEntity(id, "Imported Sources", System.currentTimeMillis()))
            id
        }

        val seenUrls = mutableSetOf<String>()  // intra-batch dedup
        var inserted = 0
        for (pc in parsed.categories) {
            val l1Id = findOrCreateCategory(topicId, pc.name, parentId = null, level = 1)
            for (s in pc.sources) {
                if (!seenUrls.add(s.url)) continue          // dup within this import
                if (dao.urlExistsAnywhere(s.url)) continue   // already present anywhere
                val rowId = dao.insertSource(
                    SourceEntity(
                        id = ids.uuid(),
                        categoryId = l1Id,
                        topicId = topicId,
                        kind = SourceKind.RSS,
                        url = s.url,
                        title = s.title,
                        enabled = true,
                        healthState = HealthState.OK,
                    )
                )
                if (rowId > 0L) inserted++
            }
        }
        inserted
    }

    /** Find a category by (topic, name, parent) or create it. Powers same-name merge + L2. */
    private suspend fun findOrCreateCategory(
        topicId: String,
        name: String,
        parentId: String?,
        level: Int,
    ): String {
        dao.findCategoryIdByName(topicId, name, parentId)?.let { return it }
        val sortOrder = dao.maxSortOrderUnder(topicId, parentId) + 1
        val id = ids.uuid()
        dao.insertCategory(
            CategoryEntity(
                id = id,
                topicId = topicId,
                level = level,
                parentId = parentId,
                name = name,
                sortOrder = sortOrder,
            ),
        )
        return id
    }

    override suspend fun mergeCategory(fromId: String, toId: String): Int =
        withContext(Dispatchers.IO) { dao.mergeCategoryInto(fromId, toId) }

    override suspend fun exportToOpml(): String = withContext(Dispatchers.IO) {
        val tree = observeTree().first()
        OpmlSerializer.serialize(tree)
    }

    private suspend fun newSource(categoryId: String, url: String, title: String, kind: SourceKind): SourceEntity =
        SourceEntity(
            id = ids.uuid(),
            categoryId = categoryId,
            topicId = dao.topicIdOfCategory(categoryId) ?: dao.firstTopicId() ?: "",
            kind = kind,
            url = url,
            title = title,
            enabled = true,
            healthState = HealthState.OK,
        )
}

/** Registers-domain host of a URL, lowercased, "www." stripped. null if unparseable. */
private fun hostOf(url: String): String? = runCatching {
    val h = java.net.URI(url).host ?: return null
    h.lowercase().removePrefix("www.")
}.getOrNull()

/**
 * Builds a synthetic (non-persisted) L2 [Category] for a domain sub-folder. Its id is
 * prefixed `domain:` so the UI can detect virtual folders and route filtering by the
 * contained source IDs rather than by a (nonexistent) category row.
 */
private fun syntheticDomainCategory(
    l1: CategoryEntity,
    host: String,
    sortOrder: Int,
): Category = Category(
    id = "domain:${l1.id}:$host",
    topicId = l1.topicId,
    level = 2,
    parentId = l1.id,
    name = host,
    sortOrder = sortOrder,
)

private fun CategoryEntity.toDomain() = Category(
    id = id,
    topicId = topicId,
    level = level,
    parentId = parentId,
    name = name,
    sortOrder = sortOrder,
)

private fun SourceEntity.toDomain() = Source(
    id = id,
    categoryId = categoryId,
    topicId = topicId,
    kind = kind,
    url = url,
    title = title,
    configJson = configJson,
    enabled = enabled,
    healthState = healthState,
    lastFetchedAt = lastFetchedAt,
    lastErrorAt = lastErrorAt,
)

private fun com.sapphire.data.db.SourceCount?.toCounts() =
    SourceCounts(this?.unread ?: 0, this?.total ?: 0)
