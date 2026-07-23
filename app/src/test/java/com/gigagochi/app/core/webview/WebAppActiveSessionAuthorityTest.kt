package com.gigagochi.app.core.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAppActiveSessionAuthorityTest {
    @Test
    fun `dashboard scope exists only for current authenticated owner and pet`() {
        val authority = WebAppActiveSessionAuthority()

        assertNull(authority.ownerId())
        assertNull(authority.dashboardScope())

        authority.activateOwner("owner-a")
        assertEquals("owner-a", authority.ownerId())
        assertNull(authority.dashboardScope())

        authority.activateDashboard("owner-a", "pet-a")
        val first = requireNotNull(authority.dashboardScope())
        assertEquals("owner-a", first.ownerId)
        assertEquals("pet-a", first.petId)
        assertTrue(authority.isCurrent(first))

        authority.activateDashboard("owner-a", "pet-b")
        val second = requireNotNull(authority.dashboardScope())
        assertFalse(authority.isCurrent(first))
        assertTrue(authority.isCurrent(second))

        authority.clear()
        assertNull(authority.ownerId())
        assertNull(authority.dashboardScope())
        assertFalse(authority.isCurrent(second))
    }
}
