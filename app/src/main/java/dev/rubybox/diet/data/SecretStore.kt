package dev.rubybox.diet.data

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
 * Small secrets (OpenAI key, Dropbox refresh token) encrypted with an AES key held in the
 * Android Keystore. The file is excluded from backups and is never synced.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    @Synchronized
    fun put(name: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = b64(cipher.iv) + ":" + b64(ct)
        prefs.edit().putString(name, packed).apply()
    }

    @Synchronized
    fun get(name: String): String? {
        val packed = prefs.getString(name, null) ?: return null
        return runCatching {
            val (iv, ct) = packed.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    companion object {
        const val OPENAI_KEY = "openai_key"
        const val DROPBOX_REFRESH = "dropbox_refresh"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "diet_secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
