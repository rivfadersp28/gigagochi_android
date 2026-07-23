package com.gigagochi.app.core.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebMediaHttpPlanTest {
    @Test
    fun fullAndClosedRangesHaveBrowserCompatibleStatusAndHeaders() {
        val full = planWebMediaResponse(null, totalLength = 100L, maxResourceBytes = 100L)
        assertEquals(200, full.statusCode)
        assertEquals(0L, full.start)
        assertEquals(100L, full.length)
        assertEquals("100", full.headers["Content-Length"])
        assertEquals("bytes", full.headers["Accept-Ranges"])

        val partial = planWebMediaResponse("bytes=10-19", 100L, 100L)
        assertEquals(206, partial.statusCode)
        assertEquals(10L, partial.start)
        assertEquals(10L, partial.length)
        assertEquals("bytes 10-19/100", partial.headers["Content-Range"])

        val clamped = planWebMediaResponse("bytes=90-200", 100L, 100L)
        assertEquals(206, clamped.statusCode)
        assertEquals(10L, clamped.length)
        assertEquals("bytes 90-99/100", clamped.headers["Content-Range"])
    }

    @Test
    fun openAndSuffixRangesResolveAgainstTheCompleteLength() {
        val open = planWebMediaResponse("bytes=95-", 100L, 100L)
        assertEquals(206, open.statusCode)
        assertEquals(95L, open.start)
        assertEquals(5L, open.length)
        assertEquals("bytes 95-99/100", open.headers["Content-Range"])

        val suffix = planWebMediaResponse("bytes=-10", 100L, 100L)
        assertEquals(206, suffix.statusCode)
        assertEquals(90L, suffix.start)
        assertEquals(10L, suffix.length)

        val oversizedSuffix = planWebMediaResponse("bytes=-1000", 100L, 100L)
        assertEquals(0L, oversizedSuffix.start)
        assertEquals(100L, oversizedSuffix.length)
    }

    @Test
    fun unknownLengthSupportsClosedRangeAndSafelyIgnoresOpenRange() {
        val closed = planWebMediaResponse("bytes=0-1023", null, 2048L)
        assertEquals(206, closed.statusCode)
        assertEquals(1024L, closed.length)
        assertEquals("bytes 0-1023/*", closed.headers["Content-Range"])

        val open = planWebMediaResponse("bytes=0-", null, 2048L)
        assertEquals(200, open.statusCode)
        assertNull(open.length)
        assertNull(open.headers["Content-Range"])
    }

    @Test
    fun malformedMultipleUnsatisfiableAndOverLimitRangesReturn416() {
        listOf(
            "items=0-1",
            "bytes=0-1,3-4",
            "bytes=20-10",
            "bytes=-0",
            "bytes=100-",
            "bytes=100-101",
            "bytes=99999999999999999999-",
        ).forEach { header ->
            val plan = planWebMediaResponse(header, totalLength = 100L, maxResourceBytes = 100L)
            assertEquals(header, 416, plan.statusCode)
            assertEquals("bytes */100", plan.headers["Content-Range"])
            assertEquals("0", plan.headers["Content-Length"])
        }
    }
}
