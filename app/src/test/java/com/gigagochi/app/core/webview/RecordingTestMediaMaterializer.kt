package com.gigagochi.app.core.webview

import java.io.File

internal class RecordingTestMediaMaterializer : WebMediaMaterializer {
    val sources = mutableListOf<String>()

    /** Projection fixtures model a verified disk-cache hit so their assertions stay synchronous. */
    override fun acquireCached(
        sourceUrl: String,
        kind: WebMediaKind,
    ): VerifiedWebMediaAsset = materialize(sourceUrl, kind)

    override fun materialize(sourceUrl: String, kind: WebMediaKind): VerifiedWebMediaAsset {
        sources += sourceUrl
        val file = File.createTempFile("verified-web-test-", ".media").apply {
            deleteOnExit()
            writeBytes(byteArrayOf(sources.size.toByte()))
        }
        val cleanUrl = sourceUrl.substringBefore('?')
        val mime = when (kind) {
            WebMediaKind.Video -> "video/mp4"
            WebMediaKind.Image -> when {
                cleanUrl.endsWith(".jpg") || cleanUrl.endsWith(".jpeg") -> "image/jpeg"
                cleanUrl.endsWith(".webp") -> "image/webp"
                else -> "image/png"
            }
        }
        return VerifiedWebMediaAsset(file, mime, file.length(), "test-${sources.size}")
    }
}
