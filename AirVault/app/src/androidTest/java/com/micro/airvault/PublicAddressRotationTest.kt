package com.micro.airvault

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import java.security.KeyStore
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class PublicAddressRotationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun unlockedAddressesAndSelectedChainSurviveActivityRecreation() {
        val preferences = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("vault", Context.MODE_PRIVATE)
        assumeFalse(preferences.contains("ciphertext") || preferences.contains("iv"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeFalse(context.getDatabasePath("vault.db").exists())
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val hadDatabaseKey = keyStore.containsAlias("airvault.database.hmac.v1")
        val fixtureStore = VaultStore(context)
        val data = WalletEngine.publicData("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", com.micro.airprotocol.BitcoinAddressType.SEGWIT)
        val bitcoinAddress = data.addresses.getValue(WalletChain.BITCOIN)
        val ethereumAddress = data.addresses.getValue(WalletChain.ETHEREUM)
        val originalSession = ViewModelProvider(compose.activity)[PublicAddressSession::class.java]

        try {
            val fixture = try {
                runBlocking {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                        init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(32) { 7 }, "AES"))
                    }
                    ProtectedMnemonic.fromString("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
                        .use { fixtureStore.save("钱包1", it, cipher) }
                }
            } catch (exception: IllegalStateException) {
                assumeNoException("此设备不支持硬件 Keystore", exception)
                return
            }
            fixtureStore.close()
            compose.runOnUiThread {
                originalSession.walletId = fixture.id
                originalSession.unlockedAddresses = mapOf(
                    WalletChain.BITCOIN to bitcoinAddress,
                    WalletChain.ETHEREUM to ethereumAddress
                )
                originalSession.selectedChain = WalletChain.BITCOIN
                originalSession.address = bitcoinAddress
                originalSession.catalog = data.catalog
                originalSession.accountRows = WalletAccount.all.associateWith { data.catalog.address(it) }
                originalSession.addressFrames = listOf("public-address-export")
                val field = MainActivity::class.java.getDeclaredField("screen\$delegate")
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                (field.get(compose.activity) as MutableState<Screen>).value = Screen.ACCOUNTS
            }
            compose.onNodeWithText(bitcoinAddress).assertIsDisplayed()
            compose.activityRule.scenario.recreate()

            compose.waitUntil(10_000) {
                val field = MainActivity::class.java.getDeclaredField("screen\$delegate").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                (field.get(compose.activity) as MutableState<Screen>).value == Screen.ACCOUNTS
            }

            compose.activityRule.scenario.onActivity { activity ->
                assertSame(originalSession, ViewModelProvider(activity)[PublicAddressSession::class.java])
            }
            compose.onNodeWithText(bitcoinAddress).assertIsDisplayed()
            compose.onNodeWithText("解锁地址").assertDoesNotExist()

            compose.onNodeWithText("Ethereum · EVM").performClick()
            compose.onNodeWithText(ethereumAddress).assertIsDisplayed()
            compose.onNodeWithText("解锁地址").assertDoesNotExist()
        } finally {
            fixtureStore.close()
            compose.activityRule.scenario.close()
            context.deleteDatabase("vault.db")
            if (!hadDatabaseKey) keyStore.deleteEntry("airvault.database.hmac.v1")
        }
    }
}
