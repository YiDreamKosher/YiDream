package com.yidream.mdm

import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton

/** One settings destination at a time; all navigation stays behind MainActivity's account gate. */
internal class SettingsMenuController(private val activity: AppCompatActivity, private val onUpdatesOpened: () -> Unit) {
    private data class Destination(val row: Int, val page: Int, val title: Int)
    private val destinations = linkedMapOf(
        "account" to Destination(R.id.settingsOpenAccount, R.id.settingsPageAccount, R.string.account_settings_title),
        "appearance" to Destination(R.id.settingsOpenAppearance, R.id.settingsPageAppearance, R.string.settings_appearance_section),
        "language" to Destination(R.id.settingsOpenLanguage, R.id.settingsPageLanguage, R.string.settings_languages_section),
        "updates" to Destination(R.id.rowUpdates, R.id.settingsPageUpdates, R.string.settings_menu_updates),
        "filter" to Destination(R.id.settingsOpenFilter, R.id.settingsPageFilter, R.string.settings_filter_section),
        "contact" to Destination(R.id.settingsOpenContact, R.id.settingsPageContact, R.string.settings_menu_contact)
    )
    var selected: String? = null
        private set

    fun bind(restored: String? = null) {
        destinations.forEach { (key, destination) -> activity.findViewById<View>(destination.row).setOnClickListener { open(key) } }
        activity.findViewById<AppCompatButton>(R.id.settingsBack).apply {
            backgroundTintList = null
            supportBackgroundTintList = null
            setOnClickListener { back() }
        }
        open(restored)
    }

    fun open(key: String?) {
        selected = key?.takeIf { it in destinations }
        activity.findViewById<View>(R.id.settingsMenu).visibility = if (selected == null) View.VISIBLE else View.GONE
        activity.findViewById<View>(R.id.settingsSubpage).visibility = if (selected == null) View.GONE else View.VISIBLE
        destinations.forEach { (name, destination) -> activity.findViewById<View>(destination.page).visibility = if (name == selected) View.VISIBLE else View.GONE }
        selected?.let { activity.findViewById<TextView>(R.id.settingsPageTitle).setText(destinations.getValue(it).title) }
        activity.findViewById<ScrollView>(R.id.pageSettings).scrollTo(0, 0)
        if (selected == "updates") onUpdatesOpened()
    }

    fun back(): Boolean {
        if (selected == null) return false
        open(null)
        return true
    }

    fun setUpdateAvailable(available: Boolean) {
        activity.findViewById<View>(R.id.settingsUpdateBadge).visibility = if (available) View.VISIBLE else View.GONE
    }
}
