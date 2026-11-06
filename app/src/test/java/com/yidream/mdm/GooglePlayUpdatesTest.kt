package com.yidream.mdm

import android.app.Activity
import android.content.Context
import android.os.UserManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GooglePlayUpdatesTest {
    private val pkg = "com.whatsapp"
    private val listing = "https://play.google.com/store/apps/details?id=$pkg"

    @Test fun onlyTheApprovedExactPlayPackageCanBeOpened() {
        assertEquals(listing, GooglePlayUpdateSource.listing(listing + "&hl=fr", pkg))
        assertNull(GooglePlayUpdateSource.listing(listing, "com.waze"))
        listOf(
            "http://play.google.com/store/apps/details?id=$pkg",
            "https://play.google.com.evil.example/store/apps/details?id=$pkg",
            "https://user@play.google.com/store/apps/details?id=$pkg",
            "https://play.google.com:444/store/apps/details?id=$pkg",
            listing + "&id=com.waze",
            listing + "&%69d=$pkg",
            listing + "#other",
            "https://play.google.com/store/search?q=$pkg",
            "https://play.google.com/store/apps/details?id=%ZZ",
            "https://example.org/$pkg.apk"
        ).forEach { assertNull(it, GooglePlayUpdateSource.listing(it, pkg)) }
        assertNull(GooglePlayUpdateSource.listing(listing, "com.whatsapp&other=package"))
    }

    @Test fun missingPlayCannotFallBackToABrowserOrClaimAnUpdate() {
        val context = RuntimeEnvironment.getApplication()
        val play = GooglePlayUpdates(context)
        assertFalse(play.available(listing))
        assertFalse(play.open(listing))
        assertNull(shadowOf(context).nextStartedActivity)
        val intent = play.intent(listing)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("com.android.vending", intent.`package`)
        assertEquals(listing, intent.data.toString())
        assertNull(intent.extras)
    }

    @Test fun visiblePlayReceivesOnlyTheCanonicalListingWithoutCredentials() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val context = controller.get()
        val play = GooglePlayUpdates(context)
        val component = ComponentName("com.android.vending", "StoreActivity")
        val resolve = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = component.packageName
                name = component.className
                enabled = true
                applicationInfo = ApplicationInfo().apply {
                    packageName = component.packageName
                    enabled = true
                }
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(play.intent(listing), resolve)
        assertTrue(play.available(listing))
        assertTrue(play.open(listing))
        val started = shadowOf(context).nextStartedActivity
        assertEquals(listing, started.data.toString())
        assertEquals("com.android.vending", started.`package`)
        assertNull(started.extras)
        val users = context.getSystemService(Context.USER_SERVICE) as UserManager
        shadowOf(users).setUserRestriction(android.os.Process.myUserHandle(), UserManager.DISALLOW_INSTALL_APPS, true)
        assertFalse("Opening Play must never bypass an administrator install restriction", play.available(listing))
        assertFalse(play.open(listing))
        assertTrue(users.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS))
        controller.pause().stop().destroy()
    }
}
