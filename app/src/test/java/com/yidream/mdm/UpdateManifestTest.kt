package com.yidream.mdm

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateManifestTest {
    private fun manifest() = JSONObject().put("versionCode", 18).put("versionName", "v5.10.0")
        .put("apkUrl", UpdateManager.APPROVED_APK_URL).put("apkSha256", "a".repeat(64))

    @Test fun acceptsNewOfficialHostAndOnlyOffersHigherVersion() {
        assertEquals("https://yidreamkosher.github.io/YiDream/version.json", UpdateManager.MANIFEST_URL)
        val result = UpdateManager.evaluateManifest(manifest().toString(), 17)
        assertTrue(result is UpdateManager.CheckResult.Available)
        assertEquals(18, (result as UpdateManager.CheckResult.Available).update.versionCode)
        for (installed in listOf(18L, 19L))
            assertEquals(UpdateManager.CheckResult.UpToDate, UpdateManager.evaluateManifest(manifest().toString(), installed))
    }

    @Test fun invalidOrForeignManifestIsUnavailableAndNeverClaimsUpToDate() {
        for (url in listOf("https://qinfrance.github.io/YiDream/admin/yidream.apk",
            UpdateManager.APPROVED_APK_URL + "?source=other", "https://example.org/app.apk")) {
            assertEquals(UpdateManager.CheckResult.Unavailable,
                UpdateManager.evaluateManifest(manifest().put("apkUrl", url).toString(), 18))
        }
        for (sha in listOf("", "a".repeat(63), "z".repeat(64)))
            assertEquals(UpdateManager.CheckResult.Unavailable,
                UpdateManager.evaluateManifest(manifest().put("apkSha256", sha).toString(), 18))
        for (body in listOf("{}", "not json", manifest().put("versionCode", -1).toString(),
            manifest().put("versionName", "").toString(), manifest().put("versionCode", Long.MAX_VALUE).toString()))
            assertEquals(UpdateManager.CheckResult.Unavailable, UpdateManager.evaluateManifest(body, 17))
    }
}
