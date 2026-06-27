package com.sapphire.data.save

import com.sapphire.data.save.SavedItemLabelsCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S07 label-map JSON codec round-trip + malformed-input safety. Pure unit test; the codec
 * is what backs `SavedItem.labels` persistence.
 */
class SavedItemLabelsCodecTest {

    @Test
    fun `empty map encodes to empty json object`() {
        assertEquals("{}", SavedItemLabelsCodec.encode(emptyMap()))
    }

    @Test
    fun `round-trips a non-empty label map preserving order-agnostic equality`() {
        val labels = mapOf("topic" to "AI", "priority" to "high", "read" to "later")
        val decoded = SavedItemLabelsCodec.decode(SavedItemLabelsCodec.encode(labels))
        assertEquals(labels, decoded)
    }

    @Test
    fun `decode null or blank yields empty map`() {
        assertTrue(SavedItemLabelsCodec.decode(null).isEmpty())
        assertTrue(SavedItemLabelsCodec.decode("").isEmpty())
        assertTrue(SavedItemLabelsCodec.decode("   ").isEmpty())
    }

    @Test
    fun `decode malformed json yields empty map instead of throwing`() {
        assertTrue(SavedItemLabelsCodec.decode("{not json").isEmpty())
    }

    @Test
    fun `decode ignores unknown keys`() {
        val raw = """{"a":"1","b":"2"}"""
        assertEquals(mapOf("a" to "1", "b" to "2"), SavedItemLabelsCodec.decode(raw))
    }
}
