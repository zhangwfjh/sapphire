package com.sapphire.data.feed

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the RFC-822 / ISO-8601 date parsers (they use only JDK
 * SimpleDateFormat, no Android deps). Guards the regression where zzz alone failed
 * numeric offsets like "+0000" emitted by hnrss/BBC.
 */
class FeedParsingDateTest {

    @Test fun rfc822_numeric_offset() {
        val t = parseRfc822("Wed, 24 Jun 2026 03:17:47 +0000")
        assertNotNull(t); assertTrue(t != null && t > 0)
    }

    @Test fun rfc822_colon_offset() {
        val t = parseRfc822("Wed, 24 Jun 2026 03:17:47 +00:00")
        assertNotNull(t); assertTrue(t != null && t > 0)
    }

    @Test fun rfc822_named_zone() {
        val t = parseRfc822("Wed, 02 Oct 2024 13:37:00 GMT")
        assertNotNull(t); assertTrue(t != null && t > 0)
    }

    @Test fun iso8601_z() {
        val t = parseIso8601("2024-10-02T13:37:00Z")
        assertNotNull(t); assertTrue(t != null && t > 0)
    }
}
