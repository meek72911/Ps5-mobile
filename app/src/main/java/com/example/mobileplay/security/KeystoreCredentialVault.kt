package com.example.mobileplay.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.mobileplay.remoteplay.ChiakiNativeBridge
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Paired PlayStation 5 console record stored encrypted via Android Keystore AES-256-GCM.
 * Holds authentic libchiaki registration keys (rpRegistKey + rpKey/morning) returned by PS5 PIN pairing.
 * Never stores plaintext credentials outside Keystore encryption and never uses zero-filled placeholders.
 */
data class RegisteredConsole(
    val hostId: String,
    val hostName: String,
    val hostAddress: String,
    val psnAccountId: String,
    val registrationNote: String,
    val savedAtMs: Long,
    val rpRegistKeyBase64: String = "",
    val rpKeyBase64: String = "",
    val rpKeyType: Int = 0,
    val serverMac: String = ""
) {
    /**
     * Returns true ONLY when authentic, non-zero 16-byte rpRegistKey and rpKey credentials
     * obtained from real libchiaki PS5 registration are present.
     */
    fun hasValidSessionCredentials(): Boolean {
        val registKey = decodeRegistKeyBytes()
        val rpKey = decodeRpKeyBytes()
        return try {
            ChiakiNativeBridge.validateSessionCredentials(registKey, rpKey).isSuccess
        } finally {
            registKey?.fill(0)
            rpKey?.fill(0)
        }
    }

    fun decodeRegistKeyBytes(): ByteArray? {
        if (rpRegistKeyBase64.isBlank()) return null
        return runCatching { Base64.decode(rpRegistKeyBase64, Base64.NO_WRAP) }.getOrNull()
    }

    fun decodeRpKeyBytes(): ByteArray? {
        if (rpKeyBase64.isBlank()) return null
        return runCatching { Base64.decode(rpKeyBase64, Base64.NO_WRAP) }.getOrNull()
    }
}

/**
 * Hardware-backed Android Keystore AES-256-GCM vault for PS5 Remote Play pairing credentials.
 */
class KeystoreCredentialVault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val isHardwareKeystoreAvailable: Boolean
        get() = runCatching { getOrCreateSecretKey() != null }.getOrDefault(false)

    @Synchronized
    fun saveConsole(console: RegisteredConsole): Result<Unit> = runCatching {
        val current = loadConsoles().getOrThrow()
            .filterNot { it.hostId == console.hostId || it.hostAddress == console.hostAddress }
        val updated = listOf(console) + current
        val payload = serializeConsoles(updated)
        val encrypted = encrypt(payload)
        prefs.edit().putString(KEY_VAULT_BLOB, encrypted).apply()
    }

    fun loadConsoles(): Result<List<RegisteredConsole>> = runCatching {
        val blob = prefs.getString(KEY_VAULT_BLOB, null) ?: return@runCatching emptyList()
        val plaintext = decrypt(blob)
        val array = JSONArray(plaintext)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                add(
                    RegisteredConsole(
                        hostId = obj.getString("hostId"),
                        hostName = obj.getString("hostName"),
                        hostAddress = obj.getString("hostAddress"),
                        psnAccountId = obj.getString("psnAccountId"),
                        registrationNote = obj.optString("registrationNote", ""),
                        savedAtMs = obj.optLong("savedAtMs", 0L),
                        rpRegistKeyBase64 = obj.optString("rpRegistKeyBase64", ""),
                        rpKeyBase64 = obj.optString("rpKeyBase64", ""),
                        rpKeyType = obj.optInt("rpKeyType", 0),
                        serverMac = obj.optString("serverMac", "")
                    )
                )
            }
        }
    }

    @Synchronized
    fun removeConsole(hostId: String): Result<Unit> = runCatching {
        val remaining = loadConsoles().getOrThrow().filterNot { it.hostId == hostId }
        if (remaining.isEmpty()) {
            prefs.edit().remove(KEY_VAULT_BLOB).apply()
            return@runCatching
        }
        prefs.edit().putString(KEY_VAULT_BLOB, encrypt(serializeConsoles(remaining))).apply()
    }

    private fun serializeConsoles(list: List<RegisteredConsole>): String =
        JSONArray().apply {
            list.forEach { item ->
                put(
                    JSONObject().apply {
                        put("hostId", item.hostId)
                        put("hostName", item.hostName)
                        put("hostAddress", item.hostAddress)
                        put("psnAccountId", item.psnAccountId)
                        put("registrationNote", item.registrationNote)
                        put("savedAtMs", item.savedAtMs)
                        put("rpRegistKeyBase64", item.rpRegistKeyBase64)
                        put("rpKeyBase64", item.rpKeyBase64)
                        put("rpKeyType", item.rpKeyType)
                        put("serverMac", item.serverMac)
                    }
                )
            }
        }.toString()

    private fun encrypt(plaintext: String): String {
        val key = getOrCreateSecretKey()
            ?: error("Android Keystore is unavailable on this device/runtime.")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val key = getOrCreateSecretKey()
            ?: error("Android Keystore is unavailable on this device/runtime.")
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        require(combined.size > GCM_IV_LENGTH) { "Corrupted encrypted vault payload." }
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, StandardCharsets.UTF_8)
    }

    private fun getOrCreateSecretKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "mobileplay_ps5_vault_aes_gcm_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
        private const val PREFS_NAME = "mobileplay_secure_vault"
        private const val KEY_VAULT_BLOB = "encrypted_consoles_v1"
    }
}
