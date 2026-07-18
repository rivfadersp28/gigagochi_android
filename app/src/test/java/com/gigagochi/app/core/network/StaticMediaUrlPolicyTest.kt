package com.gigagochi.app.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StaticMediaUrlPolicyTest {
    private val policy = StaticMediaUrlPolicy("https://gigagochi.serega.works/", false)
    private val debugPolicy = StaticMediaUrlPolicy("https://gigagochi.serega.works/", true)

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

    @Test
    fun exactTestPetMediaIsDebugOnlyAndDoesNotOpenTheNamespace() {
        val exactPaths = listOf(
            "/test-pet/openai-normal.png",
            "/test-pet/openai-sad.png",
            "/test-pet/openai-happy.png",
            "/test-pet/openai-normal.mp4?v=20260713-seedance-preroll-v3",
            "/test-pet/openai-sad.mp4?v=20260713-seedance-preroll-v3",
            "/test-pet/openai-happy.mp4?v=20260713-seedance-preroll-v3",
        )
        exactPaths.forEach { path ->
            val absolute = "https://gigagochi.serega.works$path"
            assertEquals(absolute, debugPolicy.resolve(absolute))
            assertNull(path, policy.resolve(absolute))
        }

        listOf(
            "https://gigagochi.serega.works/test-pet/arbitrary.png",
            "https://gigagochi.serega.works/test-pet/openai-normal.png?token=secret",
            "https://gigagochi.serega.works/test-pet/%2e%2e/static/pet.png",
            "https://gigagochi.serega.works/test-pet/openai%2fnormal.png",
            "https://evil.example/test-pet/openai-normal.png",
        ).forEach { value ->
            assertNull(value, debugPolicy.resolve(value))
            assertNull(value, policy.resolve(value))
        }
    }
}
