package com.yidream.mdm

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class UsbAppProfileTest {
    @Test fun legacyConfigurationDoesNotEnableAnAllowlist() {
        assertNull(UsbAppProfile.parse(JSONObject("""{"extra_packages":["com.example.app"]}""")))
    }

    @Test fun acceptsOnlyTheTwoProfilesAndBoundedPackageIds() {
        val packages = JSONArray(listOf("com.waze", "com.example.notes", "com.example.notes"))
        for (type in UsbAppProfileType.entries) {
            val config = JSONObject().put("app_profile", type.value).put("allowed_packages", packages)
            assertEquals(UsbAppProfile(type, setOf("com.waze", "com.example.notes")), UsbAppProfile.parse(config))
        }

        val oversized = JSONArray((0..100).map { "com.example.app$it" })
        val invalid = listOf(
            JSONObject().put("allowed_packages", packages),
            JSONObject().put("app_profile", "other").put("allowed_packages", packages),
            JSONObject().put("app_profile", "mp3"),
            JSONObject().put("app_profile", "mp3").put("allowed_packages", JSONArray(listOf("invalid package"))),
            JSONObject().put("app_profile", "mp3").put("allowed_packages", oversized)
        )
        invalid.forEach { config -> assertTrue(runCatching { UsbAppProfile.parse(config) }.isFailure) }
    }

    @Test fun onlyPreviouslyManagedPackagesCanBeRestoredAndExplicitDenialsStayInForce() {
        val selected = setOf("com.example.kept", "com.example.foreign", "com.example.denied")
        val profile = UsbAppProfile(UsbAppProfileType.MP3, selected)
        val eligible = selected + "com.waze"
        val tracked = setOf("com.example.kept", "com.example.denied", "com.example.critical")
        val plan = profile.changes(eligible, tracked, mapOf("com.example.denied" to false, "com.waze" to true))
        assertEquals(setOf("com.example.denied"), plan.hide)
        assertEquals(setOf("com.example.kept"), plan.restore)
    }

    @Test fun aReceiptUsesOnlyAValidRequestNonce() {
        val value = "90c0e024-62e3-48ec-8c4a-6d148d3745c1"
        assertEquals(value, UsbAppProfile.requestId(JSONObject().put("profile_request_id", value)))
        assertTrue(runCatching { UsbAppProfile.requestId(JSONObject().put("profile_request_id", "../unsafe")) }.isFailure)
        assertTrue(runCatching { UsbAppProfile.requestId(JSONObject()) }.isFailure)
    }
}
