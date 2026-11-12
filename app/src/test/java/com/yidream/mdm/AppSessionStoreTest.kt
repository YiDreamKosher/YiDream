package com.yidream.mdm

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class AppSessionStoreTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val key = SecretKeySpec(ByteArray(32), "AES")

    @Test fun sessionRotationCannotMixCiphertextAndIv() {
        val writer = AppSessionStore(app, testKey = { key })
        writer.write(session("previous"))
        val reader = AppSessionStore(app, testKey = {
            writer.write(session("replacement"))
            key
        })

        assertEquals("previous", reader.read()?.getString("test_session"))
        assertEquals("replacement", writer.read()?.getString("test_session"))
    }

    @Test fun failedDecryptionDoesNotClearASessionWrittenDuringTheRead() {
        val writer = AppSessionStore(app, testKey = { key })
        writer.write(session("previous"))
        app.getSharedPreferences("yidream_account_session", Context.MODE_PRIVATE)
            .edit().putString("ciphertext", "AAAA").commit()
        val reader = AppSessionStore(app, testKey = {
            writer.write(session("replacement"))
            key
        })

        assertNull(reader.read())
        assertEquals("replacement", writer.read()?.getString("test_session"))
    }

    private fun session(name: String) = JSONObject().put("test_session", name)
}
