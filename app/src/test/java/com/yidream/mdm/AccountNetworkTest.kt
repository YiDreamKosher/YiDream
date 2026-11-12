package com.yidream.mdm

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountNetworkTest {
    @Test fun networkWithoutAndroidValidationStillAttemptsAccountVerification() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val connectivity = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
        val network = requireNotNull(connectivity.activeNetwork)
        val capabilities = NetworkCapabilities.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        shadowOf(connectivity).setNetworkCapabilities(network, capabilities)

        assertTrue(InternetReachability.hasInternetRoute(context))
        val error = runCatching { AppAccountService(context).validateSession() }.exceptionOrNull()
        assertEquals("SIGN_IN_REQUIRED", error?.message)
    }

    @Test fun localOnlyNetworkDoesNotCountAsAnInternetRoute() {
        val context = RuntimeEnvironment.getApplication()
        val connectivity = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
        val network = requireNotNull(connectivity.activeNetwork)
        shadowOf(connectivity).setNetworkCapabilities(network, NetworkCapabilities.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build())

        assertFalse(InternetReachability.hasInternetRoute(context))
    }
}
