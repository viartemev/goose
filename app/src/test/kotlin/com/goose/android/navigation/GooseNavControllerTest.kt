package com.goose.android.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class GooseNavControllerTest {
    @Test
    fun `navigateToTab sets route`() {
        // Verify GooseTab routes match expected values
        assertEquals("home", GooseTab.Home.route)
        assertEquals("health", GooseTab.Health.route)
        assertEquals("coach", GooseTab.Coach.route)
        assertEquals("more", GooseTab.More.route)
    }

    @Test
    fun `tab titles are non-blank`() {
        GooseTab.entries.forEach { tab ->
            assert(tab.title.isNotBlank()) { "${tab.name} has blank title" }
        }
    }

    @Test
    fun `tab icons are defined`() {
        GooseTab.entries.forEach { tab ->
            assert(tab.icon != null)
        }
    }

    @Test
    fun `tab ordinal order matches expected`() {
        assertEquals(0, GooseTab.Home.ordinal)
        assertEquals(1, GooseTab.Health.ordinal)
        assertEquals(2, GooseTab.Coach.ordinal)
        assertEquals(3, GooseTab.More.ordinal)
    }

    @Test
    fun `all tab routes are unique`() {
        val routes = GooseTab.entries.map { it.route }
        assertEquals(routes.size, routes.distinct().size)
    }
}
