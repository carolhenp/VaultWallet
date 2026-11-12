package com.micro.airvault

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class WalletStartupTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "startup-test-${System.nanoTime()}"
    private val isolated = object : ContextWrapper(context) {
        override fun getSharedPreferences(ignored: String?, mode: Int): SharedPreferences = super.getSharedPreferences(name, mode)
    }

    @Test fun existingWalletWaitsOnSplashThenGoesDirectlyToWalletCards() {
        val wallet = seedWallet()
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val store = VaultStore(isolated, "$name.db") {
            entered.countDown()
            check(release.await(20, TimeUnit.SECONDS))
            ByteArray(32) { 42 }
        }
        withStore(store) {
            try {
                initialize()
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                compose.onNodeWithTag("wallet-splash").assertIsDisplayed()
                compose.onNodeWithTag("wallet-card-list").assertDoesNotExist()
                assertEquals(Screen.SPLASH, state<Screen>("screen"))
                release.countDown()
                compose.waitUntil(15_000) { state<Screen>("screen") == Screen.HOME && !state<Boolean>("busy") }
                compose.onNodeWithTag("wallet-card-${wallet.id}").assertIsDisplayed()
                compose.onNodeWithTag("wallet-splash").assertDoesNotExist()
            } finally { release.countDown() }
        }
    }

    @Test fun databaseFailureStaysOnSplashAndRetryLoadsWalletInsteadOfShowingCreation() {
        val wallet = seedWallet()
        val unavailable = AtomicBoolean(true)
        withStore(VaultStore(isolated, "$name.db") {
            check(!unavailable.get()) { "测试数据库暂时不可用" }
            ByteArray(32) { 42 }
        }) {
            initialize()
            compose.waitUntil(10_000) { !state<Boolean>("busy") }
            assertEquals(Screen.SPLASH, state<Screen>("screen"))
            compose.onNodeWithTag("wallet-splash").assertIsDisplayed()
            compose.onNodeWithTag("wallet-card-list").assertDoesNotExist()
            unavailable.set(false)
            compose.onNodeWithTag("startup-retry").performClick()
            compose.waitUntil(15_000) { state<Screen>("screen") == Screen.HOME && !state<Boolean>("busy") }
            compose.onNodeWithTag("wallet-card-${wallet.id}").assertIsDisplayed()
        }
    }

    @Test fun emptyDatabaseEntersWelcomeAfterStartupCheck() {
        withStore(VaultStore(isolated, "$name.db") { ByteArray(32) { 42 } }) {
            initialize()
            compose.waitUntil(10_000) { !state<Boolean>("busy") && state<Screen>("screen") != Screen.SPLASH }
            assertEquals(Screen.WELCOME, state<Screen>("screen"))
            compose.onNodeWithTag("wallet-splash").assertDoesNotExist()
        }
    }

    private val storeField get() = MainActivity::class.java.getDeclaredField("vault").apply { isAccessible = true }

    private fun withStore(store: VaultStore, block: () -> Unit) {
        compose.waitUntil(15_000) { !state<Boolean>("busy") }
        val original = storeField.get(compose.activity)
        compose.runOnUiThread { storeField.set(compose.activity, store) }
        try { block() } finally {
            compose.runOnUiThread { storeField.set(compose.activity, original) }
            store.close()
            context.deleteDatabase("$name.db")
            context.deleteSharedPreferences(name)
        }
    }

    private fun initialize() = compose.runOnUiThread {
        MainActivity::class.java.getDeclaredMethod("initializeWallets").apply { isAccessible = true }.invoke(compose.activity)
    }

    private fun seedWallet(): StoredWallet = runBlocking {
        VaultStore(isolated, "$name.db") { ByteArray(32) { 42 } }.use { store ->
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(32) { 7 }, "AES"))
            }
            ProtectedMnemonic.fromString(List(11) { "abandon" }.plus("about").joinToString(" ")).use {
                store.save("已有钱包", it, cipher)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> state(name: String): T = (MainActivity::class.java.getDeclaredField("${name}\$delegate")
        .apply { isAccessible = true }.get(compose.activity) as MutableState<T>).value
}
