package com.yidream.mdm

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
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
import java.util.Locale

internal data class DevicePolicy(
    val revision: Long,
    val apps: Map<String, Boolean>,
    val sites: Map<String, Boolean>
) {
    fun permits(address: URI): Boolean {
        if (!isWebAddress(address)) return false
        var host = address.host.lowercase(Locale.ROOT)
        while (true) {
            sites[host]?.let { return it }
            if (!host.contains('.')) return false
            host = host.substringAfter('.')
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("revision", revision).put("apps", JSONObject(apps)).put("sites", JSONObject(sites))

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

        fun parse(value: JSONObject): DevicePolicy {
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
            return DevicePolicy(revision, rules("apps", 100, ::validPackageName), rules("sites", 200, ::validHost))
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

    private fun preferences(context: Context) =
        context.getSharedPreferences("yidream_device_policy", Context.MODE_PRIVATE)

    fun current(context: Context): DevicePolicy? {
        val device = MdmService(context).deviceCredential() ?: return null
        val cached = preferences(context)
        if (cached.getString("device", null) != device.first) return null
        return runCatching { DevicePolicy.parse(JSONObject(cached.getString("policy", null) ?: return null)) }.getOrNull()
    }

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
                .putString("device", device.first)
                .putString("policy", policy.toJson().toString())
                .commit()
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
