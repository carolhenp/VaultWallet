package com.micro.airvault

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.micro.airprotocol.BitcoinAddressType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class CustomChainFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun menuSavesCustomPathAndAddressBrowserUsesBothBranches() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "custom-chain-flow-${System.nanoTime()}"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(ignored: String?, mode: Int): SharedPreferences = super.getSharedPreferences(name, mode)
        }
        val store = VaultStore(isolated, "$name.db") { ByteArray(32) { 42 } }
        val mnemonic = List(11) { "abandon" }.plus("about").joinToString(" ")
        val wallet = runBlocking {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(32) { 7 }, "AES"))
            }
            ProtectedMnemonic.fromString(mnemonic).use { store.save("测试钱包", it, cipher) }
        }
        compose.waitUntil(15_000) { !state<Boolean>("busy") }
        val field = MainActivity::class.java.getDeclaredField("vault").apply { isAccessible = true }
        val original = field.get(compose.activity)
        val session = ViewModelProvider(compose.activity)[PublicAddressSession::class.java]
        try {
            compose.runOnUiThread {
                field.set(compose.activity, store)
                setState("wallets", listOf(wallet))
                setState("currentWallet", wallet)
                setState("screen", Screen.ACCOUNTS)
            }
            compose.onNodeWithTag("home-actions-menu").performClick()
            compose.onNodeWithText("添加钱包").assertDoesNotExist()
            compose.onNodeWithText("添加自定义链").performClick()
            compose.onNodeWithTag("save-custom-chain").assertIsNotEnabled()
            compose.onNodeWithTag("custom-chain-name").performTextReplacement("测试 EVM 网络")
            compose.onNodeWithTag("custom-chain-format").performClick()
            compose.onNodeWithText("Solana").performClick()
            compose.onNodeWithTag("custom-chain-id").assertDoesNotExist()
            compose.onNodeWithTag("save-custom-chain").assertIsEnabled()
            compose.onNodeWithTag("custom-chain-format").performClick()
            compose.onNodeWithText("EVM (0x)").performClick()
            compose.onNodeWithTag("custom-chain-path").performTextReplacement("m/44'/60'/5'/0/7")
            compose.onNodeWithTag("save-custom-chain").assertIsNotEnabled()
            compose.onNodeWithTag("custom-chain-id").performTextReplacement("0")
            compose.onNodeWithTag("save-custom-chain").assertIsNotEnabled()
            compose.onNodeWithTag("custom-chain-id").performTextReplacement("31337")
            compose.onNodeWithTag("save-custom-chain").performScrollTo().performClick()
            compose.waitUntil(15_000) { state<Screen>("screen") == Screen.ACCOUNTS && !state<Boolean>("busy") }
            val custom = runBlocking { store.customChains().single() }
            assertEquals("m/44'/60'/5'/0/7", custom.derivationPath)
            assertEquals(31337L, custom.chainId)
            runBlocking {
                VaultStore(isolated, "$name.db") { ByteArray(32) { 42 } }.use {
                    assertEquals(custom, it.customChains().single())
                }
            }
            val account = WalletAccount.custom(custom)
            val data = WalletEngine.publicData(mnemonic, BitcoinAddressType.SEGWIT, listOf(custom))
            // Production unlock uses biometrics. Supply its public result to exercise subsequent UI on an emulator.
            compose.runOnUiThread {
                MainActivity::class.java.getDeclaredMethod("applyPublicData", WalletEngine.PublicData::class.java)
                    .apply { isAccessible = true }.invoke(compose.activity, data)
            }
            compose.onNodeWithTag("wallet-address-list").performScrollToNode(hasTestTag("account-${account.id}"))
            compose.onNodeWithTag("account-${account.id}").performClick()
            compose.waitUntil(10_000) { state<Screen>("screen") == Screen.ADDRESS && !state<Boolean>("busy") }
            compose.onNodeWithText(custom.derivationPath).assertIsDisplayed()
            compose.onNodeWithText(data.catalog.address(account).address).assertIsDisplayed()
            compose.onNodeWithTag("custom-chain-id-value").performScrollTo().assertTextEquals("31337")
            compose.onNodeWithTag("address-scan-button").assertDoesNotExist()
            compose.onNodeWithTag("address-actions-menu").performClick()
            compose.onNodeWithText("扫描签名请求").assertDoesNotExist()
            compose.onNodeWithText("地址浏览器").performClick()
            compose.waitUntil(10_000) { session.browserAddresses.size == 100 && !state<Boolean>("busy") }
            compose.onNodeWithText(account.path(0, 0)).assertIsDisplayed()
            compose.onNodeWithTag("address-tab-1").performClick()
            compose.waitUntil(10_000) { session.browserBranch == 1 && session.browserAddresses.size == 100 && !state<Boolean>("busy") }
            compose.onNodeWithText(account.path(1, 0)).assertIsDisplayed()
            compose.onNodeWithTag("derived-address-list").performScrollToIndex(100)
            compose.onNodeWithText(account.path(1, 99)).assertIsDisplayed()
        } finally {
            compose.runOnUiThread { field.set(compose.activity, original) }
            store.close()
            context.deleteDatabase("$name.db")
            context.deleteSharedPreferences(name)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> state(name: String): T = (MainActivity::class.java.getDeclaredField("${name}\$delegate")
        .apply { isAccessible = true }.get(compose.activity) as MutableState<T>).value

    @Suppress("UNCHECKED_CAST")
    private fun <T> setState(name: String, value: T) {
        (MainActivity::class.java.getDeclaredField("${name}\$delegate").apply { isAccessible = true }
            .get(compose.activity) as MutableState<T>).value = value
    }
}
