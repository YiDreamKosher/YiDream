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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

internal data class DeviceRequestScope(val accountId: String, val deviceId: String)

internal data class QueuedDeviceRequest(
    val scope: DeviceRequestScope,
    val id: String,
    val target: String,
    val reason: String,
    val kind: Kind,
    val allowed: Boolean = true,
    val failed: Boolean = false,
    val requestedHours: Int? = null,
    val requestedUntil: Instant? = null
) {
    enum class Kind { SITE, APP }

    init {
        if (requestedHours != null || requestedUntil != null) {
            require(kind == Kind.SITE && allowed && (requestedHours == null) != (requestedUntil == null))
            require(requestedHours == null || requestedHours == 24)
        }
    }

    fun toJson(): JSONObject = JSONObject().put("account", scope.accountId).put("device", scope.deviceId)
        .put("id", id).put("target", target).put("reason", reason).put("kind", kind.name)
        .put("allowed", allowed).put("failed", failed)
        .put("requested_hours", requestedHours).put("requested_until", requestedUntil?.toString())

    companion object {
        fun read(value: JSONObject): QueuedDeviceRequest = QueuedDeviceRequest(
            DeviceRequestScope(value.getString("account"), value.getString("device")),
            value.getString("id"), value.getString("target"), value.getString("reason"),
            Kind.valueOf(value.getString("kind")), value.getBoolean("allowed"), value.getBoolean("failed"),
            requestedHours = if (value.isNull("requested_hours")) null else value.getInt("requested_hours"),
            requestedUntil = if (value.isNull("requested_until")) null else Instant.parse(value.getString("requested_until"))
        )
    }
}

/** Persist the idempotency key before sending. An interrupted POST can replay the same ID. */
internal object OfflineRequests {
    private const val JOB_ID = 181093
    private const val MAX_REQUESTS = 50
    private val storageLock = Any()
    private val syncLock = Mutex()

    private fun load(store: AppSessionStore): List<QueuedDeviceRequest> {
        val items = store.read()?.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            runCatching { QueuedDeviceRequest.read(items.getJSONObject(i)) }.getOrNull()
        }
    }

    private fun save(store: AppSessionStore, items: List<QueuedDeviceRequest>) {
        if (items.isEmpty()) store.clear()
        else store.write(JSONObject().put("items", JSONArray(items.map { it.toJson() })))
    }

    fun pending(context: Context, scope: DeviceRequestScope): List<QueuedDeviceRequest> = synchronized(storageLock) {
        load(AppSessionStore(context, requests = true)).filter { it.scope == scope }
    }

    fun enqueue(context: Context, request: QueuedDeviceRequest) {
        check(DeviceOwnerManager(context).isDeviceOwner()) { "APP_ACCESS_DENIED" }
        require(request.reason.length <= 2000) { "REQUEST_REASON_TOO_LONG" }
        synchronized(storageLock) {
            check(AppAccountService(context).confirmQueueScope(request.scope)) { "APP_ACCESS_DENIED" }
            val store = AppSessionStore(context, requests = true)
            val items = load(store).filter { it.scope == request.scope }
            if (items.none { it.id == request.id }) {
                check(items.size < MAX_REQUESTS) { "REQUEST_QUEUE_FULL" }
                save(store, items + request)
            }
        }
        schedule(context)
    }

    fun discard(context: Context, request: QueuedDeviceRequest) {
        update(context, request, null)
    }

    private fun update(context: Context, request: QueuedDeviceRequest, replacement: QueuedDeviceRequest?) {
        synchronized(storageLock) {
            val store = AppSessionStore(context, requests = true)
            val items = load(store).mapNotNull { current ->
                if (current.scope == request.scope && current.id == request.id) replacement else current
            }
            save(store, items)
        }
    }

    fun schedule(context: Context) {
        val hasPending = synchronized(storageLock) {
            load(AppSessionStore(context, requests = true)).any { !it.failed }
        }
        if (!hasPending) return
        val app = context.applicationContext
        val scheduler = app.getSystemService(JobScheduler::class.java) ?: return
        if (scheduler.getPendingJob(JOB_ID) == null) {
            scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(app, OfflineRequestSyncJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build())
        }
    }

    /** True means there is no retryable work for this enrollment. */
    suspend fun flush(context: Context): Boolean = syncLock.withLock {
        val queued = synchronized(storageLock) {
            load(AppSessionStore(context, requests = true)).any { !it.failed }
        }
        if (!queued) return@withLock true
        if (!DeviceOwnerManager(context).isDeviceOwner()) return@withLock true
        val account = AppAccountService(context)
        if (!account.hasSession) return@withLock true
        if (!InternetReachability.hasInternetRoute(context)) return@withLock false
        val scope = try {
            account.validateOnlineSession().scope() ?: return@withLock true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return@withLock !account.hasSession
        }
        val service = MdmService(context)
        val complete = dispatchPending(pending(context, scope), service::sendQueuedRequest,
            { request, replacement -> update(context, request, replacement) },
            { account.invalidateQueueScope(scope) })
        complete && (!account.confirmQueueScope(scope) || pending(context, scope).none { !it.failed })
    }

    internal suspend fun dispatchPending(
        pending: List<QueuedDeviceRequest>,
        send: suspend (QueuedDeviceRequest) -> Unit,
        update: (QueuedDeviceRequest, QueuedDeviceRequest?) -> Unit,
        denied: suspend () -> Unit
    ): Boolean {
        var retry = false
        for (request in pending.filterNot { it.failed }) {
            try {
                send(request)
                update(request, null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                when (error.message) {
                    "INVALID_APP_REQUEST", "INVALID_SITE_REQUEST", "INVALID_SITE",
                    "INVALID_SITE_WINDOW", "SITE_WINDOW_EXPIRED", "REQUESTS_DISABLED" ->
                        update(request, request.copy(failed = true))
                    "SITE_REQUEST_LIMIT", "APP_REQUEST_LIMIT" -> retry = true
                    "APP_ACCESS_DENIED" -> { denied(); return true }
                    "SESSION_CHANGED" -> return true
                    else -> return false
                }
            }
        }
        return !retry
    }
}

class OfflineRequestSyncJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch(Dispatchers.IO) {
            val complete = try {
                OfflineRequests.flush(applicationContext)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
            jobFinished(params, !complete)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
