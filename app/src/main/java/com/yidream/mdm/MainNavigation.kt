package com.yidream.mdm

internal object MainNavigation {
    fun visibleTabs(webAvailable: Boolean = true, storeAvailable: Boolean = true): List<String> =
        listOfNotNull("home", "store".takeIf { storeAvailable }, "sites".takeIf { webAvailable }, "settings")

    fun restore(key: String?, webAvailable: Boolean = true, storeAvailable: Boolean = true): String = when {
        key == "about" -> "settings"
        key in visibleTabs(webAvailable, storeAvailable) -> key!!
        else -> "home"
    }

    fun adjacent(key: String, forward: Boolean, webAvailable: Boolean, storeAvailable: Boolean): String {
        val tabs = visibleTabs(webAvailable, storeAvailable)
        val index = tabs.indexOf(restore(key, webAvailable, storeAvailable))
        return tabs[(index + if (forward) 1 else -1).coerceIn(tabs.indices)]
    }
}
