package io.github.sudantha.youtubelite

import io.github.sudantha.youtubelite.data.BoundedInputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class BoundedInputStreamTest {
    @Test
    fun `reads up to limit then reports EOF`() {
        val stream = BoundedInputStream(ByteArrayInputStream("hello world".toByteArray()), 5)
        val text = stream.reader().use { it.readText() }
        assertEquals("hello", text)
    }

    @Test
    fun `unbounded source cannot hang or exceed the limit`() {
        val source = object : java.io.InputStream() {
            override fun read(): Int = 'a' // infinite source
        }
        val stream = BoundedInputStream(source, 100)
        val text = stream.reader().use { it.readText() }
        assertEquals(100, text.length)
    }

    @Test
    fun `buffered reads respect the limit`() {
        val bytes = ByteArray(1000) { 65 }
        val stream = BoundedInputStream(ByteArrayInputStream(bytes), 700)
        val buffer = ByteArray(512)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read == -1) break
            total += read
        }
        assertEquals(700, total)
    }
}
