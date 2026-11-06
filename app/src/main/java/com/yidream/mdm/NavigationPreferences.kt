package com.yidream.mdm

import android.content.Context

/** A display preference only: it never changes device policies or app permissions. */
internal class NavigationPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("yidream_appearance", Context.MODE_PRIVATE)

    var showBottomBar: Boolean
        get() = preferences.getString("navigation", "tabs") != "home"
        set(value) { preferences.edit().putString("navigation", if (value) "tabs" else "home").apply() }
}
