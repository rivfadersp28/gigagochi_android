package com.gigagochi.app.core.network

import com.gigagochi.app.core.model.SensitiveToken
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val FeatureResponseMaxBytes = 512 * 1024

data class FeatureHttpRequest(
    val method: String,
    val path: String,
    val body: ByteArray = ByteArray(0),
) {
    override fun toString(): String = "FeatureHttpRequest(method=$method,path=$path,<redacted>)"
}

data class FeatureHttpResponse(val statusCode: Int, val body: ByteArray) {
    override fun toString(): String = "FeatureHttpResponse(statusCode=$statusCode,<redacted>)"
}

interface FeatureHttpTransport {
    suspend fun execute(request: FeatureHttpRequest, accessToken: SensitiveToken): FeatureHttpResponse
}

class FeatureTransportException(message: String) : IOException(message)

class UrlConnectionFeatureHttpTransport(
    baseUrl: String,
    allowDebugLoopbackHttp: Boolean,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 30_000,
    private val maxResponseBytes: Int = FeatureResponseMaxBytes,
) : FeatureHttpTransport {
    private val base = validatedFeatureBaseUrl(baseUrl, allowDebugLoopbackHttp)
        ?: throw IllegalArgumentException("Invalid backend base URL")

    override suspend fun execute(
        request: FeatureHttpRequest,
        accessToken: SensitiveToken,
    ): FeatureHttpResponse = withContext(Dispatchers.IO) {
        try {
            require(request.method in setOf("GET", "POST"))
            require(request.path.startsWith("/api/android/") && !request.path.contains(".."))
            val endpoint = base.resolve(request.path.removePrefix("/"))
            require(endpoint.scheme == base.scheme && endpoint.host == base.host)
            val connection = URL(endpoint.toASCIIString()).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = request.method
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.doInput = true
                connection.doOutput = request.method == "POST"
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("Cache-Control", "no-store")
                connection.setRequestProperty("Pragma", "no-cache")
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Authorization", "Bearer ${accessToken.reveal()}")
                if (connection.doOutput) {
                    connection.setFixedLengthStreamingMode(request.body.size)
                    connection.outputStream.use { it.write(request.body) }
                }
                val status = connection.responseCode
                if (status in 300..399) throw FeatureTransportException("Redirect rejected")
                if (connection.contentLengthLong > maxResponseBytes) {
                    throw FeatureTransportException("Response exceeds configured limit")
                }
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val bytes = stream?.use { input ->
                    ByteArrayOutputStream().use { output ->
                        val buffer = ByteArray(8 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > maxResponseBytes) {
                                throw FeatureTransportException("Response exceeds configured limit")
                            }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                } ?: ByteArray(0)
                FeatureHttpResponse(status, bytes)
            } finally {
                connection.disconnect()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (known: FeatureTransportException) {
            throw known
        } catch (_: IllegalArgumentException) {
            throw FeatureTransportException("Feature request rejected")
        }
    }
}

fun validatedFeatureBaseUrl(baseUrl: String, allowDebugLoopbackHttp: Boolean): URI? = runCatching {
    val parsed = URI(baseUrl.trim()).normalize()
    val scheme = parsed.scheme?.lowercase()
    val host = parsed.host?.lowercase()
    val loopback = allowDebugLoopbackHttp && scheme == "http" &&
        host in setOf("localhost", "127.0.0.1", "10.0.2.2", "::1")
    require(scheme == "https" || loopback)
    require(!host.isNullOrBlank() && parsed.userInfo == null)
    require(parsed.query == null && parsed.fragment == null)
    URI(parsed.scheme, null, parsed.host, parsed.port, parsed.path.orEmpty().trimEnd('/') + "/", null, null)
}.getOrNull()
