package com.gigagochi.app.core.webview

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

internal class WebMediaProxy(
    context: Context,
    private val registry: WebMediaReferenceRegistry,
) {
    @Suppress("unused")
    private val applicationContext = context.applicationContext

    fun intercept(
        requestUrl: String,
        method: String,
        requestHeaders: Map<String, String>,
    ): WebResourceResponse? {
        if (!isWebMediaRequest(requestUrl)) return null
        if (method != "GET" && method != "HEAD") {
            return errorResponse(
                statusCode = 405,
                reasonPhrase = "Method Not Allowed",
                headers = mapOf("Allow" to "GET, HEAD"),
            )
        }

        // This call may download on WebView's request thread. It returns only after verification and
        // atomic publication, so neither HEAD nor GET can observe a partial file.
        val resolved = registry.resolveRequest(requestUrl)
            ?: return errorResponse(404, "Not Found")
        val media = resolved.media
        val range = requestHeaders.entries.firstOrNull {
            it.key.equals("Range", ignoreCase = true)
        }?.value
        val plan = runCatching {
            planWebMediaResponse(range, media.byteLength, media.maxResourceBytes)
        }.getOrElse {
            return errorResponse(500, "Internal Server Error")
        }
        if (!registry.isActive(resolved)) return errorResponse(404, "Not Found")
        if (!plan.servesBody || method == "HEAD") {
            return response(
                media = media,
                plan = plan,
                input = ByteArrayInputStream(ByteArray(0)),
            )
        }
        val input = runCatching {
            LocalMediaInputStream(
                fileInput = FileInputStream(media.path),
                position = plan.start,
                length = requireNotNull(plan.length),
            )
        }.getOrElse {
            return errorResponse(404, "Not Found")
        }
        return response(media, plan, input)
    }

    private fun response(
        media: RegisteredWebMedia,
        plan: WebMediaHttpPlan,
        input: InputStream,
    ): WebResourceResponse = WebResourceResponse(
        media.mimeType,
        null,
        plan.statusCode,
        plan.reasonPhrase,
        plan.headers + mapOf(
            "Content-Disposition" to "inline",
            "ETag" to "\"sha256-${media.version}\"",
        ),
        input,
    )

    private fun errorResponse(
        statusCode: Int,
        reasonPhrase: String,
        headers: Map<String, String> = emptyMap(),
    ): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "UTF-8",
        statusCode,
        reasonPhrase,
        mapOf(
            "Cache-Control" to "no-store",
            "Content-Length" to "0",
            "X-Content-Type-Options" to "nosniff",
        ) + headers,
        ByteArrayInputStream(ByteArray(0)),
    )
}

private class LocalMediaInputStream(
    private val fileInput: FileInputStream,
    position: Long,
    length: Long,
) : InputStream() {
    private var remaining = length
    private var closed = false

    init {
        require(position >= 0L && length >= 0L)
        fileInput.channel.position(position)
    }

    override fun read(): Int {
        val oneByte = ByteArray(1)
        val read = read(oneByte, 0, 1)
        return if (read < 0) -1 else oneByte[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw IOException("Media response stream is closed")
        if (length == 0) return 0
        if (remaining == 0L) return -1
        val read = fileInput.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (read < 0) throw IOException("Verified media file was truncated during response")
        remaining -= read
        return read
    }

    override fun close() {
        if (closed) return
        closed = true
        fileInput.close()
    }
}
