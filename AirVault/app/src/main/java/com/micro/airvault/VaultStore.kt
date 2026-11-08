package com.micro.airvault

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

class VaultStore(context: Context) {
    private val preferences = context.getSharedPreferences("vault", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    val exists: Boolean
        get() = preferences.contains(CIPHERTEXT) && preferences.contains(IV)

    fun encryptionCipher(): Cipher = Cipher.getInstance(TRANSFORMATION).apply {
        init(Cipher.ENCRYPT_MODE, getOrCreateKey())
    }

    fun decryptionCipher(): Cipher {
        val iv = Base64.decode(preferences.getString(IV, null) ?: error("Vault is empty"), Base64.NO_WRAP)
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        }
    }

    fun save(mnemonic: String, authenticatedCipher: Cipher) {
        val clear = mnemonic.toByteArray(Charsets.UTF_8)
        try {
            val encrypted = authenticatedCipher.doFinal(clear)
            try {
                check(preferences.edit()
                    .putString(CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putString(IV, Base64.encodeToString(authenticatedCipher.iv, Base64.NO_WRAP))
                    .commit()) { "Could not save vault" }
            } finally {
                encrypted.fill(0)
            }
        } finally {
            clear.fill(0)
        }
    }

    fun decrypt(authenticatedCipher: Cipher): String {
        val encrypted = Base64.decode(preferences.getString(CIPHERTEXT, null) ?: error("Vault is empty"), Base64.NO_WRAP)
        return try {
            val clear = authenticatedCipher.doFinal(encrypted)
            try {
                String(clear, Charsets.UTF_8)
            } finally {
                clear.fill(0)
            }
        } finally {
            encrypted.fill(0)
        }
    }

    fun delete() {
        check(preferences.edit().clear().commit()) { "Could not remove vault" }
        keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return requireHardwareKey(it) }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        fun specification(strongBox: Boolean): KeyGenParameterSpec {
            val builder = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            } else {
                builder.setUserAuthenticationValidityDurationSeconds(-1)
            }
            if (strongBox) builder.setIsStrongBoxBacked(true)
            return builder.build()
        }
        val key = try {
            generator.init(specification(true))
            generator.generateKey()
        } catch (_: StrongBoxUnavailableException) {
            generator.init(specification(false))
            generator.generateKey()
        }
        return try {
            requireHardwareKey(key)
        } catch (exception: Exception) {
            keyStore.deleteEntry(KEY_ALIAS)
            throw exception
        }
    }

    private fun requireHardwareKey(key: SecretKey): SecretKey {
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        check(info.isInsideSecureHardware) { "设备不支持硬件保护的 Keystore 密钥" }
        return key
    }

    private companion object {
        const val KEY_ALIAS = "airvault.mnemonic.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val CIPHERTEXT = "ciphertext"
        const val IV = "iv"
    }
}
