package com.gigagochi.app.core.webview

internal data class WebMediaHttpPlan(
    val statusCode: Int,
    val reasonPhrase: String,
    val start: Long = 0L,
    val length: Long? = null,
    val headers: Map<String, String> = emptyMap(),
) {
    val servesBody: Boolean get() = statusCode == 200 || statusCode == 206
}

/** Builds strict single-range response semantics without touching Android or the network. */
internal fun planWebMediaResponse(
    rangeHeader: String?,
    totalLength: Long?,
    maxResourceBytes: Long,
): WebMediaHttpPlan {
    require(maxResourceBytes > 0L)
    require(totalLength == null || totalLength in 0L..maxResourceBytes)
    val commonHeaders = mapOf(
        "Accept-Ranges" to "bytes",
        "Cache-Control" to "no-store",
        "X-Content-Type-Options" to "nosniff",
    )
    if (rangeHeader == null) {
        return WebMediaHttpPlan(
            statusCode = 200,
            reasonPhrase = "OK",
            length = totalLength,
            headers = commonHeaders.withContentLength(totalLength),
        )
    }

    val parsed = parseSingleByteRange(rangeHeader)
        ?: return unsatisfiableWebMediaPlan(totalLength, commonHeaders)
    val selected = when (parsed) {
        is ParsedByteRange.Closed -> {
            val end = totalLength?.let { minOf(parsed.endInclusive, it - 1L) }
                ?: parsed.endInclusive
            if (
                parsed.start > parsed.endInclusive ||
                parsed.start >= maxResourceBytes ||
                end < parsed.start
            ) {
                null
            } else {
                SelectedByteRange(parsed.start, end)
            }
        }

        is ParsedByteRange.Open -> totalLength?.let { total ->
            if (parsed.start >= total) null else SelectedByteRange(parsed.start, total - 1L)
        }

        is ParsedByteRange.Suffix -> totalLength?.takeIf { it > 0L }?.let { total ->
            val length = minOf(parsed.length, total)
            SelectedByteRange(total - length, total - 1L)
        }
    }
    if (selected == null) {
        // A valid open or suffix range cannot be represented until a complete length is known.
        if (totalLength == null && parsed !is ParsedByteRange.Closed) {
            return WebMediaHttpPlan(
                statusCode = 200,
                reasonPhrase = "OK",
                headers = commonHeaders,
            )
        }
        return unsatisfiableWebMediaPlan(totalLength, commonHeaders)
    }
    if (selected.endInclusive >= maxResourceBytes) {
        return unsatisfiableWebMediaPlan(totalLength, commonHeaders)
    }
    val length = Math.addExact(selected.endInclusive - selected.start, 1L)
    val completeLength = totalLength?.toString() ?: "*"
    return WebMediaHttpPlan(
        statusCode = 206,
        reasonPhrase = "Partial Content",
        start = selected.start,
        length = length,
        headers = commonHeaders + mapOf(
            "Content-Length" to length.toString(),
            "Content-Range" to "bytes ${selected.start}-${selected.endInclusive}/$completeLength",
        ),
    )
}

private sealed interface ParsedByteRange {
    data class Closed(val start: Long, val endInclusive: Long) : ParsedByteRange
    data class Open(val start: Long) : ParsedByteRange
    data class Suffix(val length: Long) : ParsedByteRange
}

private data class SelectedByteRange(val start: Long, val endInclusive: Long)

private fun parseSingleByteRange(value: String): ParsedByteRange? {
    if (
        value.length !in 7..128 ||
        value.any(Char::isISOControl) ||
        !value.startsWith("bytes=") ||
        ',' in value
    ) {
        return null
    }
    val spec = value.removePrefix("bytes=")
    val separator = spec.indexOf('-')
    if (separator < 0 || spec.indexOf('-', separator + 1) >= 0) return null
    val first = spec.substring(0, separator)
    val last = spec.substring(separator + 1)
    if (first.isEmpty()) {
        val suffix = last.toStrictLongOrNull()?.takeIf { it > 0L } ?: return null
        return ParsedByteRange.Suffix(suffix)
    }
    val start = first.toStrictLongOrNull() ?: return null
    if (last.isEmpty()) return ParsedByteRange.Open(start)
    val end = last.toStrictLongOrNull() ?: return null
    return ParsedByteRange.Closed(start, end)
}

private fun String.toStrictLongOrNull(): Long? =
    takeIf { it.isNotEmpty() && it.length <= 19 && it.all(Char::isDigit) }
        ?.toLongOrNull()

private fun unsatisfiableWebMediaPlan(
    totalLength: Long?,
    commonHeaders: Map<String, String>,
): WebMediaHttpPlan = WebMediaHttpPlan(
    statusCode = 416,
    reasonPhrase = "Range Not Satisfiable",
    headers = commonHeaders + mapOf(
        "Content-Range" to "bytes */${totalLength?.toString() ?: "*"}",
        "Content-Length" to "0",
    ),
)

private fun Map<String, String>.withContentLength(length: Long?): Map<String, String> =
    if (length == null) this else this + ("Content-Length" to length.toString())
