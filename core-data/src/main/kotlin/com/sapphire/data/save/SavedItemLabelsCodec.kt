package com.sapphire.data.save

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Encodes/decodes the `SavedItem.labels` map to/from the `labels_json` column. Uses
 * kotlinx.serialization with a lenient config: a malformed or empty JSON object decodes
 * to an empty map rather than throwing, so a partially-written row never breaks the list.
 */
internal object SavedItemLabelsCodec {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun encode(labels: Map<String, String>): String {
        if (labels.isEmpty()) return "{}"
        val obj = buildJsonObject {
            labels.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    fun decode(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            json.decodeFromString(JsonObject.serializer(), raw)
                .jsonObject
                .mapValues { (_, v) -> v.jsonPrimitive.content }
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
