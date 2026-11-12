package com.yidream.mdm

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Système de code de déverrouillage journalier.
 *
 * Le mot de passe maître n'est JAMAIS stocké sur le téléphone, ni en clair
 * ni haché directement. À la place :
 *
 *  - Lors de la config (Web ADB), une "clé intermédiaire" est dérivée du
 *    mot de passe maître + un sel (HMAC-SHA256), et c'est CETTE clé
 *    intermédiaire qui est poussée sur le téléphone (dans yidream_config.json).
 *  - Le code du jour = HMAC-SHA256(clé intermédiaire, date du jour), tronqué
 *    à 6 caractères.
 *  - Le téléphone peut donc vérifier un code sans jamais avoir connu le
 *    mot de passe original.
 *
 * Limite honnête : la clé intermédiaire stockée sur le téléphone permet en
 * théorie de calculer tous les codes passés/futurs si elle est extraite
 * (accès root, backup, etc.). C'est néanmoins largement plus sûr qu'un PIN
 * statique stocké tel quel : le mot de passe original n'est jamais exposé,
 * et changer le sel côté Web ADB invalide immédiatement tous les codes.
 */
class UnlockManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "yidream_unlock"
        private const val KEY_INTERMEDIATE = "intermediate_key"
        private const val FAILED_ATTEMPTS = "failed_attempts"
        private const val RETRY_AT = "retry_at"
        private const val WAIT_MS = 15 * 60_000L
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun storeIntermediateKey(base64Key: String) {
        require(decodeKey(base64Key) != null)
        check(prefs().edit().putString(KEY_INTERMEDIATE, base64Key).commit())
    }

    fun hasKeyConfigured(): Boolean = recoveryKey() != null

    private fun decodeKey(value: String): ByteArray? = runCatching {
        Base64.decode(value, Base64.NO_WRAP).takeIf { it.size == 32 }
    }.getOrNull()

    private fun recoveryKey(): ByteArray? = prefs().getString(KEY_INTERMEDIATE, null)?.let(::decodeKey)

    private fun hmacSha256(keyBytes: ByteArray, message: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
    }

    private fun codeFor(keyBytes: ByteArray, dateString: String): String {
        val digest = hmacSha256(keyBytes, dateString)
        // 6 caractères hexadécimaux en majuscules, faciles à taper
        return digest.joinToString("") { "%02X".format(it) }.take(6)
    }

    /**
     * Vérifie le code saisi par l'utilisateur contre le code du jour
     * (avec une marge de ±1 jour pour tolérer les changements de fuseau
     * horaire / minuit proche).
     */
    enum class Verification { ACCEPTED, INCORRECT, LOCKED }

    fun verify(inputCode: String): Verification {
        val key = recoveryKey() ?: return Verification.INCORRECT
        val now = System.currentTimeMillis()
        val prefs = prefs()
        val retryAt = prefs.getLong(RETRY_AT, 0L)
        if (retryAt > now) return Verification.LOCKED
        val cleaned = inputCode.trim().uppercase(Locale.ROOT)
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val candidates = listOf(-1, 0, 1).map { offsetDays ->
            formatter.format(Date(now + offsetDays * 24L * 60 * 60 * 1000))
        }
        if (candidates.any { codeFor(key, it) == cleaned }) {
            prefs.edit().remove(FAILED_ATTEMPTS).remove(RETRY_AT).commit()
            return Verification.ACCEPTED
        }
        val attempts = if (retryAt > 0L) 1 else prefs.getInt(FAILED_ATTEMPTS, 0) + 1
        if (attempts >= 5) {
            prefs.edit().putLong(RETRY_AT, now + WAIT_MS).remove(FAILED_ATTEMPTS).commit()
            return Verification.LOCKED
        }
        prefs.edit().putInt(FAILED_ATTEMPTS, attempts).remove(RETRY_AT).commit()
        return Verification.INCORRECT
    }
}
