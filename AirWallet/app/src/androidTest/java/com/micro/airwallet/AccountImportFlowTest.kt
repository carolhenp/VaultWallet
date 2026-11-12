package com.micro.airwallet

import androidx.compose.runtime.MutableState
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.micro.airprotocol.WireCodec
import com.micro.airprotocol.WireMessage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.web3j.utils.Numeric

@RunWith(AndroidJUnit4::class)
class AccountImportFlowTest {
    @Test fun singleChainImportSelectsThatNetworkPreservesOthersAndRejectsInvalidReplacement() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "single-account-test-${System.nanoTime()}"
        val store = WatchStore(context, name, "$name.db")
        val existing = mapOf(WalletChain.ETHEREUM to WatchedAccount("0x9858effd232b4033e47d90003d41ec34ecaeda94"))
        val zcash = WatchedAccount("t1XVXWCvpMgBvUaed4XDqWtgQgJSu1Ghz7F",
            "03db98d8f87716269ed31879aef19bdadbc869a9ea67729e36332d023b916cbcc9")
        val message = WireMessage(WalletChain.ZCASH.ordinal, WireCodec.ADDRESSES, ByteArray(16) { 7 },
            listOf(zcash.address.toByteArray(), Numeric.hexStringToByteArray(zcash.publicKey)))
        val expected = existing + (WalletChain.ZCASH to zcash)
        try {
            store.saveAccounts(existing)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val field = MainActivity::class.java.getDeclaredField("store").apply { isAccessible = true }
                    val original = field.get(activity)
                    val scan = MainActivity::class.java.getDeclaredMethod("scan", String::class.java).apply { isAccessible = true }
                    try {
                        field.set(activity, store)
                        state<Map<WalletChain, WatchedAccount>>(activity, "accounts").value = existing
                        state<Screen>(activity, "screen").value = Screen.SCAN_ACCOUNT
                        scan.invoke(activity, WireCodec.singleFrame(WireCodec.encode(message)))
                        assertEquals(Screen.HOME, state<Screen>(activity, "screen").value)
                        assertEquals(WalletChain.ZCASH, state<WalletChain>(activity, "selectedChain").value)
                        assertEquals(expected, state<Map<WalletChain, WatchedAccount>>(activity, "accounts").value)
                        assertEquals(expected, store.accounts())
                        val invalid = message.copy(fields = listOf("invalid-address".toByteArray(), message.fields[1]))
                        state<Screen>(activity, "screen").value = Screen.SCAN_ACCOUNT
                        scan.invoke(activity, WireCodec.singleFrame(WireCodec.encode(invalid)))
                        assertEquals(expected, store.accounts())
                        assertEquals(expected, state<Map<WalletChain, WatchedAccount>>(activity, "accounts").value)
                        assertTrue(state<String>(activity, "error").value.isNotEmpty())
                    } finally {
                        state<Screen>(activity, "screen").value = Screen.HOME
                        field.set(activity, original)
                    }
                }
            }
            val reopened = WatchStore(context, name, "$name.db")
            try { assertEquals(expected, reopened.accounts()) } finally { reopened.close() }
        } finally {
            store.close()
            context.deleteSharedPreferences(name)
            context.deleteDatabase("$name.db")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> state(activity: MainActivity, name: String): MutableState<T> =
        MainActivity::class.java.getDeclaredField("${name}\$delegate").apply { isAccessible = true }
            .get(activity) as MutableState<T>
}
