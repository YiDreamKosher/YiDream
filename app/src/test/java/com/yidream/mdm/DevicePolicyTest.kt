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
import java.time.Instant

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
    fun temporaryAccessExpiresAndUsesTheBaseRuleAtItsDeadline() {
        val deadline = Instant.parse("2026-11-12T12:00:00Z")
        val policy = DevicePolicy(
            8, emptyMap(),
            mapOf("always.example.org" to true, "blocked.example.org" to false, "private.trip.example.org" to false),
            mapOf("always.example.org" to deadline, "blocked.example.org" to deadline, "trip.example.org" to deadline)
        )
        val before = deadline.minusMillis(1)
        val trip = URI("https://news.trip.example.org/")
        val blocked = URI("https://blocked.example.org/")
        val permanent = URI("https://always.example.org/")
        assertEquals(DevicePolicy.SiteAccess(true, deadline), policy.siteAccess(trip, before))
        assertEquals(DevicePolicy.SiteAccess(true, deadline), policy.siteAccess(blocked, before))
        assertFalse(policy.siteAccess(URI("https://private.trip.example.org/"), before).allowed)
        assertFalse(policy.siteAccess(trip, deadline).allowed)
        assertFalse(policy.siteAccess(blocked, deadline.plusSeconds(1)).allowed)
        assertEquals(DevicePolicy.SiteAccess(true), policy.siteAccess(permanent, deadline))
    }

    @Test
    fun v2PolicyRequiresAnAuthoritativeClockAndWindowRules() {
        val value = JSONObject("""{"revision":4,"apps":{},"sites":{},"server_time":"2026-11-12T11:00:00+00:00"}""")
        assertTrue(runCatching { DevicePolicy.parse(value, requireWindows = true) }.isFailure)
        value.put("temporary_sites", JSONObject().put("example.org", "2026-11-12T12:00:00+00:00"))
        val policy = DevicePolicy.parse(value, requireWindows = true)
        assertTrue(policy.permits(URI("https://example.org/")))
        value.remove("server_time")
        assertTrue(runCatching { DevicePolicy.parse(value, requireWindows = true) }.isFailure)
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
