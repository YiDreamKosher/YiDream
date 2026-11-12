package com.yidream.mdm

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineRequestsTest {
    private val day = 86_400_000L
    private val checkedAt = Instant.parse("2026-11-12T12:00:00Z").toEpochMilli()
    private val clock = OfflineAccountLease.Clock(checkedAt, 1000L, 2)
    private val account = AppAccess(AppAccount("account", "user@example.org", "+33123456789", false), "phone")
    private val scope = requireNotNull(account.scope())

    private fun session(expiry: Long = checkedAt + 30 * day) = JSONObject().put("kind", "code")
        .put("expires_at", Instant.ofEpochMilli(expiry).toString())
        .put("device_id", "phone").put("device_token", "a".repeat(64))

    @Test fun offlineLeaseRequiresConfirmedSamePhoneAndHonorsBothExpirations() {
        assertNull(OfflineAccountLease.read(session(), clock))
        val valid = OfflineAccountLease.confirm(session(), account, clock)
        val offline = requireNotNull(OfflineAccountLease.read(valid, clock))
        assertEquals(scope, offline.scope())
        assertTrue(offline.offline)
        assertEquals(checkedAt + 7 * day, requireNotNull(offline.offlineUntil))
        assertNull(OfflineAccountLease.read(valid, clock.copy(wall = checkedAt + 7 * day)))
        assertNull(OfflineAccountLease.read(valid, clock.copy(elapsed = clock.elapsed + 7 * day)))
        assertNull(OfflineAccountLease.read(valid, clock.copy(wall = checkedAt - 1)))
        assertNull(OfflineAccountLease.read(valid.put("device_id", "other-phone"), clock))
        val shorter = OfflineAccountLease.confirm(session(checkedAt + day), account, clock)
        assertNull(OfflineAccountLease.read(shorter, clock.copy(wall = checkedAt + day)))
    }

    @Test fun interruptedSendReplaysItsOriginalIdAndOnlyRemovesAcknowledgedRequest() = runBlocking {
        val site = QueuedDeviceRequest(scope, UUID.randomUUID().toString(), "https://example.org/", "",
            QueuedDeviceRequest.Kind.SITE, allowed = false)
        val pending = mutableListOf(QueuedDeviceRequest.read(JSONObject(site.toJson().toString())))
        val sentIds = mutableListOf<String>()
        val send: suspend (QueuedDeviceRequest) -> Unit = {
            sentIds.add(it.id)
            if (sentIds.size == 1) throw IOException("response lost")
            assertEquals(scope, it.scope)
            assertFalse(it.allowed)
        }
        val update: (QueuedDeviceRequest, QueuedDeviceRequest?) -> Unit = { previous, next ->
            pending.remove(previous)
            if (next != null) pending.add(next)
        }
        assertFalse(OfflineRequests.dispatchPending(pending.toList(), send, update, {}))
        assertEquals(listOf(site), pending)
        assertTrue(OfflineRequests.dispatchPending(pending.toList(), send, update, {}))
        assertTrue(pending.isEmpty())
        assertEquals(listOf(site.id, site.id), sentIds)
    }

    @Test fun temporarySiteRequestKeepsItsDeadlineAndStopsRetryingWhenExpired() = runBlocking {
        val until = Instant.parse("2026-11-12T12:00:00Z")
        val request = QueuedDeviceRequest(scope, UUID.randomUUID().toString(), "https://example.org/", "",
            QueuedDeviceRequest.Kind.SITE, requestedUntil = until)
        val restored = QueuedDeviceRequest.read(JSONObject(request.toJson().toString()))
        assertEquals(request, restored)
        val duration = request.copy(requestedUntil = null, requestedHours = 24)
        assertEquals(duration, QueuedDeviceRequest.read(JSONObject(duration.toJson().toString())))
        var failed: QueuedDeviceRequest? = null
        assertTrue(OfflineRequests.dispatchPending(listOf(restored), { sent ->
            assertEquals(request.id, sent.id)
            error("INVALID_SITE_WINDOW")
        }, { _, replacement -> failed = replacement }, {}))
        assertEquals(request.copy(failed = true), failed)
    }

    @Test fun explicitDenialStopsRemainingRequests() = runBlocking {
        val app = QueuedDeviceRequest(scope, UUID.randomUUID().toString(), "com.example.app", "",
            QueuedDeviceRequest.Kind.APP)
        var sent = 0
        var denied = false
        assertTrue(OfflineRequests.dispatchPending(listOf(app, app.copy(id = UUID.randomUUID().toString())), {
            sent++
            error("APP_ACCESS_DENIED")
        }, { _, _ -> fail("A denied request cannot be treated as delivered") }, { denied = true }))
        assertEquals(1, sent)
        assertTrue(denied)
    }
}
