package com.gigagochi.app.core.network

import com.gigagochi.app.core.model.SensitiveToken
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureFeatureHttpTest {
    @Test
    fun productionRequiresHttpsAndDebugHttpIsLoopbackOnly() {
        assertNotNull(validatedFeatureBaseUrl("https://gigagochi.serega.works/", false))
        assertNull(validatedFeatureBaseUrl("http://gigagochi.serega.works/", true))
        assertNull(validatedFeatureBaseUrl("http://192.168.1.5/", true))
        assertNull(validatedFeatureBaseUrl("http://127.0.0.1/", false))
        assertNotNull(validatedFeatureBaseUrl("http://127.0.0.1:8080/", true))
    }

    @Test
    fun redirectsAndOversizedBodiesAreRejectedWithoutSecretsInException() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/android/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/api/android/target")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/api/android/large") { exchange ->
            val bytes = "x".repeat(200).toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val transport = UrlConnectionFeatureHttpTransport(
                "http://127.0.0.1:${server.address.port}/",
                true,
                maxResponseBytes = 64,
            )
            listOf("/api/android/redirect", "/api/android/large").forEach { path ->
                var rejected: Throwable? = null
                try {
                    transport.execute(
                        FeatureHttpRequest("GET", path),
                        SensitiveToken.of("never-render-this-token"),
                    )
                } catch (error: FeatureTransportException) {
                    rejected = error
                }
                assertNotNull(rejected)
                assertTrue(!rejected.toString().contains("never-render"))
            }
        } finally {
            server.stop(0)
        }
    }
}
