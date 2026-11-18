package com.yidream.mdm

internal object MainNavigation {
    private val tabs = setOf("home", "store", "sites", "settings")
    fun restore(key: String?, webAvailable: Boolean = true): String = when {
        key == "about" -> "settings"
        key == "sites" && !webAvailable -> "store"
        key in tabs -> key!!
        else -> "home"
    }
}
