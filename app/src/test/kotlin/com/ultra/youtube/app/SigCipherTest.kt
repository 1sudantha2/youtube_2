package com.ultra.youtube.app

import com.ultra.youtube.app.data.innertube.SigCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Signature descrambling.
 *
 * YouTube rotates the helper names in `base.js`, so the tests use synthetic snippets that
 * keep the *structure* (a split/join function driving a helper object of primitives) while
 * the names are arbitrary. That is exactly the invariance the parser relies on.
 */
class SigCipherTest {

    private val js = """
        var sig=function(a){a=a.split("");Qqa.rQ(a,3);Qqa.swap(a,12);Qqa.cut(a,2);return a.join("")};
        var Qqa={rQ:function(a){a.reverse()},swap:function(a,b){var c=a[0];a[0]=a[b%a.length];a[b%a.length]=c},cut:function(a,b){a.splice(0,b)}};
    """.trimIndent()

    @Test
    fun `parses the helper name and the ordered operations`() {
        val function = SigCipher.parse(js)
        assertNotNull(function)
        assertEquals("Qqa", function!!.helperName)
        assertEquals(
            listOf(
                SigCipher.Operation.Reverse,
                SigCipher.Operation.Swap(12),
                SigCipher.Operation.Splice(2),
            ),
            function.operations,
        )
    }

    @Test
    fun `applies the operations in order`() {
        val function = SigCipher.parse(js)!!
        val signature = "ABCDEFGHIJKLMN"
        // reverse -> NMLKJIHGFEDCBA
        // swap(12) -> element0 <-> element12 -> CMLKJIHGFEDNBA
        // splice(2) -> LKJIHGFEDNBA
        assertEquals("LKJIHGFEDNBA", SigCipher.apply(signature, function))
    }

    @Test
    fun `handles bracket-access helper calls`() {
        val bracketJs = """
            var sig=function(a){a=a.split("");Bb["rev"](a);Bb["sw"](a,5);return a.join("")};
            var Bb={rev:function(a){a.reverse()},sw:function(a,b){var c=a[0];a[0]=a[b%a.length];a[b%a.length]=c}};
        """.trimIndent()
        val function = SigCipher.parse(bracketJs)
        assertNotNull(function)
        assertEquals(listOf(SigCipher.Operation.Reverse, SigCipher.Operation.Swap(5)), function!!.operations)
    }

    @Test
    fun `returns null when there is no descrambler`() {
        assertNull(SigCipher.parse("var nothing=function(a){return a};"))
        assertNull(SigCipher.parse(""))
    }

    @Test
    fun `finds the player js url in a watch page`() {
        val html = """{"PLAYER_CONFIG":{"jsUrl":"/s/player/abcdef12/player_ias.vflset/en_US/base.js"}}"""
        assertEquals(
            "https://www.youtube.com/s/player/abcdef12/player_ias.vflset/en_US/base.js",
            SigCipher.findPlayerJsUrl(html),
        )
        assertNull(SigCipher.findPlayerJsUrl("<html>no player here</html>"))
    }

    @Test
    fun `sign appends the parameter with the right separator`() {
        assertEquals(
            "https://x/videoplayback?id=1&signature=ABC",
            SigCipher.sign("https://x/videoplayback?id=1", "ABC", "signature"),
        )
        assertEquals(
            "https://x/videoplayback?sig=ABC",
            SigCipher.sign("https://x/videoplayback", "ABC", "sig"),
        )
    }

    @Test
    fun `apply is stable for an empty operation list`() {
        val function = SigCipher.SigFunction("X", listOf(SigCipher.Operation.Reverse, SigCipher.Operation.Reverse))
        assertEquals("abcdef", SigCipher.apply("abcdef", function))
        assertTrue(SigCipher.apply("a", function) == "a")
    }
}
