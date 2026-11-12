package com.yidream.mdm

import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.net.URI
import java.time.Duration

/** This activity has no exported entry point and cannot access pages outside the device's allowlist. */
class ManagedBrowserActivity : AppCompatActivity() {
    private lateinit var address: EditText
    private lateinit var open: Button
    private lateinit var status: TextView
    private lateinit var pageContainer: FrameLayout
    private var reader: WebView? = null
    private data class ReaderPolicy(val rules: DevicePolicy, val leaseDeadline: Long?) {
        fun leaseRemaining(): Long = leaseDeadline?.let { it - SystemClock.elapsedRealtime() } ?: Long.MAX_VALUE
    }
    @Volatile private var policy: ReaderPolicy? = null
    private var poll: Job? = null
    private var pageLoad: Job? = null
    private var pageExpiry: Job? = null
    private var currentPage: URI? = null
    private var initialAddress: String? = null

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialAddress = intent.getStringExtra("site_address")
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            setBackgroundResource(R.drawable.bg_app_gradient)
        }
        val back = button(getString(R.string.managed_sites_back), primary = false).apply {
            setOnClickListener { finish() }
        }
        layout.addView(back, LinearLayout.LayoutParams(-2, -2))
        address = EditText(this).apply {
            hint = getString(R.string.mdm_site_url)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setBackgroundResource(R.drawable.ui_input)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        open = button(getString(R.string.managed_sites_open), primary = true).apply {
            isEnabled = false
            setOnClickListener { navigate(address.text.toString()) }
        }
        layout.addView(LinearLayout(this).apply {
            addView(address, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(open, LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(8) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        status = TextView(this).apply {
            text = getString(R.string.managed_sites_loading_rules)
            textSize = 13f
            setPadding(0, dp(8), 0, dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        layout.addView(status)
        pageContainer = FrameLayout(this)
        layout.addView(pageContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
    }

    private fun newReader() = WebView(this).apply {
        settings.javaScriptEnabled = false
        settings.domStorageEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.blockNetworkLoads = true
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.setSupportMultipleWindows(false)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.isForMainFrame && request.hasGesture() && request.method == "GET") {
                    view.post { if (reader === view) navigate(request.url.toString()) }
                }
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(),
                    ByteArrayInputStream(byteArrayOf()))
        }
        setDownloadListener { _, _, _, _, _ -> status.setText(R.string.managed_sites_unsupported) }
    }

    override fun onResume() {
        super.onResume()
        status.setText(R.string.managed_sites_loading_rules)
        poll = lifecycleScope.launch {
            while (isActive) {
                when (val result = RemoteDevicePolicy.sync(applicationContext)) {
                    is PolicySync.Accepted -> acceptPolicy(result.policy)
                    is PolicySync.Cached -> acceptPolicy(result.policy, result.leaseDeadlineElapsed)
                    PolicySync.Denied, PolicySync.NotEnrolled -> {
                        discardPolicy()
                        finish()
                        break
                    }
                    PolicySync.Unavailable -> discardPolicy()
                }
                delay(RemoteDevicePolicy.FOREGROUND_INTERVAL_MS)
            }
        }
    }

    private fun acceptPolicy(accepted: DevicePolicy, leaseDeadline: Long? = null) {
        val first = policy == null
        val next = ReaderPolicy(accepted, leaseDeadline)
        if (next.leaseRemaining() <= 0) {
            discardPolicy()
            return
        }
        policy = next
        open.isEnabled = true
        if (currentPage != null) {
            watchPageExpiry()
        } else if (first) {
            status.setText(R.string.managed_sites_limits)
            initialAddress?.let {
                initialAddress = null
                navigate(it)
            }
        }
    }

    private fun discardPolicy() {
        policy = null
        open.isEnabled = false
        clearPage()
        status.setText(R.string.managed_sites_rules_unavailable)
    }

    private fun permits(address: URI): Boolean =
        policy?.let { it.leaseRemaining() > 0 && it.rules.permits(address) } == true

    private fun navigate(input: String) {
        val selected = runCatching {
            val value = input.trim()
            URI(if (value.contains("://")) value else "https://$value")
        }.getOrNull()
        if (selected == null || !permits(selected)) {
            clearPage()
            status.setText(R.string.managed_sites_blocked)
            return
        }
        clearPage()
        status.setText(R.string.mdm_loading)
        address.setText(selected.toASCIIString())
        pageLoad = lifecycleScope.launch {
            try {
                val page = ManagedWebPage.load(selected, ::permits)
                if (!permits(page.address)) {
                    clearPage()
                    status.setText(R.string.managed_sites_blocked)
                    return@launch
                }
                currentPage = page.address
                address.setText(page.address.toASCIIString())
                reader = newReader().also { webView ->
                    pageContainer.addView(webView, FrameLayout.LayoutParams(-1, -1))
                    webView.loadDataWithBaseURL(page.address.toASCIIString(), page.html, "text/html", "UTF-8", null)
                }
                watchPageExpiry()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                clearPage()
                status.setText(when (error.message) {
                    "SITE_BLOCKED" -> R.string.managed_sites_blocked
                    "SITE_UNSUPPORTED" -> R.string.managed_sites_unsupported
                    else -> R.string.managed_sites_unavailable
                })
            }
        }
    }

    private fun watchPageExpiry(afterDeadline: Boolean = false) {
        pageExpiry?.cancel()
        pageExpiry = null
        val page = currentPage ?: return
        val active = policy ?: return
        val leaseRemaining = active.leaseRemaining()
        if (leaseRemaining <= 0) {
            discardPolicy()
            return
        }
        val access = active.rules.siteAccess(page)
        if (!access.allowed) {
            clearPage()
            status.setText(if (afterDeadline) R.string.site_window_expired else R.string.managed_sites_blocked)
            return
        }
        val deadline = access.expiresAt
        status.text = if (deadline == null) getString(R.string.managed_sites_limits)
            else getString(R.string.site_window_allowed_until, formatSiteWindowTime(this, deadline))
        val untilWindowCloses = deadline?.let { Duration.between(active.rules.currentTime(), it).toMillis() }
        val untilPageCloses = minOf(leaseRemaining, untilWindowCloses ?: Long.MAX_VALUE)
        if (untilPageCloses != Long.MAX_VALUE) {
            pageExpiry = lifecycleScope.launch {
                delay(untilPageCloses.coerceAtLeast(1L))
                watchPageExpiry(afterDeadline = true)
            }
        }
    }

    private fun clearPage() {
        pageExpiry?.cancel()
        pageExpiry = null
        pageLoad?.cancel()
        pageLoad = null
        currentPage = null
        reader?.let { webView ->
            pageContainer.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
        reader = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && currentPage != null) watchPageExpiry()
    }

    override fun onPause() {
        poll?.cancel()
        poll = null
        policy = null
        open.isEnabled = false
        initialAddress = currentPage?.toASCIIString() ?: initialAddress
        clearPage()
        super.onPause()
    }

    private fun button(label: String, primary: Boolean) = Button(this).apply {
        text = label
        isAllCaps = false
        backgroundTintList = null
        stateListAnimator = null
        setBackgroundResource(if (primary) R.drawable.ui_primary_button else R.drawable.ui_secondary_button)
        setTextColor(getColor(if (primary) R.color.white else R.color.accent_blue))
    }
}
