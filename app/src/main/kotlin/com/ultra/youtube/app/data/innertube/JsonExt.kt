package com.ultra.youtube.app.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Null-tolerant accessors for InnerTube responses.
 *
 * YouTube reshapes these payloads frequently and omits whole subtrees; a `!!` or a
 * strict `@Serializable` model would turn a cosmetic change into a crash. Everything
 * here degrades to `null` instead.
 */
internal object JsonExt {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    operator fun JsonElement?.get(key: String): JsonElement? =
        (this as? JsonObject)?.get(key)

    operator fun JsonElement?.get(index: Int): JsonElement? =
        (this as? JsonArray)?.getOrNull(index)

    fun JsonElement?.string(): String? = when (this) {
        is JsonPrimitive -> if (isString) content else content
        else -> null
    }

    fun JsonElement?.int(default: Int = 0): Int =
        this?.string()?.toIntOrNull() ?: default

    fun JsonElement?.long(default: Long = 0L): Long =
        this?.string()?.toLongOrNull() ?: default

    fun JsonElement?.bool(default: Boolean = false): Boolean = when (this) {
        is JsonPrimitive -> content.toBooleanStrictOrNull() ?: default
        else -> default
    }

    fun JsonElement?.array(): List<JsonElement> =
        (this as? JsonArray)?.toList() ?: emptyList()

    fun JsonElement?.obj(): JsonObject? = this as? JsonObject

    /** Walks `a -> b -> c`, returning the first missing node as `null`. */
    fun JsonElement?.path(vararg keys: String): JsonElement? {
        var node: JsonElement? = this
        for (key in keys) {
            node = node[key] ?: return null
            if (node is JsonNull) return null
        }
        return node
    }

    /** Adds/overrides `key` on a [JsonObject], returning a new object. */
    fun JsonObject.with(key: String, value: JsonElement): JsonObject {
        val map = LinkedHashMap<String, JsonElement>(this)
        map[key] = value
        return JsonObject(map)
    }

    /** Builds a [JsonObject], dropping null values so the payload stays small. */
    inline fun jsonObj(vararg pairs: Pair<String, JsonElement?>): JsonObject {
        val map = LinkedHashMap<String, JsonElement>(pairs.size)
        for ((key, value) in pairs) {
            if (value != null && value !is JsonNull) map[key] = value
        }
        return JsonObject(map)
    }

    fun str(value: String?): JsonElement? = value?.let { JsonPrimitive(it) }
    fun num(value: Int): JsonElement = JsonPrimitive(value)
    fun num(value: Long): JsonElement = JsonPrimitive(value)
    fun bool(value: Boolean): JsonElement = JsonPrimitive(value)
    fun arr(vararg items: JsonElement): JsonElement = JsonArray(items.toList())
}
