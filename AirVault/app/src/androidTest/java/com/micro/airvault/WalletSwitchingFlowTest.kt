package com.micro.airvault

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class WalletSwitchingFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun switchingClearsOldSessionAndAddingWalletUsesCurrentCount() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "wallet-switch-test-${System.nanoTime()}"
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
            }.also { vault.selectWallet(it.first().id) }
        }
        val storeField = MainActivity::class.java.getDeclaredField("vault").apply { isAccessible = true }
        val original = storeField.get(compose.activity) as VaultStore
        val session = ViewModelProvider(compose.activity)[PublicAddressSession::class.java]
        try {
            compose.runOnUiThread {
                storeField.set(compose.activity, vault)
                setState("wallets", wallets)
                setState("currentWallet", wallets.first())
                setState("screen", Screen.HOME)
                setState("requestText", "old request")
                session.walletId = wallets.first().id
                session.address = "old-wallet-address"
                session.unlockedAddresses = mapOf(WalletChain.ETHEREUM to "old-wallet-address")
                session.addressFrames = listOf("old-wallet-export")
            }
            compose.onNodeWithText("钱包1").assertIsDisplayed()
            compose.onNodeWithTag("wallet-card-${wallets.last().id}").performClick()
            compose.waitUntil(10_000) { session.walletId == wallets.last().id }
            compose.onNodeWithText("old-wallet-address").assertDoesNotExist()
            compose.onNodeWithText("导出观察账户").assertDoesNotExist()
            compose.onNodeWithText("解锁地址").assertIsDisplayed()
            compose.runOnIdle {
                assertTrue(session.unlockedAddresses.isEmpty())
                assertTrue(session.addressFrames.isEmpty())
                assertEquals("", state<String>("requestText"))
            }
            runBlocking { assertEquals(wallets.last(), vault.currentWallet()) }

            compose.activityRule.scenario.onActivity {
                MainActivity::class.java.getDeclaredMethod("goBack").apply { isAccessible = true }.invoke(it)
            }
            compose.onNodeWithText("创建新钱包").performClick()
            compose.onNodeWithText("导入助记词").performClick()
            phrase.split(' ').forEach { word ->
                word.take(4).forEach { compose.onNodeWithTag("import-letter-$it").performClick() }
                compose.onNodeWithTag("import-candidate-$word").performClick()
            }
            compose.onNodeWithTag("import-next").performClick()
            compose.waitUntil(10_000) { state<Screen>("screen") == Screen.WALLET_NAME }
            compose.onNodeWithText("钱包3").assertIsDisplayed()
            runBlocking { assertEquals(2, vault.wallets().size) }
        } finally {
            compose.runOnUiThread { storeField.set(compose.activity, original) }
            vault.close()
            context.deleteDatabase("$name.db")
            context.deleteSharedPreferences(name)
        }
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
