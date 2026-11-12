package com.micro.airvault

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class VaultStoreProtectedTest {
    @Test
    fun encryptedMnemonicRoundTripsThroughProtectedMemory(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferencesName = "vault-sodium-test-${System.nanoTime()}"
        val databaseName = "$preferencesName.db"
        val isolatedContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                super.getSharedPreferences(preferencesName, mode)
        }
        val keyBytes = ByteArray(32) { (it + 1).toByte() }
        val source = "abandon ability able about above absent absorb abstract absurd abuse access accident"
        try {
            val vault = VaultStore(isolatedContext, databaseName) { ByteArray(32) { 42 } }
            vault.use {
                val key = SecretKeySpec(keyBytes, "AES")
                val encrypt = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
                ProtectedMnemonic.fromString(source).use { vault.save("钱包1", it, encrypt) }
                val decrypt = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypt.iv))
                }
                vault.decryptProtected(decrypt).use { protected ->
                    assertEquals(source, protected.withMnemonic { it })
                }
            }
        } finally {
            SodiumMemory.wipe(keyBytes)
            context.deleteSharedPreferences(preferencesName)
            context.deleteDatabase(databaseName)
        }
    }
}
