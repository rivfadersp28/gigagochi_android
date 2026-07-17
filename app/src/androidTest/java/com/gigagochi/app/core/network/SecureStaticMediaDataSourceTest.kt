package com.gigagochi.app.core.network

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureStaticMediaDataSourceTest {
    private val policy = StaticMediaUrlPolicy("https://gigagochi.serega.works/", false)

    @Test
    fun unknownLengthAcceptsExactLimitButRejectsLimitPlusOne() {
        val exact = FakeConnection(URL(MediaUrl), 200, byteArrayOf(1, 2, 3, 4), -1)
        val source = SecureStaticMediaDataSource(policy, 4) { exact }
        source.open(DataSpec(Uri.parse(MediaUrl)))
        assertEquals(listOf(1, 2, 3, 4), readAll(source).map(Byte::toInt))
        source.close()

        val oversized = FakeConnection(URL(MediaUrl), 200, byteArrayOf(1, 2, 3, 4, 5), -1)
        val oversizedSource = SecureStaticMediaDataSource(policy, 4) { oversized }
        oversizedSource.open(DataSpec(Uri.parse(MediaUrl)))
        assertFailure(StaticMediaFailure.TooLarge) { readAll(oversizedSource) }
        oversizedSource.close()
    }

    @Test
    fun openFailureDisconnectsAndRangeOverflowFailsBeforeConnection() {
        val redirect = FakeConnection(URL(MediaUrl), 302, byteArrayOf(), 0)
        val source = SecureStaticMediaDataSource(policy, 10) { redirect }
        assertFailure(StaticMediaFailure.Redirect) { source.open(DataSpec(Uri.parse(MediaUrl))) }
        assertTrue(redirect.disconnected)

        var connectionCalls = 0
        val overflow = SecureStaticMediaDataSource(policy, Long.MAX_VALUE) {
            connectionCalls += 1
            redirect
        }
        val spec = DataSpec.Builder()
            .setUri(Uri.parse(MediaUrl))
            .setPosition(Long.MAX_VALUE - 5)
            .setLength(10)
            .build()
        assertFailure(StaticMediaFailure.TooLarge) { overflow.open(spec) }
        assertEquals(0, connectionCalls)
    }

    @Test
    fun range206AndSafe200FallbackWorkWhile416IsTyped() {
        val partial = FakeConnection(
            URL(MediaUrl),
            206,
            byteArrayOf(3, 4),
            2,
            mapOf("Content-Range" to "bytes 2-3/4"),
        )
        val source206 = SecureStaticMediaDataSource(policy, 10) { partial }
        source206.open(DataSpec.Builder().setUri(Uri.parse(MediaUrl)).setPosition(2).build())
        assertEquals(listOf(3, 4), readAll(source206).map(Byte::toInt))
        assertEquals(null, partial.requestHeaders["Authorization"])
        assertEquals("", partial.requestHeaders["Cookie"])
        assertEquals("no-store", partial.requestHeaders["Cache-Control"])
        source206.close()

        val full = FakeConnection(URL(MediaUrl), 200, byteArrayOf(1, 2, 3, 4), 4)
        val fallback = SecureStaticMediaDataSource(policy, 10) { full }
        fallback.open(DataSpec.Builder().setUri(Uri.parse(MediaUrl)).setPosition(2).build())
        assertEquals(listOf(3, 4), readAll(fallback).map(Byte::toInt))
        fallback.close()

        val rangeFailure = FakeConnection(URL(MediaUrl), 416, byteArrayOf(), 0)
        val failed = SecureStaticMediaDataSource(policy, 10) { rangeFailure }
        assertFailure(StaticMediaFailure.RangeNotSatisfiable) {
            failed.open(DataSpec.Builder().setUri(Uri.parse(MediaUrl)).setPosition(2).build())
        }
    }

    @Test
    fun zeroLengthIsRejectedByMedia3BeforeDatasourceConnection() {
        var calls = 0
        val source = SecureStaticMediaDataSource(policy, 10) {
            calls += 1
            FakeConnection(URL(MediaUrl), 200, byteArrayOf(), 0)
        }
        val failure = runCatching {
            DataSpec.Builder().setUri(Uri.parse(MediaUrl)).setLength(0).build()
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, calls)
        source.close()
    }

    private fun readAll(source: SecureStaticMediaDataSource): ByteArray {
        val result = mutableListOf<Byte>()
        val buffer = ByteArray(2)
        while (true) {
            val read = source.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            repeat(read) { result += buffer[it] }
        }
        return result.toByteArray()
    }

    private fun assertFailure(expected: StaticMediaFailure, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull() as StaticMediaIOException
        assertEquals(expected, failure.reason)
    }

    private class FakeConnection(
        url: URL,
        private val status: Int,
        private val body: ByteArray,
        private val declaredLength: Long,
        private val headers: Map<String, String> = emptyMap(),
    ) : HttpURLConnection(url) {
        var disconnected = false
        val requestHeaders = mutableMapOf<String, String>()
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getResponseCode() = status
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
        override fun getContentLengthLong() = declaredLength
        override fun getHeaderField(name: String?) = headers[name]
        override fun setRequestProperty(key: String?, value: String?) {
            if (key != null && value != null) requestHeaders[key] = value
        }
    }

    private companion object {
        const val MediaUrl = "https://gigagochi.serega.works/static/media.mp4"
    }
}
