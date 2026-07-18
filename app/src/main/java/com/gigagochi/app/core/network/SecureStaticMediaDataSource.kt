package com.gigagochi.app.core.network

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

const val StaticVideoMaxBytes: Long = 64L * 1024L * 1024L
const val StaticImageMaxBytes: Long = 8L * 1024L * 1024L

enum class StaticMediaFailure { UnsafeUrl, Redirect, HttpStatus, RangeNotSatisfiable, TooLarge, Truncated }

class StaticMediaIOException(val reason: StaticMediaFailure) : IOException("Static media request failed: $reason")

@UnstableApi
class SecureStaticMediaDataSource(
    private val urlPolicy: StaticMediaUrlPolicy,
    private val maxResourceBytes: Long = StaticVideoMaxBytes,
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : BaseDataSource(true) {
    private var connection: HttpURLConnection? = null
    private var input: InputStream? = null
    private var remaining: Long = 0L
    private var exactRemaining = false
    private var requiresLimitProbe = false
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val safeUrl = urlPolicy.resolve(dataSpec.uri.toString())
            ?: throw StaticMediaIOException(StaticMediaFailure.UnsafeUrl)
        val start = dataSpec.position
        require(start >= 0L)
        if (start >= maxResourceBytes) throw StaticMediaIOException(StaticMediaFailure.TooLarge)
        val requestedLength = dataSpec.length.takeIf { it != C.LENGTH_UNSET.toLong() }
        if (requestedLength != null) {
            if (requestedLength < 0L || requestedLength > maxResourceBytes - start) {
                throw StaticMediaIOException(StaticMediaFailure.TooLarge)
            }
        }
        if (requestedLength == 0L) {
            remaining = 0L
            exactRemaining = true
            opened = true
            transferStarted(dataSpec)
            return 0L
        }
        val connection = connectionFactory(URL(safeUrl)).also { this.connection = it }
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.defaultUseCaches = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Cache-Control", "no-store")
            connection.setRequestProperty("Pragma", "no-cache")
            connection.setRequestProperty("Cookie", "")
            if (start > 0L || requestedLength != null) {
                val end = requestedLength?.let { Math.addExact(start, it - 1L).toString() }.orEmpty()
                connection.setRequestProperty("Range", "bytes=$start-$end")
            }
            val status = connection.responseCode
            if (status in 300..399) throw StaticMediaIOException(StaticMediaFailure.Redirect)
            if (status == 416) throw StaticMediaIOException(StaticMediaFailure.RangeNotSatisfiable)
            if (status !in 200..299) throw StaticMediaIOException(StaticMediaFailure.HttpStatus)
            if (urlPolicy.resolve(connection.url.toString()) != safeUrl) {
                throw StaticMediaIOException(StaticMediaFailure.UnsafeUrl)
            }
            val contentLength = connection.getHeaderField("Content-Length")
                ?.trim()
                ?.toLongOrNull()
                ?.takeIf { it >= 0L }
            val totalLength = parseTotalLength(connection.getHeaderField("Content-Range"))
                ?: if (status == 200) contentLength else null
            if (status == 206 && totalLength == null) {
                throw StaticMediaIOException(StaticMediaFailure.HttpStatus)
            }
            if (totalLength != null && totalLength > maxResourceBytes) {
                throw StaticMediaIOException(StaticMediaFailure.TooLarge)
            }
            val stream = connection.inputStream
            if (status == 200 && start > 0L) skipFully(stream, start)
            if (status == 206) validateContentRange(connection.getHeaderField("Content-Range"), start)
            input = stream
            remaining = when {
                requestedLength != null -> requestedLength
                status == 200 && contentLength != null -> contentLength - start
                status == 206 && contentLength != null -> contentLength
                else -> maxResourceBytes - start
            }
            exactRemaining = requestedLength != null || contentLength != null
            requiresLimitProbe = !exactRemaining
            if (remaining < 0L || remaining > maxResourceBytes - start) {
                throw StaticMediaIOException(StaticMediaFailure.TooLarge)
            }
            opened = true
            transferStarted(dataSpec)
            return if (!exactRemaining) C.LENGTH_UNSET.toLong() else remaining
        } catch (failure: Exception) {
            input?.close()
            input = null
            connection.disconnect()
            this.connection = null
            throw failure
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) {
            if (requiresLimitProbe) {
                requiresLimitProbe = false
                if (input?.read() != -1) throw StaticMediaIOException(StaticMediaFailure.TooLarge)
            }
            return C.RESULT_END_OF_INPUT
        }
        val read = input?.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            ?: return C.RESULT_END_OF_INPUT
        if (read < 0) {
            if (exactRemaining && remaining > 0L) {
                throw StaticMediaIOException(StaticMediaFailure.Truncated)
            }
            return C.RESULT_END_OF_INPUT
        }
        remaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): android.net.Uri? = connection?.url?.toString()?.let {
        android.net.Uri.parse(it)
    }

    override fun close() {
        try {
            input?.close()
        } finally {
            input = null
            connection?.disconnect()
            connection = null
            if (opened) transferEnded()
            opened = false
            exactRemaining = false
            requiresLimitProbe = false
        }
    }

    private fun skipFully(stream: InputStream, count: Long) {
        var left = count
        while (left > 0L) {
            val skipped = stream.skip(left)
            if (skipped <= 0L) {
                if (stream.read() < 0) throw EOFException()
                left -= 1L
            } else left -= skipped
        }
    }

    private fun parseTotalLength(value: String?): Long? = value
        ?.substringAfter('/', "")
        ?.takeIf { it.isNotBlank() && it != "*" }
        ?.toLongOrNull()

    private fun validateContentRange(value: String?, expectedStart: Long) {
        val start = value?.removePrefix("bytes ")?.substringBefore('-')?.toLongOrNull()
        if (start != expectedStart) throw StaticMediaIOException(StaticMediaFailure.HttpStatus)
    }

    class Factory(
        private val urlPolicy: StaticMediaUrlPolicy,
        private val maxResourceBytes: Long = StaticVideoMaxBytes,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            SecureStaticMediaDataSource(urlPolicy, maxResourceBytes)
    }
}
