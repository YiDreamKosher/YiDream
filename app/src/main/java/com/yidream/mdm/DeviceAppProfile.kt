package com.yidream.mdm

internal object DeviceAppProfile {
    val mp3Packages = setOf(
        "com.android.calculator2", "com.google.android.calculator",
        "com.android.deskclock", "com.google.android.deskclock",
        "com.android.calendar", "com.google.android.calendar",
        "com.android.soundrecorder", "com.google.android.apps.recorder",
        "com.android.notes", "com.android.notepad", "com.google.android.keep",
        "com.jtech.zemer", "tfilon.tfilon", "net.calj.android", "com.rhmsoft.pulsar",
        "fm.jewishmusic.application"
    )
    val qinPackages = mp3Packages + setOf("com.waze", "app.kosherscan.kosher_scan")

    fun packages(profile: String?): Set<String>? = when (profile) {
        "mp3" -> mp3Packages
        "qin" -> qinPackages
        else -> null
    }
}
