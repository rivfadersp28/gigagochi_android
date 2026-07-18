package com.gigagochi.app.debug

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalEncodingApi::class)
class DebugSessionPayloadTest {
    private val now = 1_800_000_000_000L

    @Test
    fun strictBase64JsonProducesRedactedSession() {
        val session = DebugSessionPayloadParser.parse(
            encode(
                """{"accountId":"account-17","accessToken":"access-secret","refreshToken":"refresh-secret","expiresAt":1800000060000}""",
            ),
            now,
        )

        requireNotNull(session)
        assertEquals("account-17", session.accountId)
        assertEquals(now + 60_000, session.expiresAtEpochMillis)
        assertEquals("<redacted>", session.accessToken.toString())
        assertFalse(session.toString().contains("access-secret"))
        assertFalse(session.toString().contains("refresh-secret"))
    }

    @Test
    fun malformedExpiredOrNonCanonicalPayloadFailsClosed() {
        listOf(
            null,
            "not-base64",
            encode("{}"),
            encode(
                """{"accountId":"account-17","accessToken":"access","refreshToken":null,"expiresAt":1800000000000}""",
            ),
            encode(
                """{"accountId":" account-17","accessToken":"access","refreshToken":null,"expiresAt":1800000060000}""",
            ),
            encode(
                """{"accountId":"account-17","accessToken":"access","refreshToken":null,"expiresAt":1800000060000,"unexpected":true}""",
            ),
        ).forEach { assertNull(DebugSessionPayloadParser.parse(it, now)) }
    }

    @Test
    fun tokenAndPayloadBoundsAreEnforcedBeforePersistence() {
        val oversizedToken = "x".repeat(16 * 1024 + 1)
        val encoded = encode(
            """{"accountId":"account-17","accessToken":"$oversizedToken","refreshToken":null,"expiresAt":1800000060000}""",
        )
        assertTrue(encoded.length < 48 * 1024)
        assertNull(DebugSessionPayloadParser.parse(encoded, now))
        assertNull(DebugSessionPayloadParser.parse("A".repeat(48 * 1024 + 1), now))
    }

    private fun encode(value: String): String = Base64.Default.encode(value.encodeToByteArray())
}
