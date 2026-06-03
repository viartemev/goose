package com.goose.android

import com.goose.android.navigation.GooseTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class NavigationTest {
    @Test
    fun `all tabs have unique routes`() {
        val routes = GooseTab.entries.map { it.route }
        assertEquals(routes.size, routes.distinct().size)
    }

    @Test
    fun `home tab is first`() {
        assertEquals(GooseTab.Home, GooseTab.entries.first())
    }

    @Test
    fun `all tabs have non-empty titles and icons`() {
        GooseTab.entries.forEach { tab ->
            assert(tab.title.isNotBlank()) { "${tab.name} has blank title" }
            assertNotNull(tab.icon)
        }
    }

    @Test
    fun `all four tabs are defined`() {
        assertEquals(4, GooseTab.entries.size)
        assertNotNull(GooseTab.entries.find { it == GooseTab.Home })
        assertNotNull(GooseTab.entries.find { it == GooseTab.Health })
        assertNotNull(GooseTab.entries.find { it == GooseTab.Coach })
        assertNotNull(GooseTab.entries.find { it == GooseTab.More })
    }
}
