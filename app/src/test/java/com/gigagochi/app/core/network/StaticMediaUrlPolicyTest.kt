package com.gigagochi.app.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StaticMediaUrlPolicyTest {
    private val policy = StaticMediaUrlPolicy("https://gigagochi.serega.works/", false)

    @Test
    fun allowsOnlySameOriginStaticCacheVersionUrls() {
        assertEquals(
            "https://gigagochi.serega.works/static/pet.mp4?v=123",
            policy.resolve("/static/pet.mp4?v=123"),
        )
        assertEquals(
            "https://gigagochi.serega.works/static/pet.jpg?v=abc_1",
            policy.resolve("https://gigagochi.serega.works:443/static/pet.jpg?v=abc_1"),
        )
        listOf(
            "https://evil.example/static/pet.mp4",
            "/static/%2e%2e/secret",
            "/static/a%2Fb",
            "/static/a%5Cb",
            "/static/pet.mp4?v=1&v=2",
            "/static/pet.mp4?token=secret",
            "/api/android/chat",
        ).forEach { assertNull(it, policy.resolve(it)) }
    }
}
