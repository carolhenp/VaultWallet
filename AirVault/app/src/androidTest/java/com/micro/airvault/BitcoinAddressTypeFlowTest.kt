package com.micro.airvault

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.micro.airprotocol.BitcoinAddressType
import com.micro.airprotocol.WireCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class BitcoinAddressTypeFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun switchingTypeUpdatesAddressAndExportAndRestoresEachWalletsChoice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "bitcoin-type-test-${System.nanoTime()}"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(ignored: String?, mode: Int): SharedPreferences =
                super.getSharedPreferences(name, mode)
        }
        val vault = VaultStore(isolated, "$name.db") { ByteArray(32) { 42 } }
        val phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val wallets = runBlocking {
            (1..2).map { index ->
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(32) { 7 }, "AES"))
                }
                ProtectedMnemonic.fromString(phrase).use { vault.save("钱包$index", it, cipher) }
            }.also {
                vault.setBitcoinAddressType(it.last().id, BitcoinAddressType.LEGACY)
                vault.selectWallet(it.first().id)
            }
            vault.wallets()
        }
        val data = WalletEngine.publicData(phrase, BitcoinAddressType.SEGWIT)
        val storeField = MainActivity::class.java.getDeclaredField("vault").apply { isAccessible = true }
        val original = storeField.get(compose.activity) as VaultStore
        val session = ViewModelProvider(compose.activity)[PublicAddressSession::class.java]
        try {
            compose.waitForIdle()
            compose.runOnUiThread {
                storeField.set(compose.activity, vault)
                setState("wallets", wallets)
                setState("currentWallet", wallets.first())
                setState("screen", Screen.HOME)
                session.walletId = wallets.first().id
                session.selectedChain = WalletChain.BITCOIN
                session.bitcoinAddressType = BitcoinAddressType.SEGWIT
                MainActivity::class.java.getDeclaredMethod("applyPublicData", WalletEngine.PublicData::class.java)
                    .apply { isAccessible = true }.invoke(compose.activity, data)
            }
            compose.onNodeWithText("创建新钱包").assertIsDisplayed()
            compose.onNodeWithTag("wallet-card-${wallets.first().id}").performClick()
            compose.waitUntil(10_000) { state<Screen>("screen") == Screen.ACCOUNTS }
            compose.onNodeWithTag("wallet-name-edit").performClick()
            compose.onNodeWithTag("rename-wallet-input").performTextReplacement(" ")
            compose.onNodeWithText("保存").assertIsNotEnabled()
            compose.onNodeWithTag("rename-wallet-input").performTextReplacement("x".repeat(31))
            compose.onNodeWithText("保存").assertIsNotEnabled()
            compose.onNodeWithTag("rename-wallet-input").performTextReplacement("旅行钱包")
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(10_000) { state<StoredWallet?>("currentWallet")?.name == "旅行钱包" }
            compose.onNodeWithText("旅行钱包").assertIsDisplayed()
            runBlocking { assertEquals("旅行钱包", vault.currentWallet()!!.name) }
            compose.onNodeWithTag("wallet-address-list").performScrollToNode(hasText("Sonic · EVM"))
            compose.onNodeWithText("扫描签名请求").assertDoesNotExist()
            compose.onNodeWithText("输入签名请求").assertDoesNotExist()
            listOf(BitcoinAddressType.LEGACY, BitcoinAddressType.SEGWIT, BitcoinAddressType.TAPROOT).forEach { type ->
                val account = WalletAccount(WalletChain.BITCOIN, type)
                compose.onNodeWithTag("wallet-address-list").performScrollToNode(hasText(account.title))
                compose.onNodeWithTag("account-${account.id}").performClick()
                compose.waitUntil(10_000) { state<Screen>("screen") == Screen.ADDRESS && !state<Boolean>("busy") }
                compose.onNodeWithText(data.bitcoinAccounts.getValue(type).address).assertIsDisplayed()
                compose.onNodeWithText(type.derivationPath).assertIsDisplayed()
                val scanBounds = compose.onNodeWithTag("address-scan-button").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                compose.onNodeWithTag("address-qr-action").performClick()
                compose.onNodeWithContentDescription("钱包地址二维码").assertIsDisplayed()
                compose.onNodeWithText("关闭").performClick()
                compose.onNodeWithTag("pair-METAMASK").performScrollTo().assertIsNotEnabled()
                compose.onNodeWithTag("pair-TRUST_WALLET").performScrollTo().assertIsNotEnabled()
                assertEquals(scanBounds, compose.onNodeWithTag("address-scan-button").assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
                compose.onNodeWithTag("pair-AIRWALLET").performScrollTo().performClick()
                compose.waitUntil(10_000) { state<Screen>("screen") == Screen.PAIRING }
                compose.onNodeWithText("用 AirWallet 钱包扫描二维码").assertIsDisplayed()
                compose.runOnIdle {
                    assertEquals(1, session.pairingFrames.size)
                    val collector = WireCodec.Collector()
                    var payload: ByteArray? = null
                    session.pairingFrames.forEach { payload = collector.offer(it) }
                    val message = WireCodec.decode(payload!!)
                    assertEquals(WalletChain.BITCOIN.ordinal, message.chain)
                    assertEquals(2, message.fields.size)
                    assertEquals(data.bitcoinAccounts.getValue(type).address, message.fields[0].toString(Charsets.UTF_8))
                }
                compose.onNodeWithContentDescription("返回").performClick()
                compose.onNodeWithTag("pair-TRUST_WALLET").performScrollTo().assertIsNotEnabled()
                compose.onNodeWithTag("address-actions-menu").performClick()
                compose.onNodeWithText("复制地址").performClick()
                compose.runOnIdle {
                    val clipboard = compose.activity.getSystemService(android.content.ClipboardManager::class.java)
                    assertEquals(data.bitcoinAccounts.getValue(type).address, clipboard.primaryClip!!.getItemAt(0).text.toString())
                }
                compose.onNodeWithTag("address-actions-menu").performClick()
                compose.onNodeWithText("地址浏览器").performClick()
                compose.waitUntil(10_000) { session.browserAddresses.size == 100 && !state<Boolean>("busy") }
                compose.onNodeWithText(account.path(0, 0)).assertIsDisplayed()
                compose.onNodeWithTag("derived-address-list").performScrollToIndex(100)
                compose.onNodeWithText(account.path(0, 99)).assertIsDisplayed()
                verifyBrowserQrAndCopy(session.browserAddresses.last())
                compose.onNodeWithTag("address-tab-1").performClick()
                compose.waitUntil(10_000) { session.browserBranch == 1 && session.browserAddresses.size == 100 && !state<Boolean>("busy") }
                compose.onNodeWithTag("derived-address-list").performScrollToIndex(1)
                compose.onNodeWithText(account.path(1, 0)).assertIsDisplayed()
                compose.onNodeWithTag("derived-address-list").performScrollToIndex(100)
                compose.onNodeWithText(account.path(1, 99)).assertIsDisplayed()
                verifyBrowserQrAndCopy(session.browserAddresses.last())
                compose.onNodeWithContentDescription("返回").performClick()
                compose.onNodeWithContentDescription("返回").performClick()
                runBlocking { assertEquals(type, vault.currentWallet()!!.bitcoinAddressType) }
            }
            val zcash = WalletAccount(WalletChain.ZCASH)
            val zcashAddress = "t1XVXWCvpMgBvUaed4XDqWtgQgJSu1Ghz7F"
            compose.onNodeWithTag("wallet-address-list").performScrollToNode(hasText("Zcash · 透明地址"))
            compose.onNodeWithTag("account-${zcash.id}").performClick()
            compose.waitUntil(10_000) { state<Screen>("screen") == Screen.ADDRESS && !state<Boolean>("busy") }
            compose.onNodeWithText(zcashAddress).assertIsDisplayed()
            compose.onNodeWithText("透明地址 · P2PKH").assertIsDisplayed()
            compose.onNodeWithText(zcash.path).assertIsDisplayed()
            compose.onNodeWithTag("address-scan-button").assertDoesNotExist()
            compose.onNodeWithTag("address-qr-action").performClick()
            compose.onNodeWithContentDescription("钱包地址二维码").assertIsDisplayed()
            compose.onNodeWithText("关闭").performClick()
            compose.onNodeWithTag("pair-METAMASK").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("pair-AIRWALLET").performScrollTo().performClick()
            compose.waitUntil(10_000) { state<Screen>("screen") == Screen.PAIRING }
            compose.runOnIdle {
                val collector = WireCodec.Collector()
                var payload: ByteArray? = null
                session.pairingFrames.forEach { payload = collector.offer(it) }
                val message = WireCodec.decode(payload!!)
                assertEquals(1, session.pairingFrames.size)
                assertEquals(WalletChain.ZCASH.ordinal, message.chain)
                assertEquals(2, message.fields.size)
                assertEquals(zcashAddress, message.fields[0].toString(Charsets.UTF_8))
                assertEquals(33, message.fields[1].size)
            }
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithTag("pair-TRUST_WALLET").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Zcash 账号同步仅支持 AirWallet。").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("address-actions-menu").performClick()
            compose.onNodeWithTag("address-scan-menu-item").assertDoesNotExist()
            compose.onNodeWithText("复制地址").performClick()
            compose.runOnIdle {
                val clipboard = compose.activity.getSystemService(android.content.ClipboardManager::class.java)
                assertEquals(zcashAddress, clipboard.primaryClip!!.getItemAt(0).text.toString())
            }
            compose.onNodeWithTag("address-actions-menu").performClick()
            compose.onNodeWithText("地址浏览器").performClick()
            compose.waitUntil(10_000) { session.browserAddresses.size == 100 && !state<Boolean>("busy") }
            compose.onNodeWithText(zcash.path(0, 0)).assertIsDisplayed()
            compose.onNodeWithTag("derived-address-list").performScrollToIndex(100)
            compose.onNodeWithText("t1MvfX6w8rPQwUFtEe8UPPMrkTzqkaqo2Am").assertIsDisplayed()
            compose.onNodeWithTag("address-tab-1").performClick()
            compose.waitUntil(10_000) { session.browserBranch == 1 && session.browserAddresses.size == 100 && !state<Boolean>("busy") }
            compose.onNodeWithTag("derived-address-list").performScrollToIndex(1)
            compose.onNodeWithText("t1YF8h4qviS77p36wWdUfe8faw4DXduYZnm").assertIsDisplayed()
            compose.onNodeWithTag("derived-address-list").performScrollToIndex(100)
            compose.onNodeWithText(zcash.path(1, 99)).assertIsDisplayed()
            compose.onNodeWithText("t1WnBFbNjGxGghqntSQjyxePi3xDUGqLvbx").assertIsDisplayed()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithTag("wallet-address-list").performScrollToNode(hasText("Ethereum · EVM"))
            compose.onNodeWithTag("account-ETHEREUM:").performClick()
            compose.onNodeWithTag("pair-METAMASK").performScrollTo().performClick()
            compose.waitUntil(10_000) { state<Screen>("screen") == Screen.PAIRING }
            compose.onNodeWithText("用 MetaMask 钱包扫描二维码").assertIsDisplayed()
            assertTrue(session.pairingFrames.first().startsWith("UR:CRYPTO-ACCOUNT/"))
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithTag("pair-TRUST_WALLET").performScrollTo().performClick()
            compose.waitUntil(10_000) { state<Screen>("screen") == Screen.PAIRING }
            compose.onNodeWithText("用 TrustWallet 钱包扫描二维码").assertIsDisplayed()
            assertEquals(listOf(data.addresses.getValue(WalletChain.ETHEREUM)), session.pairingFrames)
            compose.onNodeWithContentDescription("返回").performClick()
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission("com.micro.airvault", android.Manifest.permission.CAMERA)
            compose.onNodeWithTag("address-scan-button").performClick()
            compose.runOnIdle { assertEquals(Screen.SCAN, state<Screen>("screen")) }
            compose.onNodeWithContentDescription("返回").performClick()
            compose.runOnIdle { assertEquals(Screen.ADDRESS, state<Screen>("screen")) }
            compose.onNodeWithTag("address-actions-menu").performClick()
            compose.onNodeWithTag("address-scan-menu-item").performClick()
            compose.runOnIdle { assertEquals(Screen.SCAN, state<Screen>("screen")) }
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithText("旅行钱包").assertIsDisplayed()
            compose.onNodeWithTag("wallet-card-${wallets.last().id}").performClick()
            compose.waitUntil(10_000) { session.walletId == wallets.last().id }
            compose.runOnIdle {
                assertEquals(BitcoinAddressType.LEGACY, session.bitcoinAddressType)
                assertTrue(session.bitcoinAccounts.isEmpty())
                assertTrue(session.accountRows.isEmpty())
                assertNull(session.catalog)
                assertTrue(session.pairingFrames.isEmpty())
            }
            runBlocking {
                assertEquals(BitcoinAddressType.TAPROOT, vault.wallets().first().bitcoinAddressType)
                assertEquals(BitcoinAddressType.LEGACY, vault.currentWallet()!!.bitcoinAddressType)
            }
        } finally {
            compose.runOnUiThread { storeField.set(compose.activity, original) }
            vault.close()
            context.deleteDatabase("$name.db")
            context.deleteSharedPreferences(name)
        }
    }

    private fun verifyBrowserQrAndCopy(derived: DerivedAddress) {
        compose.onNodeWithTag("derived-${derived.path}").performClick()
        compose.onNodeWithTag("derived-address-qr-dialog").assertIsDisplayed()
        compose.onNodeWithContentDescription("钱包地址二维码").assertIsDisplayed()
        compose.onNode(hasText(derived.address) and hasAnyAncestor(hasTestTag("derived-address-qr-dialog"))).assertIsDisplayed()
        compose.onNodeWithTag("derived-address-copy").performScrollTo().performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(android.content.ClipboardManager::class.java)
            assertEquals(derived.address, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("derived-address-qr-dialog").assertDoesNotExist()
    }

    private fun <T> setState(name: String, value: T) {
        val field = MainActivity::class.java.getDeclaredField("${name}\$delegate").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(compose.activity) as MutableState<T>).value = value
    }

    private fun <T> state(name: String): T {
        val field = MainActivity::class.java.getDeclaredField("${name}\$delegate").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (field.get(compose.activity) as MutableState<T>).value
    }
}
