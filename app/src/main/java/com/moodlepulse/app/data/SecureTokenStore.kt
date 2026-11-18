package com.moodlepulse.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureTokenStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveMoodleToken(token: String) {
        saveEncrypted(KEY_MOODLE_TOKEN, token)
    }

    fun getMoodleToken(): String? = readEncrypted(KEY_MOODLE_TOKEN)

    fun clearMoodleToken() {
        prefs.edit().remove(KEY_MOODLE_TOKEN).apply()
    }

    fun saveGeminiApiKey(apiKey: String) {
        if (apiKey.isBlank()) {
            prefs.edit().remove(KEY_GEMINI_API_KEY).apply()
        } else {
            saveEncrypted(KEY_GEMINI_API_KEY, apiKey.trim())
        }
    }

    fun getGeminiApiKey(): String? = readEncrypted(KEY_GEMINI_API_KEY)

    private fun saveEncrypted(key: String, plainText: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))

        val payload = ByteBuffer.allocate(4 + iv.size + encrypted.size)
            .putInt(iv.size)
            .put(iv)
            .put(encrypted)
            .array()

        val encoded = Base64.getEncoder().encodeToString(payload)
        prefs.edit().putString(key, encoded).apply()
    }

    private fun readEncrypted(key: String): String? {
        val encoded = prefs.getString(key, null) ?: return null
        return runCatching {
            val payload = Base64.getDecoder().decode(encoded)
            val buffer = ByteBuffer.wrap(payload)
            val ivSize = buffer.int
            if (ivSize !in 12..32) return null
            val iv = ByteArray(ivSize)
            buffer.get(iv)
            val cipherBytes = ByteArray(buffer.remaining())
            buffer.get(cipherBytes)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "MoodlePulseMasterKey"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFS_NAME = "moodle_pulse_secure_prefs"
        private const val KEY_MOODLE_TOKEN = "moodle_ws_token"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
    }
}
