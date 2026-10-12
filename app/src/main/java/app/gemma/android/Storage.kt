package app.gemma.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** How the phone should behave. None of this changes Gemma herself. */
data class Prefs(
    /** Her voice is available at all (play buttons, spoken replies). */
    val voiceOutput: Boolean = true,
    /** Replies are spoken as they arrive, without pressing play. */
    val autoPlay: Boolean = true,
    /** "plain", "balanced" or "expressive": how much performance Vision adds to her delivery. */
    val style: String = "balanced",
    val speed: Float = 1.0f,
    /** In live mode, talking over her stops her (used with earphones). */
    val interrupt: Boolean = true,
    val compact: Boolean = false,
    val shareLocation: Boolean = false,
    val notifications: Boolean = true,
    val developer: Boolean = false,
)

/**
 * What the phone keeps: where Vision is, the access key, how the app should
 * behave, and nothing of Gemma's. The access key is encrypted with a key held
 * in the Android Keystore, so it is not readable from the app's files and is
 * never written in plain text.
 */
class Storage(context: Context) {
    private val prefsFile = context.applicationContext.getSharedPreferences("gemma", Context.MODE_PRIVATE)

    var serverAddress: String
        get() = prefsFile.getString("server_address", "") ?: ""
        set(value) = prefsFile.edit().putString("server_address", value).apply()

    var accessKey: String
        get() = decrypt(prefsFile.getString("access_key_enc", null)) ?: ""
        set(value) {
            val editor = prefsFile.edit()
            if (value.isBlank()) editor.remove("access_key_enc") else editor.putString("access_key_enc", encrypt(value))
            editor.apply()
        }

    var prefs: Prefs
        get() = Prefs(
            voiceOutput = prefsFile.getBoolean("voice_output", true),
            autoPlay = prefsFile.getBoolean("auto_play", true),
            style = prefsFile.getString("voice_style", "balanced") ?: "balanced",
            speed = prefsFile.getFloat("voice_speed", 1.0f),
            interrupt = prefsFile.getBoolean("interrupt", true),
            compact = prefsFile.getBoolean("compact", false),
            shareLocation = prefsFile.getBoolean("share_location", false),
            notifications = prefsFile.getBoolean("notifications", true),
            developer = prefsFile.getBoolean("developer_mode", false),
        )
        set(p) = prefsFile.edit()
            .putBoolean("voice_output", p.voiceOutput).putBoolean("auto_play", p.autoPlay)
            .putString("voice_style", p.style).putFloat("voice_speed", p.speed)
            .putBoolean("interrupt", p.interrupt).putBoolean("compact", p.compact)
            .putBoolean("share_location", p.shareLocation).putBoolean("notifications", p.notifications)
            .putBoolean("developer_mode", p.developer).apply()

    /** The newest notice from Gemma already shown on this phone. -1 = never asked yet. */
    var lastNoticeId: Long
        get() = prefsFile.getLong("last_notice_id", -1)
        set(value) = prefsFile.edit().putLong("last_notice_id", value).apply()

    var askedForNotifications: Boolean
        get() = prefsFile.getBoolean("asked_notifications", false)
        set(value) = prefsFile.edit().putBoolean("asked_notifications", value).apply()

    /** A random name for this phone, so Vision can tell its devices apart. It identifies nothing else. */
    val clientId: String
        get() {
            prefsFile.getString("client_id", null)?.let { return it }
            val bytes = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val id = "android-" + bytes.joinToString("") { "%02x".format(it) }
            prefsFile.edit().putString("client_id", id).apply()
            return id
        }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(sealed, Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }

    private fun decrypt(stored: String?): String? {
        if (stored.isNullOrEmpty()) return null
        return try {
            val (iv, sealed) = stored.split(":", limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(sealed, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            null     // the key was lost (for example after a restore): ask for the access key again
        }
    }

    private companion object {
        const val ALIAS = "gemma_access_key"
    }
}
