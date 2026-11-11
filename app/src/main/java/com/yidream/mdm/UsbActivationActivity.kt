package com.yidream.mdm

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock

/** Android allows the authorized ADB shell to reach this entry point via DUMP. */
class UsbActivationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = runCatching { intent.getStringExtra(EXTRA) }.getOrNull()
        intent.removeExtra(EXTRA)
        val code = raw?.takeIf { it.length == 32 }
            ?.let { runCatching { AccountInput.administratorCode(it) }.getOrNull() }
        if (code != null && DeviceOwnerManager(this).isDeviceOwner()) {
            UsbActivation.offer(code)
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
    }

    companion object {
        const val EXTRA = "yidream_activation_code"
    }
}

/** Keep the code in this process until the main activity reaches the foreground. */
internal object UsbActivation {
    private var pendingCode: String? = null
    private var offeredAt = 0L

    fun offer(code: String) {
        pendingCode = code
        offeredAt = SystemClock.elapsedRealtime()
    }

    fun take(): String? {
        val code = pendingCode
        pendingCode = null
        return code?.takeIf { SystemClock.elapsedRealtime() - offeredAt < 120_000L }
    }
}
