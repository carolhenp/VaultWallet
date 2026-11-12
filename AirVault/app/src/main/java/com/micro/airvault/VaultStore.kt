package com.micro.airvault

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import com.micro.airprotocol.BitcoinAddressType
import java.io.Closeable
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class VaultStore internal constructor(
    context: Context,
    databaseName: String,
    derivePassword: () -> ByteArray
) : Closeable {
    constructor(context: Context) : this(context, "vault.db", { VaultDatabaseKey().derivePassword() })

    private val preferences = context.getSharedPreferences("vault", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val databaseFile = context.getDatabasePath(databaseName)
    private val databaseDelegate = lazy { VaultDatabase.open(context, databaseName, derivePassword) }
    private val database by databaseDelegate
    private val initializationMutex = Mutex()
    private var legacyMigrated = false
    @Volatile private var closed = false

    private val hasStoredData: Boolean
        get() = databaseFile.exists() || (preferences.contains(CIPHERTEXT) && preferences.contains(IV))

    private suspend fun dao(): WalletDao = initializationMutex.withLock {
        check(!closed) { "钱包数据库已关闭" }
        database.walletDao().also {
            if (!legacyMigrated) {
                migrateLegacyWallet(it)
                legacyMigrated = true
            }
        }
    }

    internal suspend fun wallets(): List<StoredWallet> =
        if (hasStoredData) dao().wallets() else emptyList()

    internal suspend fun currentWallet(): StoredWallet? =
        if (hasStoredData) dao().currentWallet() else null

    internal suspend fun customChains(): List<CustomChain> =
        if (hasStoredData) dao().customChains() else emptyList()

    internal suspend fun addCustomChain(name: String, path: String, format: CustomAddressFormat, chainId: String = ""): CustomChain {
        val chain = CustomChain(UUID.randomUUID().toString(), CustomChainValidation.name(name),
            CustomChainValidation.path(path, format), format, System.currentTimeMillis(),
            CustomChainValidation.chainId(chainId, format))
        dao().addCustomChain(chain)
        return chain
    }

    internal suspend fun defaultWalletName(): String = "钱包${wallets().size + 1}"

    suspend fun exists(): Boolean = currentWallet() != null

    internal suspend fun selectWallet(id: String) {
        dao().select(id)
    }

    internal suspend fun setBitcoinAddressType(id: String, type: BitcoinAddressType) {
        check(dao().setBitcoinAddressType(id, type) == 1) { "钱包不存在" }
    }

    internal suspend fun renameWallet(id: String, name: String) {
        val normalizedName = name.trim()
        require(normalizedName.length in 1..MAX_NAME_LENGTH) { "钱包名称须为 1–30 个字符" }
        check(dao().rename(id, normalizedName) == 1) { "钱包不存在" }
    }

    fun encryptionCipher(): Cipher = Cipher.getInstance(TRANSFORMATION).apply {
        init(Cipher.ENCRYPT_MODE, getOrCreateKey())
    }

    suspend fun decryptionCipher(walletId: String): Cipher {
        val iv = encryptedMnemonic(walletId).second
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        }
    }

    internal suspend fun save(name: String, mnemonic: ProtectedMnemonic, authenticatedCipher: Cipher): StoredWallet {
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty() && normalizedName.length <= MAX_NAME_LENGTH) { "钱包名称须为 1–30 个字符" }
        val encrypted = mnemonic.withBytes { authenticatedCipher.doFinal(it) }
        return try {
            val wallet = StoredWallet(UUID.randomUUID().toString(), normalizedName)
            dao().save(WalletEntity(wallet.id, wallet.name, encrypted, authenticatedCipher.iv, System.currentTimeMillis()))
            wallet
        } finally {
            encrypted.fill(0)
        }
    }

    internal suspend fun decryptProtected(
        authenticatedCipher: Cipher,
        walletId: String? = null
    ): ProtectedMnemonic {
        val encrypted = encryptedMnemonic(walletId ?: requireCurrentWalletId()).first
        return try {
            val clear = authenticatedCipher.doFinal(encrypted)
            try {
                ProtectedMnemonic.fromBytes(clear)
            } finally {
                SodiumMemory.wipe(clear)
            }
        } finally {
            encrypted.fill(0)
        }
    }

    suspend fun delete(walletId: String? = null) {
        dao().delete(walletId ?: requireCurrentWalletId())
        if (!exists()) keyStore.deleteEntry(KEY_ALIAS)
    }

    override fun close() {
        closed = true
        if (databaseDelegate.isInitialized()) database.close()
    }

    private suspend fun requireCurrentWalletId(): String = currentWallet()?.id ?: error("尚未创建钱包")

    private suspend fun encryptedMnemonic(walletId: String): Pair<ByteArray, ByteArray> {
        val wallet = dao().wallet(walletId) ?: error("钱包不存在")
        return wallet.mnemonic to wallet.iv
    }

    private suspend fun migrateLegacyWallet(dao: WalletDao) {
        val encoded = preferences.getString(CIPHERTEXT, null) ?: return
        val encodedIv = preferences.getString(IV, null) ?: return
        val encrypted = Base64.decode(encoded, Base64.NO_WRAP)
        try {
            dao.migrateLegacy(WalletEntity("legacy-v1", "钱包1", encrypted,
                Base64.decode(encodedIv, Base64.NO_WRAP), 0L))
            // Remove the old copy only after Room's migration transaction has committed.
            withContext(Dispatchers.IO) {
                check(preferences.edit().remove(CIPHERTEXT).remove(IV).commit()) { "无法完成旧钱包迁移，请重试" }
            }
        } finally {
            encrypted.fill(0)
        }
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

    companion object {
        internal const val MAX_NAME_LENGTH = 30
        private const val KEY_ALIAS = "airvault.mnemonic.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val CIPHERTEXT = "ciphertext"
        private const val IV = "iv"
    }
}
