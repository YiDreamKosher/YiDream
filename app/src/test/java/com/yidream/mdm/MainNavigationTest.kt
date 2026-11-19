package com.yidream.mdm

import org.junit.Assert.assertEquals
import org.junit.Test

class MainNavigationTest {
    @Test fun redirectsOldAboutTabToSettings() { assertEquals("settings", MainNavigation.restore("about")) }
    @Test fun restoresEachMainTab() { listOf("home", "store", "sites", "settings").forEach { assertEquals(it, MainNavigation.restore(it)) } }
    @Test fun invalidStateOpensHome() { assertEquals("home", MainNavigation.restore(null)); assertEquals("home", MainNavigation.restore("unknown")) }
    @Test fun unavailableSectionsCannotBeRestoredOrReachedBySwiping() {
        assertEquals(listOf("home", "settings"), MainNavigation.visibleTabs(false, false))
        assertEquals("home", MainNavigation.restore("sites", false, false))
        assertEquals("home", MainNavigation.restore("store", false, false))
        assertEquals("settings", MainNavigation.adjacent("home", true, false, false))
        assertEquals("home", MainNavigation.adjacent("settings", false, false, false))
        assertEquals("home", MainNavigation.adjacent("home", false, false, false))
        assertEquals("settings", MainNavigation.adjacent("settings", true, false, false))
    }
    @Test fun eachApprovedSectionIsInsertedInItsConsistentPosition() {
        assertEquals(listOf("home", "sites", "settings"), MainNavigation.visibleTabs(true, false))
        assertEquals(listOf("home", "store", "settings"), MainNavigation.visibleTabs(false, true))
        assertEquals("sites", MainNavigation.adjacent("store", true, true, true))
        assertEquals("store", MainNavigation.adjacent("sites", false, true, true))
    }
}
