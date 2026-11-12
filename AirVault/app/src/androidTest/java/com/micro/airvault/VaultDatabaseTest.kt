package com.micro.airvault

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import androidx.sqlite.execSQL
import com.micro.airprotocol.BitcoinAddressType

@RunWith(AndroidJUnit4::class)
class VaultDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val mnemonicKey = SecretKeySpec(ByteArray(32) { 7 }, "AES")
    private val ivs = mutableMapOf<String, ByteArray>()
    private val firstPhrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val secondPhrase = "legal winner thank year wave sausage worth useful legal winner thank yellow"

    @Test
    fun walletsRemainSeparateAcrossReopenSelectionAndDeletion() = withIsolatedVault { isolated, name ->
        lateinit var first: StoredWallet
        lateinit var second: StoredWallet
        store(isolated, name).use { vault ->
            assertFalse(vault.exists())
            assertEquals("钱包1", vault.defaultWalletName())
            first = save(vault, "  旅行钱包  ", firstPhrase)
            assertEquals("旅行钱包", first.name)
            assertEquals("钱包2", vault.defaultWalletName())
            second = save(vault, vault.defaultWalletName(), secondPhrase)
            assertNotEquals(first.id, second.id)
            assertEquals(second, vault.currentWallet())
            assertEquals(firstPhrase, decrypt(vault, first.id))
            assertEquals(secondPhrase, decrypt(vault, second.id))
            vault.selectWallet(first.id)
        }
        store(isolated, name).use { vault ->
            assertEquals(first, vault.currentWallet())
            assertEquals(listOf(first, second), vault.wallets())
            vault.delete(first.id)
            assertEquals(second, vault.currentWallet())
            assertEquals(secondPhrase, decrypt(vault, second.id))
            assertEquals("钱包2", vault.defaultWalletName())
        }
    }

    @Test
    fun renamedWalletPersistsWithoutChangingMnemonicSelectionOrOtherWallets() = withIsolatedVault { isolated, name ->
        lateinit var first: StoredWallet
        lateinit var second: StoredWallet
        store(isolated, name).use { vault ->
            first = save(vault, "钱包1", firstPhrase)
            second = save(vault, "钱包2", secondPhrase)
            vault.setBitcoinAddressType(first.id, BitcoinAddressType.TAPROOT)
            vault.selectWallet(first.id)
            vault.renameWallet(first.id, "  旅行钱包  ")
            for (invalid in listOf(" ", "x".repeat(31))) {
                try {
                    vault.renameWallet(first.id, invalid)
                    fail("无效名称不能覆盖钱包名称")
                } catch (_: IllegalArgumentException) { }
            }
        }
        store(isolated, name).use { vault ->
            val renamed = first.copy(name = "旅行钱包", bitcoinAddressType = BitcoinAddressType.TAPROOT)
            assertEquals(renamed, vault.currentWallet())
            assertEquals(listOf(renamed, second), vault.wallets())
            assertEquals(firstPhrase, decrypt(vault, first.id))
            assertEquals(secondPhrase, decrypt(vault, second.id))
            assertEquals("钱包3", vault.defaultWalletName())
        }
    }

    @Test
    fun databaseIsEncryptedAndWrongPasswordPreservesData() = withIsolatedVault { isolated, name ->
        store(isolated, name).use { save(it, "秘密钱包名称", firstPhrase) }
        val file = isolated.getDatabasePath(name)
        val before = file.readBytes()
        assertFalse(before.take(16).toByteArray().contentEquals("SQLite format 3\u0000".toByteArray()))
        assertFalse(String(before, Charsets.ISO_8859_1).contains("wallets"))
        assertFalse(String(before, Charsets.UTF_8).contains("秘密钱包名称"))
        try {
            VaultStore(isolated, name) { ByteArray(32) { 99 } }.use { it.wallets() }
            fail("错误密码不应打开数据库")
        } catch (_: android.database.sqlite.SQLiteException) {
            // SQLCipher may reject the key before invoking the corruption callback.
        } catch (_: IllegalStateException) {
            // Our corruption handler must preserve the original file.
        }
        assertArrayEquals(before, file.readBytes())
        store(isolated, name).use { vault ->
            assertEquals("秘密钱包名称", vault.currentWallet()?.name)
            assertEquals(firstPhrase, decrypt(vault, vault.currentWallet()!!.id))
        }
    }

    @Test
    fun bitcoinAddressTypeIsStoredSeparatelyForEachWallet() = withIsolatedVault { isolated, name ->
        lateinit var first: StoredWallet
        lateinit var second: StoredWallet
        store(isolated, name).use { vault ->
            first = save(vault, "钱包1", firstPhrase)
            second = save(vault, "钱包2", secondPhrase)
            assertEquals(BitcoinAddressType.SEGWIT, first.bitcoinAddressType)
            vault.setBitcoinAddressType(first.id, BitcoinAddressType.LEGACY)
            vault.setBitcoinAddressType(second.id, BitcoinAddressType.TAPROOT)
            vault.selectWallet(first.id)
        }
        store(isolated, name).use { vault ->
            assertEquals(BitcoinAddressType.LEGACY, vault.currentWallet()!!.bitcoinAddressType)
            assertEquals(firstPhrase, decrypt(vault, first.id))
            vault.selectWallet(second.id)
            assertEquals(BitcoinAddressType.TAPROOT, vault.currentWallet()!!.bitcoinAddressType)
            assertEquals(secondPhrase, decrypt(vault, second.id))
        }
    }

    @Test
    fun encryptedVersionOneMigratesWithoutChangingMnemonicOrSelection() = withIsolatedVault { isolated, name ->
        val cipher = encryptCipher()
        val encrypted = cipher.doFinal(firstPhrase.toByteArray())
        ivs["version-one-wallet"] = cipher.iv
        isolated.getDatabasePath(name).parentFile!!.mkdirs()
        VaultSqlCipherDriver { ByteArray(32) { 42 } }.open(isolated.getDatabasePath(name).absolutePath).use { db ->
            db.execSQL("CREATE TABLE wallets (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, mnemonic BLOB NOT NULL, iv BLOB NOT NULL, created_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE vault_state (id INTEGER NOT NULL PRIMARY KEY, wallet_id TEXT, FOREIGN KEY(wallet_id) REFERENCES wallets(id) ON UPDATE NO ACTION ON DELETE SET NULL)")
            db.execSQL("CREATE INDEX index_vault_state_wallet_id ON vault_state(wallet_id)")
            db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            db.execSQL("INSERT INTO room_master_table VALUES(42, '5ddfe2868ad72626d8cbf4aebf91872b')")
            db.prepare("INSERT INTO wallets VALUES(?, ?, ?, ?, ?)").use { statement ->
                statement.bindText(1, "version-one-wallet")
                statement.bindText(2, "旧版钱包")
                statement.bindBlob(3, encrypted)
                statement.bindBlob(4, cipher.iv)
                statement.bindLong(5, 1)
                statement.step()
            }
            db.execSQL("INSERT INTO vault_state VALUES(1, 'version-one-wallet')")
            db.execSQL("PRAGMA user_version = 1")
        }
        store(isolated, name).use { vault ->
            assertEquals(StoredWallet("version-one-wallet", "旧版钱包", BitcoinAddressType.SEGWIT), vault.currentWallet())
            assertEquals(firstPhrase, decrypt(vault, "version-one-wallet"))
            vault.setBitcoinAddressType("version-one-wallet", BitcoinAddressType.TAPROOT)
        }
        store(isolated, name).use { vault ->
            assertEquals(BitcoinAddressType.TAPROOT, vault.currentWallet()!!.bitcoinAddressType)
            assertEquals(firstPhrase, decrypt(vault, "version-one-wallet"))
        }
    }

    @Test
    fun legacyCiphertextMigratesOnceAndIsRemovedAfterCommit() = withIsolatedVault { isolated, name ->
        val cipher = encryptCipher()
        val encrypted = cipher.doFinal(firstPhrase.toByteArray())
        ivs["legacy-v1"] = cipher.iv
        val preferences = isolated.getSharedPreferences("vault", Context.MODE_PRIVATE)
        assertTrue(preferences.edit()
            .putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit())
        store(isolated, name).use { vault ->
            assertEquals("钱包1", vault.currentWallet()?.name)
            assertEquals(firstPhrase, decrypt(vault, vault.currentWallet()!!.id))
            assertFalse(preferences.contains("ciphertext"))
            assertFalse(preferences.contains("iv"))
            save(vault, "钱包2", secondPhrase)
        }
        store(isolated, name).use { vault ->
            assertEquals(2, vault.wallets().size)
            assertEquals("钱包2", vault.currentWallet()?.name)
        }
    }

    @Test
    fun invalidNamesDoNotInsertWallets() = withIsolatedVault { isolated, name ->
        store(isolated, name).use { vault ->
            listOf(" ", "x".repeat(31)).forEach { invalid ->
                try {
                    save(vault, invalid, firstPhrase)
                    fail("无效名称不应被保存")
                } catch (_: IllegalArgumentException) { }
            }
            assertTrue(vault.wallets().isEmpty())
            assertEquals("钱包1", vault.defaultWalletName())
        }
    }

    @Test
    fun customChainsPersistAcrossReopenAndWalletSwitchesAndRejectDuplicates() = withIsolatedVault { isolated, name ->
        lateinit var custom: CustomChain
        store(isolated, name).use { vault ->
            val first = save(vault, "钱包1", firstPhrase)
            save(vault, "钱包2", secondPhrase)
            custom = vault.addCustomChain("  测试网络  ", "m/44h/60H/2'/0/0", CustomAddressFormat.EVM, "31337")
            assertEquals(31337L, custom.chainId)
            assertEquals("测试网络", custom.name)
            assertEquals("m/44'/60'/2'/0/0", custom.derivationPath)
            vault.selectWallet(first.id)
            assertEquals(listOf(custom), vault.customChains())
            for (invalid in listOf("测试网络", "Ethereum", " ")) {
                try {
                    vault.addCustomChain(invalid, "m/44'/60'/0'/0/0", CustomAddressFormat.EVM, "31337")
                    fail("重复或无效的链名称不应保存")
                } catch (_: IllegalArgumentException) { }
            }
            for (invalid in listOf("", "0", "-1", "1.5", "9223372036854775808")) {
                try {
                    vault.addCustomChain("无效 ChainId", "m/44'/60'/0'/0/0", CustomAddressFormat.EVM, invalid)
                    fail("无效 ChainId 不应保存")
                } catch (_: IllegalArgumentException) { }
            }
            try {
                vault.addCustomChain("无效路径", "m/44'/501'/0'/0", CustomAddressFormat.SOLANA)
                fail("Solana 的非强化路径不应保存")
            } catch (_: IllegalArgumentException) { }
        }
        store(isolated, name).use { vault ->
            assertEquals(listOf(custom), vault.customChains())
            assertEquals(2, vault.wallets().size)
            assertEquals(firstPhrase, decrypt(vault, vault.currentWallet()!!.id))
        }
        assertFalse(String(isolated.getDatabasePath(name).readBytes(), Charsets.UTF_8).contains("测试网络"))
    }

    @Test
    fun versionTwoMigratesWithoutChangingWalletOrBitcoinType() = withIsolatedVault { isolated, name ->
        lateinit var wallet: StoredWallet
        store(isolated, name).use { vault ->
            wallet = save(vault, "升级钱包", firstPhrase)
            vault.setBitcoinAddressType(wallet.id, BitcoinAddressType.TAPROOT)
        }
        // Recreate precisely the previous schema with its exported Room identity hash.
        VaultSqlCipherDriver { ByteArray(32) { 42 } }.open(isolated.getDatabasePath(name).absolutePath).use { db ->
            db.execSQL("DROP TABLE custom_chains")
            db.execSQL("UPDATE room_master_table SET identity_hash = 'ebfb5be408da524749fdd2176a4bbb17' WHERE id = 42")
            db.execSQL("PRAGMA user_version = 2")
        }
        store(isolated, name).use { vault ->
            assertEquals(wallet.copy(bitcoinAddressType = BitcoinAddressType.TAPROOT), vault.currentWallet())
            assertEquals(firstPhrase, decrypt(vault, wallet.id))
            assertTrue(vault.customChains().isEmpty())
            vault.addCustomChain("升级后新增", "m/44'/60'/0'/0/0", CustomAddressFormat.EVM, "31337")
        }
        store(isolated, name).use { assertEquals("升级后新增", it.customChains().single().name) }
    }

    @Test
    fun versionThreePreservesExistingChainsWithoutInventingChainIds() = withIsolatedVault { isolated, name ->
        lateinit var wallet: StoredWallet
        val oldChains = listOf(
            CustomChain("old-evm", "已有 EVM", "m/44'/60'/4'/0/0", CustomAddressFormat.EVM, 1),
            CustomChain("old-bitcoin", "已有 Bitcoin", "m/84'/0'/2'/0/0", CustomAddressFormat.BITCOIN_SEGWIT, 2)
        )
        store(isolated, name).use { vault ->
            wallet = save(vault, "升级前钱包", firstPhrase)
            vault.setBitcoinAddressType(wallet.id, BitcoinAddressType.LEGACY)
        }
        VaultSqlCipherDriver { ByteArray(32) { 42 } }.open(isolated.getDatabasePath(name).absolutePath).use { db ->
            db.execSQL("DROP TABLE custom_chains")
            db.execSQL("CREATE TABLE custom_chains (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, derivationPath TEXT NOT NULL, addressFormat TEXT NOT NULL, createdAt INTEGER NOT NULL)")
            db.execSQL("CREATE UNIQUE INDEX index_custom_chains_name ON custom_chains(name)")
            oldChains.forEach { chain ->
                db.prepare("INSERT INTO custom_chains VALUES (?, ?, ?, ?, ?)").use { statement ->
                    statement.bindText(1, chain.id)
                    statement.bindText(2, chain.name)
                    statement.bindText(3, chain.derivationPath)
                    statement.bindText(4, chain.addressFormat.name)
                    statement.bindLong(5, chain.createdAt)
                    statement.step()
                }
            }
            db.execSQL("UPDATE room_master_table SET identity_hash = 'c0a31ec602343d8c8440a4f8f99ebad6' WHERE id = 42")
            db.execSQL("PRAGMA user_version = 3")
        }
        lateinit var added: CustomChain
        store(isolated, name).use { vault ->
            assertEquals(wallet.copy(bitcoinAddressType = BitcoinAddressType.LEGACY), vault.currentWallet())
            assertEquals(firstPhrase, decrypt(vault, wallet.id))
            assertEquals(oldChains, vault.customChains())
            added = vault.addCustomChain("新增网络", "m/44'/60'/0'/0/0", CustomAddressFormat.EVM, "2147483648")
        }
        store(isolated, name).use { vault ->
            assertEquals(oldChains + added, vault.customChains())
            assertEquals(2147483648L, vault.customChains().last().chainId)
        }
    }

    @Test
    fun hardwareKeystoreDerivationIsStableOrRejectsSoftwareKeys() {
        val alias = "airvault.database.test.${System.nanoTime()}"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            val first = try {
                VaultDatabaseKey(alias).derivePassword()
            } catch (exception: IllegalStateException) {
                assertEquals("设备不支持硬件保护的 Keystore 密钥", exception.message)
                assertFalse(keyStore.containsAlias(alias))
                return
            }
            val second = VaultDatabaseKey(alias).derivePassword()
            try {
                assertEquals(32, first.size)
                assertArrayEquals(first, second)
                assertNull(keyStore.getKey(alias, null).encoded)
            } finally {
                SodiumMemory.wipe(first)
                SodiumMemory.wipe(second)
            }
        } finally {
            keyStore.deleteEntry(alias)
        }
    }

    private fun store(isolated: Context, name: String) = VaultStore(isolated, name) { ByteArray(32) { 42 } }

    private fun encryptCipher() = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, mnemonicKey)
    }

    private suspend fun save(vault: VaultStore, name: String, phrase: String): StoredWallet {
        val cipher = encryptCipher()
        return ProtectedMnemonic.fromString(phrase).use { vault.save(name, it, cipher) }
            .also { ivs[it.id] = cipher.iv }
    }

    private suspend fun decrypt(vault: VaultStore, id: String): String {
        // Test ciphers bypass biometrics; production uses an authenticated Keystore CryptoObject.
        val iv = ivs.getValue(id)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, mnemonicKey, GCMParameterSpec(128, iv))
        }
        return vault.decryptProtected(cipher, id).use { it.withMnemonic { phrase -> phrase } }
    }

    private fun withIsolatedVault(block: suspend (Context, String) -> Unit): Unit = runBlocking {
        val name = "vault-database-test-${System.nanoTime()}"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(ignored: String?, mode: Int): SharedPreferences =
                super.getSharedPreferences(name, mode)
        }
        try {
            block(isolated, "$name.db")
        } finally {
            context.deleteDatabase("$name.db")
            context.deleteSharedPreferences(name)
        }
    }
}
