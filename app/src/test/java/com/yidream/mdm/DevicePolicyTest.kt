package com.yidream.mdm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URI

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DevicePolicyTest {
    @Test
    fun onlyExplicitHttpsHostsAndTheirSubdomainsAreAllowed() {
        val policy = DevicePolicy(1, emptyMap(), mapOf("example.org" to true, "private.example.org" to false))
        assertTrue(policy.permits(URI("https://EXAMPLE.ORG/public")))
        assertTrue(policy.permits(URI("https://news.example.org/")))
        listOf("https://private.example.org", "https://child.private.example.org",
            "https://notexample.org", "https://example.org.other.net",
            "https://name@example.org", "https://example.org:444",
            "http://example.org", "file:///example.org", "https://example.org.").forEach {
            assertFalse(it, policy.permits(URI(it)))
        }
    }

    @Test
    fun redirectsCannotFetchAnUnapprovedDomainOrDowngradeHttps() {
        val policy = DevicePolicy(1, emptyMap(), mapOf("example.org" to true))
        val source = URI("https://example.org/a")
        assertEquals(URI("https://example.org/b"), ManagedWebPage.redirect(source, "/b", policy::permits))
        for (target in listOf("//other.org", "http://example.org/", "https://127.0.0.1", "https://example.org@other.org")) {
            try {
                ManagedWebPage.redirect(source, target, policy::permits)
                fail("Redirect accepted: $target")
            } catch (error: IllegalArgumentException) {
                assertEquals("SITE_BLOCKED", error.message)
            }
        }
    }

    @Test
    fun policyParsingDoesNotCoerceMalformedValuesIntoRules() {
        val value = JSONObject("""{"revision":4,"apps":{"com.example.test":false},"sites":{"example.org":true}}""")
        val policy = DevicePolicy.parse(value)
        assertEquals(4L, policy.revision)
        assertEquals(false, policy.apps["com.example.test"])
        assertEquals(policy, DevicePolicy.parse(policy.toJson()))
        value.getJSONObject("sites").put("example.org", "true")
        assertTrue(runCatching { DevicePolicy.parse(value) }.isFailure)
        value.getJSONObject("sites").remove("example.org")
        value.getJSONObject("sites").put("127.0.0.1", true)
        assertTrue(runCatching { DevicePolicy.parse(value) }.isFailure)
    }
}
