package com.yidream.mdm

import org.json.JSONObject

internal enum class UsbAppProfileType(val value: String) {
    MP3("mp3"),
    QIN("qin")
}

internal data class UsbAppProfile(val type: UsbAppProfileType, val allowedPackages: Set<String>) {
    internal data class Changes(val hide: Set<String>, val restore: Set<String>)

    fun allows(packageName: String, remoteRules: Map<String, Boolean>): Boolean =
        remoteRules[packageName] ?: (packageName in allowedPackages)

    /** A package hidden outside this profile remains under its existing policy. */
    fun changes(eligible: Set<String>, hiddenByProfile: Set<String>, remoteRules: Map<String, Boolean>): Changes {
        val allowed = eligible.filterTo(mutableSetOf()) { allows(it, remoteRules) }
        return Changes(eligible - allowed, eligible.intersect(hiddenByProfile).intersect(allowed))
    }

    companion object {
        private const val MAX_ALLOWED_PACKAGES = 100
        private val requestIdPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

        fun requestId(config: JSONObject): String =
            (config.get("profile_request_id") as? String)?.takeIf(requestIdPattern::matches)
                ?: error("Invalid profile request ID")

        fun parse(config: JSONObject): UsbAppProfile? {
            if (!config.has("app_profile")) {
                require(!config.has("allowed_packages") && !config.has("profile_request_id"))
                return null
            }
            val type = UsbAppProfileType.entries.singleOrNull { it.value == config.get("app_profile") }
                ?: error("Invalid app profile")
            val packages = config.getJSONArray("allowed_packages")
            require(packages.length() <= MAX_ALLOWED_PACKAGES)
            return UsbAppProfile(type, (0 until packages.length()).map { index ->
                (packages.get(index) as? String)?.takeIf(DevicePolicy::validPackageName)
                    ?: error("Invalid package name")
            }.toSet())
        }
    }
}
