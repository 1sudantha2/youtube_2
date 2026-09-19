package app.you.tube.core.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * String-indexed navigation helpers over kotlinx.serialization JSON trees.
 * Responses are walked key-by-key, so unknown/extra fields never allocate
 * model objects (minimal GC churn) and schema drift degrades gracefully
 * (null-safe navigation instead of parse crashes).
 */

fun JsonObject.pathEl(vararg keys: String): JsonElement? {
    var cur: JsonElement? = this
    for (key in keys) {
        cur = (cur as? JsonObject)?.get(key) ?: return null
    }
    return cur
}

fun JsonObject.pathObj(vararg keys: String): JsonObject? = pathEl(*keys) as? JsonObject

fun JsonObject.pathArr(vararg keys: String): JsonArray? = pathEl(*keys) as? JsonArray

fun JsonObject.pathStr(vararg keys: String): String? =
    (pathEl(*keys) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

fun JsonObject.pathBool(vararg keys: String): Boolean = pathStr(*keys) == "true"

fun JsonObject.pathInt(vararg keys: String): Int? = pathStr(*keys)?.toIntOrNull()

fun JsonObject.pathLong(vararg keys: String): Long? = pathStr(*keys)?.toLongOrNull()

fun JsonElement?.asPrimitiveString(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

fun JsonArray.objects(): List<JsonObject> = this.filterIsInstance<JsonObject>()

/**
 * Breadth-first deep scan of a JSON tree. Used for the few fields whose
 * containing renderer moves between response shapes (subscribe state, the
 * comment section identifier). Bounded to avoid pathological payloads.
 */
fun JsonObject.deepFind(predicate: (JsonObject) -> Boolean): JsonObject? {
    val queue = ArrayDeque<JsonElement>()
    queue.add(this)
    var steps = 0
    while (queue.isNotEmpty() && steps < 400_000) {
        when (val e = queue.removeFirst()) {
            is JsonObject -> {
                if (predicate(e)) return e
                e.values.forEach { queue.add(it) }
            }
            is JsonArray -> e.forEach { queue.add(it) }
            else -> Unit
        }
        steps++
    }
    return null
}
