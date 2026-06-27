package com.sapphire.data.explore

import kotlinx.serialization.json.Json
import java.io.InputStream

/**
 * Parses the bundled explore catalog from an InputStream (AssetManager in production,
 * test resource stream in tests). Returns an empty CatalogAssetDto on corrupt/missing
 * asset rather than throwing — Explore degrades to the discovered rail only.
 */
class CatalogAssetParser(private val json: Json) {

    fun parse(input: InputStream): CatalogAssetDto =
        runCatching {
            json.decodeFromString(CatalogAssetDto.serializer(), input.bufferedReader().use { it.readText() })
        }.getOrDefault(CatalogAssetDto())
}
