package com.micro.airvault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/** Derives a stable database password without exporting the hardware Keystore key. */
internal class VaultDatabaseKey(private val alias: String = "airvault.database.hmac.v1") {
    fun derivePassword(): ByteArray {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (keyStore.getKey(alias, null) as? SecretKey) ?: generateKey(keyStore)
        requireHardwareKey(key)
        return Mac.getInstance("HmacSHA256").run {
            init(key)
            doFinal("AirVault/SQLCipher/password/v1".toByteArray(Charsets.UTF_8))
        }
    }

    private fun generateKey(keyStore: KeyStore): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
        fun specification(strongBox: Boolean) = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setKeySize(256)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setIsStrongBoxBacked(strongBox)
            .build()
        val key = try {
            generator.init(specification(true))
            generator.generateKey()
        } catch (_: StrongBoxUnavailableException) {
            generator.init(specification(false))
            generator.generateKey()
        }
        return try {
            requireHardwareKey(key)
            key
        } catch (exception: Exception) {
            keyStore.deleteEntry(alias)
            throw exception
        }
    }

    private fun requireHardwareKey(key: SecretKey) {
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        check(info.isInsideSecureHardware) { "设备不支持硬件保护的 Keystore 密钥" }
    }
}
