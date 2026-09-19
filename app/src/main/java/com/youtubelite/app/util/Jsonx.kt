package com.youtubelite.app.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Zero-reflection JSON navigation helpers over kotlinx.serialization's JsonElement
 * tree. String-indexed traversal keeps allocations proportional to the paths we
 * touch, NOT to a full DTO graph — YouTube responses are huge and mostly noise.
 */

fun JsonElement?.asObj(): JsonObject? = this as? JsonObject
fun JsonElement?.asArr(): JsonArray? = this as? JsonArray
fun JsonElement?.asPrim(): JsonPrimitive? = this as? JsonPrimitive

fun JsonObject.getS(key: String): String? = (this[key] as? JsonPrimitive)?.content
fun JsonObject.getO(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.getA(key: String): JsonArray? = this[key] as? JsonArray

fun JsonObject.dig(vararg path: String): JsonElement? {
    var cur: JsonElement? = this
    for (p in path) {
        cur = (cur as? JsonObject)?.get(p) ?: return null
    }
    return cur
}

fun JsonObject.digStr(vararg path: String): String? =
    (dig(*path) as? JsonPrimitive)?.content

/** "title": {"runs":[{"text":".."}]} or {"simpleText":".."} */
fun runsText(el: JsonElement?): String? {
    val o = el as? JsonObject ?: return null
    (o["simpleText"] as? JsonPrimitive)?.let { return it.content }
    val runs = o["runs"] as? JsonArray ?: return null
    if (runs.isEmpty()) return null
    return buildString {
        for (r in runs) {
            val t = (r as? JsonObject)?.get("text") as? JsonPrimitive
            append(t?.content ?: "")
        }
    }
}

/**
 * Pre-order DFS collecting every object that directly carries [type] as a key
 * (e.g. "videoRenderer"), preserving document order. Resilient to YouTube
 * layout reshuffles because it never depends on the exact container chain.
 */
fun JsonObject.findAll(type: String): List<JsonObject> {
    val out = ArrayList<JsonObject>(16)
    val stack = ArrayDeque<JsonElement>()
    stack.addLast(this)
    while (stack.isNotEmpty()) {
        when (val el = stack.removeLast()) {
            is JsonObject -> {
                if (el.containsKey(type)) out.add(el)
                val kids = ArrayList<JsonElement>(el.size)
                for (v in el.values) kids.add(v)
                for (i in kids.indices.reversed()) stack.addLast(kids[i])
            }
            is JsonArray -> {
                for (i in el.indices.reversed()) stack.addLast(el[i])
            }
            else -> Unit
        }
    }
    return out
}

/** First "continuationItemRenderer" token found in pre-order (feed pagination). */
fun JsonObject.findNextToken(): String? {
    for (cir in findAll("continuationItemRenderer")) {
        cir.digStr("continuationEndpoint", "continuationCommand", "token")?.let { return it }
        cir.digStr("button", "buttonRenderer", "command", "continuationCommand", "token")
            ?.let { return it }
    }
    for (ncd in findAll("nextContinuationData")) {
        ncd.getS("continuation")?.let { return it }
    }
    return null
}

/** Pre-order search for the first boolean value stored under [key]. */
fun JsonObject.findFirstBool(key: String): Boolean? {
    val stack = ArrayDeque<JsonElement>()
    stack.addLast(this)
    while (stack.isNotEmpty()) {
        when (val el = stack.removeLast()) {
            is JsonObject -> {
                (el[key] as? JsonPrimitive)?.contentOrNull()?.let { return it }
                for (v in el.values) stack.addLast(v)
            }
            is JsonArray -> for (v in el) stack.addLast(v)
            else -> Unit
        }
    }
    return null
}

private fun JsonPrimitive.contentOrNull(): Boolean? = when (content) {
    "true" -> true
    "false" -> false
    else -> null
}

fun formatSeconds(total: Long): String {
    if (total <= 0) return ""
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
