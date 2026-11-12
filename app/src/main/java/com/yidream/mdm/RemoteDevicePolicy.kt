package com.yidream.mdm

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

internal data class DevicePolicy(
    val revision: Long,
    val apps: Map<String, Boolean>,
    val sites: Map<String, Boolean>,
    val temporarySites: Map<String, Instant> = emptyMap()
) {
    data class SiteAccess(val allowed: Boolean, val expiresAt: Instant? = null)

    private var timeAtFetch = Instant.now()
    private val elapsedAtFetch = SystemClock.elapsedRealtime()

    fun currentTime(): Instant = timeAtFetch.plusMillis((SystemClock.elapsedRealtime() - elapsedAtFetch).coerceAtLeast(0))

    fun permits(address: URI): Boolean = siteAccess(address).allowed

    fun siteAccess(address: URI, now: Instant = currentTime()): SiteAccess {
        if (!isWebAddress(address)) return SiteAccess(false)
        var host = address.host.lowercase(Locale.ROOT)
        while (true) {
            temporarySites[host]?.takeIf { it > now }?.let { return SiteAccess(true, it) }
            sites[host]?.let { return SiteAccess(it) }
            if (!host.contains('.')) return SiteAccess(false)
            host = host.substringAfter('.')
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("revision", revision).put("apps", JSONObject(apps)).put("sites", JSONObject(sites))
        .put("temporary_sites", JSONObject(temporarySites.mapValues { it.value.toString() }))

    companion object {
        private val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        private val dnsLabel = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")

        fun validPackageName(name: String) = name.length <= 255 && packageName.matches(name)

        private fun validHost(host: String): Boolean {
            if (host.length !in 3..253 || !host.contains('.')) return false
            val labels = host.split('.')
            return labels.last().any { it in 'a'..'z' } && labels.all(dnsLabel::matches)
        }

        fun isWebAddress(address: URI): Boolean {
            val host = address.host?.lowercase(Locale.ROOT) ?: return false
            return address.scheme.equals("https", ignoreCase = true) && address.rawUserInfo == null &&
                address.port in listOf(-1, 443) && validHost(host) && address.toASCIIString().length <= 2048
        }

        fun parse(value: JSONObject, requireWindows: Boolean = false): DevicePolicy {
            val revision = when (val number = value.get("revision")) {
                is Int -> number.toLong()
                is Long -> number
                else -> error("INVALID_POLICY")
            }
            require(revision in 0..9_007_199_254_740_991L)
            fun rules(name: String, limit: Int, valid: (String) -> Boolean): Map<String, Boolean> {
                val objectRules = value.getJSONObject(name)
                require(objectRules.length() <= limit)
                return objectRules.keys().asSequence().associateWith { key ->
                    require(valid(key))
                    objectRules.get(key) as? Boolean ?: error("INVALID_POLICY")
                }
            }
            val windows = if (requireWindows) value.getJSONObject("temporary_sites")
                else value.optJSONObject("temporary_sites") ?: JSONObject()
            require(windows.length() <= 1000)
            val temporarySites = windows.keys().asSequence().associateWith { host ->
                require(validHost(host))
                parseSiteWindowTime(windows.getString(host))
            }
            require(!value.isNull("server_time") || (!requireWindows && temporarySites.isEmpty()))
            val serverTime = if (value.isNull("server_time")) Instant.now()
                else parseSiteWindowTime(value.getString("server_time"))
            return DevicePolicy(revision, rules("apps", 100, ::validPackageName),
                rules("sites", 200, ::validHost), temporarySites).apply { timeAtFetch = serverTime }
        }
    }
}

internal sealed interface PolicySync {
    data class Accepted(val policy: DevicePolicy) : PolicySync
    data object Unavailable : PolicySync
    data object NotEnrolled : PolicySync
    data object Denied : PolicySync
}

/** Android retains its installed restrictions while offline. Only the current device can fetch its rules. */
internal object RemoteDevicePolicy {
    private const val JOB_ID = 181092
    private const val SYNC_INTERVAL_MS = 15 * 60_000L
    const val FOREGROUND_INTERVAL_MS = 30_000L
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foreground: Job? = null
    @Volatile private var latest: Pair<String, DevicePolicy>? = null

    private fun preferences(context: Context) =
        context.getSharedPreferences("yidream_device_policy", Context.MODE_PRIVATE)

    fun current(context: Context): DevicePolicy? {
        val device = MdmService(context).deviceCredential() ?: return null
        val credential = credentialHash(device.second)
        latest?.takeIf { it.first == credential }?.let { return it.second }
        val cached = preferences(context)
        if (cached.getInt("format", 0) != 2 || cached.getString("device", null) != device.first ||
            cached.getString("credential", null) != credential) return null
        // The persisted snapshot helps show base rules. Only a live policy has a trusted clock for temporary access.
        return runCatching {
            DevicePolicy.parse(JSONObject(cached.getString("policy", null) ?: return null))
                .copy(temporarySites = emptyMap())
        }.getOrNull()
    }

    private fun credentialHash(token: String): String = MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun start(context: Context) {
        val app = context.applicationContext
        pause()
        if (!DeviceOwnerManager(app).isDeviceOwner() || !MdmService(app).paired) return
        val scheduler = app.getSystemService(JobScheduler::class.java)
        if (scheduler != null && scheduler.getPendingJob(JOB_ID) == null) {
            scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(app, DevicePolicyJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(SYNC_INTERVAL_MS)
                .build())
        }
        foreground = scope.launch {
            while (isActive) {
                val outcome = sync(app)
                if (outcome == PolicySync.Denied || outcome == PolicySync.NotEnrolled) break
                delay(FOREGROUND_INTERVAL_MS)
            }
        }
    }

    fun pause() {
        foreground?.cancel()
        foreground = null
    }

    fun cancel(context: Context) {
        pause()
        latest = null
        context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
    }

    suspend fun sync(context: Context, renewSession: Boolean = false): PolicySync = withContext(Dispatchers.IO) {
        mutex.withLock { synchronize(context, renewSession) }
    }

    private suspend fun synchronize(context: Context, renewSession: Boolean): PolicySync {
        val owner = DeviceOwnerManager(context)
        val service = MdmService(context)
        val device = service.deviceCredential() ?: return PolicySync.NotEnrolled
        if (!owner.isDeviceOwner()) return PolicySync.NotEnrolled
        return try {
            if (renewSession) AppAccountService(context).validateSession()
            val policy = service.devicePolicy(device)
            currentCoroutineContext().ensureActive()
            if (service.deviceCredential() != device || !owner.isDeviceOwner()) return PolicySync.NotEnrolled
            if ((current(context)?.revision ?: 0) > policy.revision) return PolicySync.Unavailable

            var applied = true
            for ((name, allowed) in policy.apps) {
                currentCoroutineContext().ensureActive()
                if (service.deviceCredential() != device || !owner.isDeviceOwner()) return PolicySync.NotEnrolled
                if (!owner.applyRemoteAppRule(name, allowed)) applied = false
            }
            currentCoroutineContext().ensureActive()
            if (service.deviceCredential() != device || !owner.isDeviceOwner()) return PolicySync.NotEnrolled
            // Commit locally before acknowledging. A failed acknowledgement is retried on the next sync.
            val stored = preferences(context).edit()
                .putInt("format", 2)
                .putString("device", device.first)
                .putString("credential", credentialHash(device.second))
                .putString("policy", policy.copy(temporarySites = emptyMap()).toJson().toString())
                .commit()
            if (stored) latest = credentialHash(device.second) to policy
            try {
                service.reportPolicy(device, policy.revision, applied && stored)
            } catch (error: Exception) {
                if (error is CancellationException || error.message == "APP_ACCESS_DENIED" ||
                    error.message == "SESSION_CHANGED" || error.message == "SESSION_EXPIRED") throw error
            }
            if (stored) PolicySync.Accepted(policy) else PolicySync.Unavailable
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            when (error.message) {
                "APP_ACCESS_DENIED", "SESSION_EXPIRED", "SIGN_IN_REQUIRED" -> PolicySync.Denied
                "SESSION_CHANGED" -> PolicySync.NotEnrolled
                else -> PolicySync.Unavailable
            }
        }
    }
}

class DevicePolicyJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sync: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        sync = scope.launch {
            val result = RemoteDevicePolicy.sync(applicationContext, renewSession = true)
            jobFinished(params, result == PolicySync.Unavailable)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        sync?.cancel()
        sync = null
        return true
    }

    override fun onDestroy() {
        sync?.cancel()
        super.onDestroy()
    }
}
