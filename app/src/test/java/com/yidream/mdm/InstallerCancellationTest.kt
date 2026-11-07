package com.yidream.mdm

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InstallerCancellationTest {
    @Test fun cancellationDuringABlockingReadCannotWriteOrReachTheInstaller() = runBlocking {
        val output = ByteArrayOutputStream()
        var rejected = false
        var reachedInstaller = false
        val worker = launch(start = CoroutineStart.UNDISPATCHED) {
            val job = currentCoroutineContext()[Job]!!
            val input = object : InputStream() {
                override fun read(): Int = error("Buffered read is required")
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    // Models a blocking Java read returning after logout cancelled its job.
                    job.cancel()
                    bytes[offset] = 42
                    return 1
                }
            }
            try {
                copyApkBytes(input, output)
                reachedInstaller = true
            } catch (_: CancellationException) { rejected = true }
        }
        worker.join()
        assertTrue(rejected)
        assertFalse(reachedInstaller)
        assertEquals(0, output.size())
    }

    @Test fun anAlreadyCancelledCallerCannotCreateAnAndroidInstallSession() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val before = context.packageManager.packageInstaller.allSessions.size
        var rejected = false
        var result: SilentInstaller.Result? = null
        val worker = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            try { result = PackageSessionInstaller(context).install(java.io.File(context.cacheDir, "unused.apk"), context.packageName, 1L) }
            catch (_: CancellationException) { rejected = true }
        }
        worker.join()
        assertTrue("Cancellation must propagate before APK parsing/session creation", rejected)
        assertNull(result)
        assertEquals(before, context.packageManager.packageInstaller.allSessions.size)
    }

    @Test fun overlappingUpdateAttemptsOwnIndependentCacheFiles() {
        val cache = RuntimeEnvironment.getApplication().cacheDir
        val first = createUpdateApkFile(cache)
        val second = createUpdateApkFile(cache)
        try {
            assertNotEquals(first.canonicalPath, second.canonicalPath)
            first.writeText("first attempt")
            second.writeText("second attempt")
            assertTrue(first.delete())
            assertEquals("Cancelling one attempt cannot remove or change the other", "second attempt", second.readText())
        } finally { first.delete(); second.delete() }
    }

    @Test fun anActiveCopyPreservesBytesAndDigestAndEnforcesItsSizeLimit() = runBlocking {
        val bytes = "verified APK bytes".toByteArray()
        val output = ByteArrayOutputStream()
        val digest = MessageDigest.getInstance("SHA-256")
        assertEquals(bytes.size.toLong(), copyApkBytes(ByteArrayInputStream(bytes), output, digest = digest))
        assertArrayEquals(bytes, output.toByteArray())
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes), digest.digest())
        try {
            copyApkBytes(ByteArrayInputStream(bytes), ByteArrayOutputStream(), maxBytes = bytes.size.toLong() - 1L)
            fail("Oversized APK data must be rejected")
        } catch (_: IllegalArgumentException) { /* Expected bounded-download rejection. */ }
    }
}
