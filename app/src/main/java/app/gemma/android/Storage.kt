package app.gemma.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * What the phone keeps: where Vision is, the access key, and nothing of Gemma's.
 * The access key is encrypted with a key held in the Android Keystore, so it is
 * not readable from the app's files and is never written in plain text.
 */
class Storage(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("gemma", Context.MODE_PRIVATE)

    var serverAddress: String
        get() = prefs.getString("server_address", "") ?: ""
        set(value) = prefs.edit().putString("server_address", value).apply()

    var developerMode: Boolean
        get() = prefs.getBoolean("developer_mode", false)
        set(value) = prefs.edit().putBoolean("developer_mode", value).apply()

    var accessKey: String
        get() = decrypt(prefs.getString("access_key_enc", null)) ?: ""
        set(value) {
            val editor = prefs.edit()
            if (value.isBlank()) editor.remove("access_key_enc") else editor.putString("access_key_enc", encrypt(value))
            editor.apply()
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
