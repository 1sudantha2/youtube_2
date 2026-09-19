package com.ultra.youtube.app.data.innertube

/**
 * Descrambles the `s` parameter of `signatureCipher` URLs.
 *
 * With the `WEB` client profile, InnerTube returns stream URLs whose signature is
 * scrambled; the unscrambling program lives in the player's `base.js`. The transform is
 * always a chain over a char array using at most three primitives:
 *
 *  - `reverse(arr)`            — reverse the array
 *  - `splice(arr, i)`          — drop the first `i` elements
 *  - `swap(arr, i)`            — swap element 0 with element `i`
 *
 * [parse] turns the JS into that ordered list; [apply] runs it. Both are pure functions
 * with no Android or network dependencies, so the whole descrambler is unit-testable
 * against recorded `base.js` snippets.
 */
object SigCipher {

    data class SigFunction(
        /** Name of the helper object holding the three primitives, e.g. `Xqa`. */
        val helperName: String,
        val operations: List<Operation>,
    )

    sealed interface Operation {
        data object Reverse : Operation
        data class Swap(val argIndex: Int) : Operation
        data class Splice(val argIndex: Int) : Operation
    }

    // `sig=function(a){a=a.split("");Xqa.IQ(a,46);…;return a.join("")}`
    private val SIG_FUNC_REGEX = Regex(
        """\b([A-Za-z0-9_$]+)\s*=\s*function\s*\(\s*([A-Za-z$_][A-Za-z0-9$]*)\s*\)\s*\{\s*\2\s*=\s*\2\.split\(""\)\s*;([\s\S]*?)return\s+\2\.join\(""\)""",
    )

    // `Xqa={IQ:function(a,b){…},cQ:function(a,b){…},…}`
    private val HELPER_REGEX = Regex("""([A-Za-z0-9_$]+)\s*=\s*\{([\s\S]*?)\}\s*;""")

    // `Xqa.IQ(a,46)` / `Xqa["IQ"](a,46)`
    private val CALL_REGEX = Regex("""([A-Za-z0-9_$]+)\s*(?:\.\s*([A-Za-z0-9_$]+)|\[\s*"([A-Za-z0-9_$]+)"\s*\])\s*\(\s*([A-Za-z$_][A-Za-z0-9$]*)\s*(?:,\s*([A-Za-z0-9_$]+)\s*)?\)""")

    /** Matches the object entry for one primitive so we can classify it. */
    private fun entryRegex(member: String): Regex = Regex(
        """(?:^|[,{])\s*(?:""" + Regex.escape(member) + """|["']""" + Regex.escape(member) + """["'])\s*:\s*function\s*\([^)]*\)\s*\{([\s\S]*?)\}""",
    )

    /**
     * Extracts the signature program from `base.js`.
     *
     * @return `null` when the file does not contain a recognisable descrambler (in which
     *   case the caller should prefer an `ANDROID` client profile, whose URLs come
     *   pre-signed).
     */
    fun parse(js: String): SigFunction? {
        val match = SIG_FUNC_REGEX.find(js) ?: return null
        val helperName = match.groupValues[1]
        val body = match.groupValues[3]

        val helperBody = HELPER_REGEX.findAll(js)
            .firstOrNull { it.groupValues[1] == helperName }
            ?.groupValues?.getOrNull(2)
            ?: return null

        val operations = ArrayList<Operation>()
        for (call in CALL_REGEX.findAll(body)) {
            val callee = call.groupValues[1]
            if (callee != helperName) continue
            val member = call.groupValues[2].ifBlank { call.groupValues[3] }
            if (member.isBlank()) continue
            val implementation = entryRegex(member).find(helperBody)?.groupValues?.getOrNull(1)
                ?: continue
            val trimmed = implementation.trim()
            when {
                "reverse" in trimmed -> operations += Operation.Reverse
                "splice" in trimmed -> operations += Operation.Splice(argIndexOf(call))
                else -> operations += Operation.Swap(argIndexOf(call))
            }
        }
        if (operations.isEmpty()) return null
        return SigFunction(helperName = helperName, operations = operations)
    }

    /** `1` for `Xqa.IQ(a,46)`, `0` when the call carries no index. */
    private fun argIndexOf(call: MatchResult): Int =
        call.groupValues[5].toIntOrNull() ?: 0

    /** Runs the program over [signature], returning the usable signature. */
    fun apply(signature: String, function: SigFunction): String {
        var chars = signature.toCharArray()
        for (operation in function.operations) {
            chars = when (operation) {
                is Operation.Reverse -> chars.reversedArray()
                is Operation.Splice -> {
                    val from = operation.argIndex.coerceIn(0, chars.size)
                    chars.copyOfRange(from, chars.size)
                }
                is Operation.Swap -> {
                    val index = operation.argIndex % chars.size
                    val tmp = chars[0]
                    chars[0] = chars[index]
                    chars[index] = tmp
                    chars
                }
            }
        }
        return String(chars)
    }

    /**
     * Pulls the `base.js` URL out of a watch page.
     *
     * Both patterns need a literal `"` as the last character before the closing delimiter.
     * A Kotlin raw string cannot end with a quote -- the lexer always takes the longest run
     * of quotes, so `"""…js)""""` would never terminate -- hence the character class `["]`
     * instead of a bare quote.
     */
    fun findPlayerJsUrl(html: String): String? {
        val relative = Regex("""jsUrl["]\s*:\s*["](/s/player/[^"]+/base\.js)""")
            .find(html)?.groupValues?.get(1)
            ?: Regex("""["](/s/player/[^"]+/base\.js)["]""")
                .find(html)?.groupValues?.get(1)
            ?: return null
        return "https://www.youtube.com$relative"
    }

    /** Rebuilds a stream URL with the descrambled signature in the slot YouTube asked for. */
    fun sign(baseUrl: String, signature: String, paramName: String): String {
        val separator = if ('?' in baseUrl) '&' else '?'
        return baseUrl + separator + paramName + "=" + signature
    }
}
