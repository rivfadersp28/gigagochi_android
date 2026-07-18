package com.gigagochi.app.core.network

import java.net.URI

class StaticMediaUrlPolicy(
    baseUrl: String,
    allowDebugLoopbackHttp: Boolean,
) {
    private val allowDebugTestPetMedia = allowDebugLoopbackHttp
    private val base = validatedFeatureBaseUrl(baseUrl, allowDebugLoopbackHttp)
        ?: throw IllegalArgumentException("Invalid backend base URL")

    fun resolve(value: String?): String? {
        if (value == null) return null
        return runCatching {
            require(value.length <= 2_000 && value.none(Char::isISOControl) && !value.contains('\\'))
            val lowercase = value.lowercase()
            require(listOf("%2e", "%2f", "%5c").none(lowercase::contains))
            val parsed = URI(value)
            val resolved = if (parsed.isAbsolute) parsed.normalize() else {
                require(value.startsWith("/static/"))
                base.resolve(value.removePrefix("/")).normalize()
            }
            require(resolved.scheme == base.scheme)
            require(resolved.userInfo == null && resolved.fragment == null)
            require(resolved.host.equals(base.host, ignoreCase = true))
            require(effectivePort(resolved) == effectivePort(base))
            require(isAllowedMediaPath(resolved.path))
            validateCacheQuery(resolved.rawQuery)
            URI(
                resolved.scheme,
                null,
                resolved.host,
                if (effectivePort(resolved) == defaultPort(resolved.scheme)) -1 else resolved.port,
                resolved.path,
                resolved.rawQuery,
                null,
            ).toASCIIString()
        }.getOrNull()
    }

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme == "https" -> 443
        uri.scheme == "http" -> 80
        else -> -1
    }

    private fun defaultPort(scheme: String): Int = if (scheme == "https") 443 else 80

    private fun isAllowedMediaPath(path: String): Boolean =
        path.startsWith("/static/") ||
            (allowDebugTestPetMedia && path in ExactProductionTestPetPaths)

    private fun validateCacheQuery(rawQuery: String?) {
        if (rawQuery == null) return
        val pairs = rawQuery.split('&')
        require(pairs.size == 1)
        val components = pairs.single().split('=', limit = 2)
        require(components.size == 2 && components[0] == "v")
        require(Regex("^[A-Za-z0-9_-]{1,64}$").matches(components[1]))
    }

    private companion object {
        val ExactProductionTestPetPaths = setOf(
            "/test-pet/openai-normal.png",
            "/test-pet/openai-sad.png",
            "/test-pet/openai-happy.png",
            "/test-pet/openai-normal.mp4",
            "/test-pet/openai-sad.mp4",
            "/test-pet/openai-happy.mp4",
        )
    }
}
