package com.example.easy_billing.time

import com.example.easy_billing.util.WallClockParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import java.util.TimeZone
import org.junit.Test

/**
 * Phase 6 unit tests for the backend time parser (the #85 round-trip fix).
 * Pure JVM — runs under `./gradlew test`.
 */
class WallClockParserTest {

    private val IST = TimeZone.getTimeZone("Asia/Kolkata")
    private val UTC = TimeZone.getTimeZone("UTC")

    /** An event string is a shop-local wall clock and must be parsed in the shop TZ. */
    @Test fun event_string_parsed_in_shop_timezone() {
        // 2026-06-24 00:10:00 IST  ==  1782240000000 ms
        val ms = WallClockParser.parse("2026-06-24T00:10:00", IST)
        assertEquals(1782240000000L, ms)
    }

    /** Same wall-clock text in UTC is a DIFFERENT instant — proves zone matters. */
    @Test fun same_text_different_zone_differs_by_offset() {
        val ist = WallClockParser.parse("2026-06-24 00:10:00", IST)!!
        val utc = WallClockParser.parse("2026-06-24 00:10:00", UTC)!!
        assertEquals(330L * 60 * 1000, utc - ist)   // IST is 5h30m ahead of UTC
    }

    /** A cursor (updated_at) is stored UTC and must be parsed in UTC. */
    @Test fun cursor_string_parsed_in_utc() {
        val ms = WallClockParser.parse("2026-06-23T07:51:00", UTC)
        assertEquals(1782201060000L, ms)
    }

    /** Space and 'T' separators, plus fractional seconds, all parse the same. */
    @Test fun separator_and_fraction_variants() {
        val a = WallClockParser.parse("2026-06-24T00:10:00", IST)
        val b = WallClockParser.parse("2026-06-24 00:10:00", IST)
        val c = WallClockParser.parse("2026-06-24 00:10:00.123456", IST)
        assertEquals(a, b)
        assertEquals(a, c)
    }

    /** A numeric epoch-millis string is a true instant and passes through unchanged. */
    @Test fun numeric_epoch_ms_passthrough() {
        assertEquals(1782240000000L, WallClockParser.parse("1782240000000", IST))
    }

    /** Date-only values parse to shop-local midnight. */
    @Test fun date_only_is_shop_local_midnight() {
        val ms = WallClockParser.parse("2026-06-24", IST)!!
        // midnight IST 2026-06-24 == 1782239400000
        assertEquals(1782239400000L, ms)
    }

    @Test fun blank_or_null_is_null() {
        assertNull(WallClockParser.parse(null, IST))
        assertNull(WallClockParser.parse("", IST))
        assertNull(WallClockParser.parse("   ", IST))
    }
}
