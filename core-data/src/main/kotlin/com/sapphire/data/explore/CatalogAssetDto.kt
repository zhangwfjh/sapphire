package com.sapphire.data.explore

import kotlinx.serialization.Serializable

@Serializable
data class CatalogAssetDto(
    val version: Int = 1,
    val domains: List<CatalogDomainDto> = emptyList(),
)

@Serializable
data class CatalogDomainDto(
    val id: String,
    val name: String,
    val feeds: List<CatalogFeedDto> = emptyList(),
)

@Serializable
data class CatalogFeedDto(
    val title: String,
    val url: String,
    val kind: String = "rss",
    val description: String? = null,
    val language: String? = null,
)
